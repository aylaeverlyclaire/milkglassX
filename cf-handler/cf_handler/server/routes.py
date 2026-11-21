import asyncio
import logging
import time
from contextlib import asynccontextmanager
from typing import Optional, AsyncGenerator
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Request, Response, Query, Depends

from cf_handler.core.handler import CFhandler
from cf_handler.core.resolver import parse_source_order, resolve_split_plane
from cf_handler.core.animasu import search_animasu, DEFAULT_ANIMASU_BASE_URL
from cf_handler.core.mirror import RequestMirror
from cf_handler.server.models import (
    BrowserPoolResponse, CookieResponse, CacheStatsResponse, CacheClearResponse, ErrorResponse,
    TurnstileRequest, TurnstileResponse,
)
from cf_handler.utils.constants import APP_VERSION, PROXY_SCHEMES, CF_COOKIE_PREFIXES
from cf_handler.utils.security import is_safe_url, redact_proxy

global_handler = None
global_mirror = None

logger = logging.getLogger(__name__)

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    \
    global global_handler, global_mirror

    logger.info("Starting Cloudflare handler Server...")

    global_handler = CFhandler(log=True)
    await global_handler.start()
    global_mirror = RequestMirror(global_handler)

    logger.info("Server initialization complete")

    yield

    logger.info("Shutting down Cloudflare handler Server...")

    try:
        if global_mirror:
            await global_mirror.cleanup()

        if global_handler:
            await global_handler.cleanup()
    except Exception as e:
        logger.error(f"Error during shutdown: {e}")

    logger.info("Server shutdown complete")

_inflight_locks: dict[str, asyncio.Lock] = {}

def _get_target_lock(scope: str, url: Optional[str], proxy: Optional[str]) -> asyncio.Lock:
    key = f"{scope}::{(url or '').strip().lower()}::{(proxy or '').strip()}"
    if len(_inflight_locks) > 256:
        for k in list(_inflight_locks.keys()):
            if not _inflight_locks[k].locked():
                _inflight_locks.pop(k, None)
    lock = _inflight_locks.get(key)
    if lock is None:
        lock = asyncio.Lock()
        _inflight_locks[key] = lock
    return lock

def get_handler() -> CFhandler:
    \
    return global_handler

def get_mirror() -> RequestMirror:
    \
    return global_mirror

def _validate_request(request: Request, url: Optional[str], proxy: Optional[str]) -> bool:
    \
    if any(key.lower() == 'x-hostname' for key in request.headers.keys()):
        return True

    if not url:
        raise HTTPException(
            status_code=400,
            detail="url parameter is required when x-hostname header is not present"
        )

    if not is_safe_url(url):
        raise HTTPException(
            status_code=400,
            detail="Invalid or unsafe URL - localhost and private IPs are not allowed"
        )

    if proxy and not proxy.startswith(PROXY_SCHEMES):
        raise HTTPException(
            status_code=400,
            detail="Proxy must start with http://, https://, socks4://, or socks5://"
        )

    return False

def setup_routes(app: FastAPI):
\

    @app.get("/cookies", response_model=CookieResponse, responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}})
    async def get_cookies(
        request: Request,
        url: Optional[str] = Query(None, description="Target URL to get cookies for"),
        retries: int = Query(5, ge=1, le=10, description="Number of retry attempts"),
        proxy: Optional[str] = Query(None, description="Proxy URL (optional)"),
        HandleCookieCache: bool = Query(False, description="Force fresh cookie generation"),
        handler: CFhandler = Depends(get_handler),
    ):
        \
        if _validate_request(request, url, proxy):
            return await mirror_request(request, "cookies", get_mirror())

        try:
            start_time = time.time()
            logger.info(f"Getting cookies for {url} (retries: {retries}, proxy: {'yes' if proxy else 'no'})")

            async with _get_target_lock("cookies", url, proxy):
                data = await handler.get_or_generate_cookies(url, proxy, Handle_cache=HandleCookieCache)

            if not data:
                raise HTTPException(status_code=500, detail="Failed to Handle Cloudflare protection")

            generation_time = int((time.time() - start_time) * 1000)
            cf_cookies = [name for name in data["cookies"].keys() if name.startswith(CF_COOKIE_PREFIXES)]

            logger.info(f"Successfully generated {len(data['cookies'])} cookies in {generation_time}ms")
            logger.info(f"Cloudflare cookies: {cf_cookies}")

            return CookieResponse(
                cookies=data["cookies"],
                user_agent=data["user_agent"]
            )

        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error getting cookies for {url}: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/html", responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}})
    async def get_html(
        request: Request,
        url: Optional[str] = Query(None, description="Target URL to get HTML content for"),
        retries: int = Query(5, ge=1, le=10, description="Number of retry attempts"),
        proxy: Optional[str] = Query(None, description="Proxy URL (optional)"),
        HandleCookieCache: bool = Query(False, description="Force fresh cookie generation"),
        handler: CFhandler = Depends(get_handler),
    ):
        \
        if _validate_request(request, url, proxy):
            return await mirror_request(request, "html", get_mirror())

        try:
            start_time = time.time()
            logger.info(f"Getting HTML content for {url} (retries: {retries}, proxy: {'yes' if proxy else 'no'})")

            async with _get_target_lock("html", url, proxy):
                data = await handler.get_or_generate_html(url, proxy, Handle_cache=HandleCookieCache)

            if not data:
                raise HTTPException(status_code=500, detail="Failed to Handle Cloudflare protection")

            generation_time = int((time.time() - start_time) * 1000)
            cf_cookies = [name for name in data["cookies"].keys() if name.startswith(CF_COOKIE_PREFIXES)]
            content_length = len(data["html"])

            logger.info(f"Successfully generated HTML content ({content_length} chars) and {len(data['cookies'])} cookies in {generation_time}ms")
            logger.info(f"Cloudflare cookies: {cf_cookies}")

            return Response(
                content=data["html"],
                media_type="text/html",
                headers={
                    "x-cf-handler-cookies": str(len(data["cookies"])),
                    "x-cf-handler-user-agent": data["user_agent"],
                    "x-cf-handler-final-url": data["url"],
                    "x-processing-time-ms": str(generation_time)
                }
            )

        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error getting HTML content for {url}: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/fetch", responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}})
    async def fetch(
        request: Request,
        url: Optional[str] = Query(None, description="URL of the resource (PDF, image, JSON...) to download"),
        proxy: Optional[str] = Query(None, description="Proxy URL (optional)"),
        HandleCookieCache: bool = Query(False, description="Force fresh cookie generation"),
        handler: CFhandler = Depends(get_handler),
    ):
        \
        if _validate_request(request, url, proxy):
            return await mirror_request(request, "fetch", get_mirror())

        try:
            start_time = time.time()
            data = await handler.fetch_bytes(url, proxy, Handle_cache=HandleCookieCache)
            if not data:
                raise HTTPException(status_code=500, detail="Failed to Handle Cloudflare protection")

            generation_time = int((time.time() - start_time) * 1000)
            logger.info(f"Fetched {len(data['body'])} bytes ({data['content_type']}) from {url} in {generation_time}ms")
            return Response(
                content=data["body"],
                status_code=data["status_code"],
                media_type=data["content_type"],
                headers={
                    "x-cf-handler-cookies": str(len(data["cookies"])),
                    "x-cf-handler-user-agent": data["user_agent"],
                    "x-cf-handler-final-url": data["url"],
                    "x-processing-time-ms": str(generation_time),
                },
            )
        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error fetching {url}: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.post("/cache/clear", response_model=CacheClearResponse, responses={500: {"model": ErrorResponse}})
    async def clear_cache(
        handler: CFhandler = Depends(get_handler),
        mirror: RequestMirror = Depends(get_mirror),
    ):
        \
        try:
            cleared_entries = 0

            if handler:
                cache = handler.cookie_cache.cache
                cleared_entries = len(cache)
                handler.cookie_cache.clear_all()
                logger.info(f"Cleared {cleared_entries} cache entries")

            if mirror:
                await mirror.cleanup()
                logger.info("Cleaned up mirror sessions")

            return CacheClearResponse(
                status="success",
                message=f"Cache cleared successfully - {cleared_entries} entries removed"
            )
        except Exception as e:
            logger.error(f"Error clearing cache: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/cache/stats", response_model=CacheStatsResponse, responses={500: {"model": ErrorResponse}})
    async def cache_stats(handler: CFhandler = Depends(get_handler)):
        \
        try:
            if not handler:
                return CacheStatsResponse(
                    cached_entries=0,
                    total_hostnames=0,
                    hostnames=[]
                )

            cache = handler.cookie_cache.cache
            active_entries = sum(1 for cached in cache.values() if not cached.is_expired())
            expired_entries = len(cache) - active_entries

            logger.info(f"Cache stats: {active_entries} active, {expired_entries} expired, {len(cache)} total")

            return CacheStatsResponse(
                cached_entries=active_entries,
                total_hostnames=len(cache),
                hostnames=list(cache.keys())
            )
        except Exception as e:
            logger.error(f"Error getting cache stats: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/browsers", response_model=BrowserPoolResponse, responses={500: {"model": ErrorResponse}})
    async def browsers(request: Request, handler: CFhandler = Depends(get_handler)):
        \
        if any(key.lower() == "x-hostname" for key in request.headers.keys()):
            return await mirror_request(request, "browsers", get_mirror())
        return BrowserPoolResponse(**handler.pool.stats())

    @app.post("/turnstile", response_model=TurnstileResponse, responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}})
    async def solve_turnstile_post(
        request: Request,
        turnstile_req: TurnstileRequest,
        handler: CFhandler = Depends(get_handler),
    ):
        \
        if any(key.lower() == "x-hostname" for key in request.headers.keys()):
            return await mirror_request(request, "turnstile", get_mirror())

        if not is_safe_url(turnstile_req.url):
            raise HTTPException(
                status_code=400,
                detail="Invalid or unsafe URL - localhost and private IPs are not allowed"
            )

        try:
            logger.info(f"Solving Turnstile for {turnstile_req.url} (sitekey: {turnstile_req.sitekey})")
            data = await handler.get_turnstile_token(
                url=turnstile_req.url,
                sitekey=turnstile_req.sitekey,
                action=turnstile_req.action,
                cdata=turnstile_req.cdata,
                pagedata=turnstile_req.pagedata,
                proxy=turnstile_req.proxy,
            )
            if not data or not data.get("token"):
                raise HTTPException(status_code=500, detail="Failed to solve Cloudflare Turnstile challenge")

            return TurnstileResponse(
                token=data["token"],
                user_agent=data["user_agent"],
                elapsed_time=data["elapsed_time"],
                sitekey=turnstile_req.sitekey,
                url=turnstile_req.url,
            )
        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error solving Turnstile for {turnstile_req.url}: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/turnstile", response_model=TurnstileResponse, responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}})
    async def solve_turnstile_get(
        request: Request,
        url: Optional[str] = Query(None, description="Target webpage URL where Turnstile is verified"),
        sitekey: Optional[str] = Query(None, description="Cloudflare Turnstile sitekey"),
        action: Optional[str] = Query(None, description="Optional Turnstile action parameter"),
        cdata: Optional[str] = Query(None, description="Optional Turnstile cData parameter"),
        pagedata: Optional[str] = Query(None, description="Optional Turnstile chlPageData parameter"),
        proxy: Optional[str] = Query(None, description="Proxy URL (optional)"),
        handler: CFhandler = Depends(get_handler),
    ):
        \
        if any(key.lower() == "x-hostname" for key in request.headers.keys()):
            return await mirror_request(request, "turnstile", get_mirror())

        if not url:
            raise HTTPException(status_code=400, detail="url parameter is required")
        if not sitekey or not sitekey.strip():
            raise HTTPException(status_code=400, detail="sitekey parameter is required")
        if not is_safe_url(url):
            raise HTTPException(
                status_code=400,
                detail="Invalid or unsafe URL - localhost and private IPs are not allowed"
            )
        if proxy and not proxy.startswith(PROXY_SCHEMES):
            raise HTTPException(
                status_code=400,
                detail="Proxy must start with http://, https://, socks4://, or socks5://"
            )

        try:
            logger.info(f"Solving Turnstile for {url} (sitekey: {sitekey})")
            data = await handler.get_turnstile_token(
                url=url,
                sitekey=sitekey.strip(),
                action=action,
                cdata=cdata,
                pagedata=pagedata,
                proxy=proxy,
            )
            if not data or not data.get("token"):
                raise HTTPException(status_code=500, detail="Failed to solve Cloudflare Turnstile challenge")

            return TurnstileResponse(
                token=data["token"],
                user_agent=data["user_agent"],
                elapsed_time=data["elapsed_time"],
                sitekey=sitekey.strip(),
                url=url,
            )
        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error solving Turnstile for {url}: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")

    @app.get("/search/animasu")
    async def animasu_search_endpoint(
        q: str = Query(..., min_length=2, max_length=120),
        base_url: str = Query(DEFAULT_ANIMASU_BASE_URL),
        handler: CFhandler = Depends(get_handler),
    ):
        if not is_safe_url(base_url):
            raise HTTPException(status_code=400, detail="Invalid or unsafe base_url")
        items = await search_animasu(q, base_url=base_url, cf_handler=handler)
        return {"status": "ok", "source": "animasu", "query": q, "items": items}

    @app.get("/resolve")
    async def resolve_episode(
        title: str = Query(..., min_length=2, max_length=160),
        ep: int = Query(..., ge=1, le=2000),
        quality: str = Query("1080p"),
        samehadaku: str = Query("https://v2.samehadaku.how"),
        oploverz: str = Query("https://oploverz.ch"),
        nontonanimeid: str = Query("https://nontonanimeid.stream"),
        otakudesu: str = Query("https://otakudesu.blog"),
        animasu: str = Query(DEFAULT_ANIMASU_BASE_URL),
        order: Optional[str] = Query(None),
        handler: CFhandler = Depends(get_handler),
    ):
        raw_sources = {
            "samehadaku": samehadaku,
            "oploverz": oploverz,
            "nontonanimeid": nontonanimeid,
            "otakudesu": otakudesu,
            "animasu": animasu,
        }
        source_urls: dict[str, str] = {}
        for sid, raw_url in raw_sources.items():
            if raw_url and is_safe_url(raw_url):
                source_urls[sid] = raw_url
        if not source_urls:
            raise HTTPException(status_code=400, detail="No valid or safe source URLs provided")
        return await resolve_split_plane(
            title=title,
            ep=ep,
            preferred_quality=quality,
            source_urls=source_urls,
            cf_handler=handler,
            source_order=parse_source_order(order),
        )

    @app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"])
    async def mirror_request(
        request: Request,
        path: str = "",
        mirror: RequestMirror = Depends(get_mirror),
    ):
\

        if path.startswith("cache/"):
            raise HTTPException(status_code=404, detail="Not found")

        try:
            start_time = time.time()

            headers = dict(request.headers)

            hostname = None
            proxy = None
            Handle_cache = False

            for key, value in headers.items():
                key_lower = key.lower()
                if key_lower == 'x-hostname':
                    hostname = value
                elif key_lower == 'x-proxy':
                    proxy = value
                elif key_lower == 'x-handle-cache':
                    Handle_cache = value.lower() in ('true', '1', 'yes', 'on')

            if hostname and "://" in hostname:
                parsed = urlparse(hostname)
                corrected = parsed.netloc or parsed.path
                logger.warning(f"x-hostname should be a bare host, not a URL; using '{corrected}' from '{hostname}'")
                hostname = corrected

            if not hostname:
                raise HTTPException(
                    status_code=400,
                    detail="x-hostname header is required for request mirroring"
                )

            if not is_safe_url(f"https://{hostname}"):
                raise HTTPException(
                    status_code=400,
                    detail="Invalid or unsafe hostname - localhost and private IPs are not allowed"
                )

            if proxy and not proxy.startswith(PROXY_SCHEMES):
                raise HTTPException(
                    status_code=400,
                    detail="x-proxy must start with http://, https://, socks4://, or socks5://"
                )

            request_path = f"/{path}" if path else "/"
            logger.info(f"Mirroring {request.method} request to {hostname}{request_path}")
            if proxy:
                logger.info(f"Using proxy: {redact_proxy(proxy)}")
            if Handle_cache:
                logger.info("x-Handle-cache header detected - forcing fresh cookie generation")

            body = await request.body()

            query_string = str(request.query_params)

            status_code, response_headers, response_content = await mirror.mirror_request(
                method=request.method,
                path=request_path,
                query_string=query_string,
                headers=headers,
                hostname=hostname,
                proxy=proxy,
                Handle_cache=Handle_cache,
                body=body
            )

            processing_time = int((time.time() - start_time) * 1000)

            logger.info(f"Request to {hostname} completed with status {status_code} in {processing_time}ms")
            logger.info(f"Response size: {len(response_content)} bytes")

            response = Response(
                content=response_content,
                status_code=status_code,
                headers=response_headers
            )

            response.headers["x-cf-handler-version"] = APP_VERSION
            response.headers["x-processing-time-ms"] = str(processing_time)
            response.headers["x-cache-Handleed"] = str(Handle_cache).lower()

            return response

        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error mirroring request: {e}")
            raise HTTPException(status_code=500, detail="Internal server error")
