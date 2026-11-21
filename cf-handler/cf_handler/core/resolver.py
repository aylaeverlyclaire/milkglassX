import asyncio
from html.parser import HTMLParser
import re
import time
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import quote_plus, urljoin, urlparse

from curl_cffi.requests import AsyncSession
from cf_handler.core.animasu import (
    parse_animasu_detail,
    parse_animasu_episode_mirrors,
    parse_animasu_search,
)

IP_AGNOSTIC_HOSTS = (
    "pixeldrain.com",
    "pixeldrain.net",
    "krakenfiles.com",
    "buzzheavier.com",
    "filedon.co",
    "mediafire.com",
    "qiwi.gg",
    "drive.google.com",
    "drive.usercontent.google.com",
)

IP_LOCKED_HOSTS = (
    "gofile.io",
    "acefile.co",
    "desustream.",
    "ondesu.",
    "desudrive.",
    "odfiles.",
    "mp4upload.",
    "filemoon.",
    "vidhide.",
    "streamwish.",
    "dood.",
    "doodstream.",
    "streamtape.",
    "blogger.com",
    "yourupload.com",
    "vidhidepre.com",
    "berkasdrive.com",
    "mitedrive.com",
    "short.ink",
)

BLACKLIST_HOSTS = (
    "facebook.com",
    "twitter.com",
    "x.com",
    "instagram.com",
    "t.me",
    "discord.gg",
    "wa.me",
    "youtube.com",
    "youtu.be",
    "saweria.co",
    "trakteer.id",
)

DEFAULT_SOURCE_ORDER: Tuple[str, ...] = (
    "samehadaku",
    "otakudesu",
    "nontonanimeid",
    "oploverz",
    "animasu",
)

_RESOLVE_CACHE: Dict[str, Tuple[float, Dict[str, Any]]] = {}
_RESOLVE_LOCKS: Dict[str, asyncio.Lock] = {}
_CACHE_TTL_SEC = 900.0


def parse_source_order(raw: Optional[str]) -> List[str]:
    if not raw or not raw.strip():
        return list(DEFAULT_SOURCE_ORDER)
    valid = set(DEFAULT_SOURCE_ORDER)
    ordered: List[str] = []
    for token in raw.split(","):
        sid = token.strip().lower()
        if sid in valid and sid not in ordered:
            ordered.append(sid)
    for fallback in DEFAULT_SOURCE_ORDER:
        if fallback not in ordered:
            ordered.append(fallback)
    return ordered


class _AnchorIframeParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.anchors: List[Tuple[str, str, str]] = []
        self.iframes: List[str] = []
        self._in_a = False
        self._cur_href = ""
        self._cur_title = ""
        self._cur_text: List[str] = []
        self._ctx_text: List[str] = []

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        t = tag.lower()
        attr_map = {k.lower(): (v or "") for k, v in attrs}
        if t == "a":
            self._in_a = True
            self._cur_href = attr_map.get("href", "").strip()
            self._cur_title = attr_map.get("title", "").strip()
            self._cur_text = []
        elif t == "iframe":
            src = (attr_map.get("src") or attr_map.get("data-src") or "").strip()
            if src:
                self.iframes.append(src)
        elif t in ("li", "tr", "div", "p", "strong", "h2", "h3", "h4"):
            self._ctx_text = []

    def handle_data(self, data: str) -> None:
        clean = data.strip()
        if not clean:
            return
        if self._in_a:
            self._cur_text.append(clean)
        else:
            if len(self._ctx_text) < 8:
                self._ctx_text.append(clean)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "a" and self._in_a:
            text = " ".join(self._cur_text).strip() or self._cur_title
            ctx = " ".join(self._ctx_text).strip()
            if self._cur_href:
                self.anchors.append((self._cur_href, text, ctx))
            self._in_a = False
            self._cur_href = ""
            self._cur_title = ""
            self._cur_text = []


def _extract_season(raw: str) -> Optional[int]:
    low = raw.lower()
    for pat in (
        r"\bseason\s*(\d{1,2})\b",
        r"\bs(\d{1,2})\b",
        r"\b(\d{1,2})(?:nd|rd|th)\s+season\b",
        r"\bpart\s*(\d{1,2})\b",
    ):
        m = re.search(pat, low)
        if m:
            return int(m.group(1))
    return None


def _tokenize_title(raw: str) -> List[str]:
    cleaned = re.sub(r"[\[\]\(\)\{\}]+", " ", raw.lower())
    cleaned = re.sub(r"\b(?:sub\s*indo|subtitle\s*indonesia|batch|bd|ova|ona|episode|eps?)\b", " ", cleaned)
    cleaned = re.sub(r"[^a-z0-9\s]+", " ", cleaned)
    return [t for t in cleaned.split() if len(t) >= 2 or t.isdigit()]


def score_title(query: str, candidate: str) -> float:
    q_toks = set(_tokenize_title(query))
    c_toks = set(_tokenize_title(candidate))
    if not q_toks or not c_toks:
        return 0.0
    inter = len(q_toks & c_toks)
    union = len(q_toks | c_toks)
    base = inter / union if union else 0.0
    qs = _extract_season(query)
    cs = _extract_season(candidate)
    if qs is not None and cs is not None:
        base += 0.25 if qs == cs else -0.35
    elif qs is not None and qs > 1 and cs is None:
        base -= 0.25
    elif qs is None and cs is not None and cs > 1:
        base -= 0.25
    return max(0.0, min(1.0, base))


def _matches_episode(text: str, href: str, ep: int) -> bool:
    for hay in (text.lower(), href.lower()):
        m = re.search(r"(?:episode|eps?|ep)[-_:\s]*0*(\d{1,4})(?!\d)", hay)
        if m and int(m.group(1)) == ep:
            return True
    return False


def _detect_quality(text: str, href: str) -> str:
    hay = f"{text} {href}".lower()
    if "1080" in hay or "fullhd" in hay or "fhd" in hay:
        return "1080p"
    if "720" in hay or "hd" in hay:
        return "720p"
    if "480" in hay:
        return "480p"
    if "360" in hay:
        return "360p"
    return "720p"


def _detect_container(text: str, href: str) -> str:
    hay = f"{text} {href}".lower()
    if ".m3u8" in hay or "hls" in hay:
        return "HLS"
    if "mp4" in hay:
        return "MP4"
    if "mkv" in hay:
        return "MKV"
    return "UNKNOWN"


def _classify_host(url: str) -> Tuple[str, str]:
    host = (urlparse(url).hostname or "").lower()
    if not host or any(b in host for b in BLACKLIST_HOSTS):
        return ("skip", host)
    low = url.lower()
    if any(low.endswith(ext) or f"{ext}?" in low for ext in (".mkv", ".mp4", ".m3u8")):
        return ("agnostic", host)
    if any(h in host for h in IP_AGNOSTIC_HOSTS):
        return ("agnostic", host)
    if any(h in host for h in IP_LOCKED_HOSTS):
        return ("ip_locked", host)
    return ("skip", host)


async def _fetch_html(
    session: AsyncSession,
    url: str,
    referer: Optional[str],
    cf_handler: Any,
) -> Optional[str]:
    headers = {"Accept": "text/html,application/xhtml+xml,*/*"}
    if referer:
        headers["Referer"] = referer
    try:
        resp = await session.get(url, headers=headers, timeout=12, allow_redirects=True)
        if resp.status_code == 200 and len(resp.text) > 200 and "cf-browser-verification" not in resp.text.lower():
            return resp.text
    except Exception:
        pass
    if cf_handler is not None:
        try:
            data = await cf_handler.get_or_generate_html(url, None, Handle_cache=False)
            if data and isinstance(data.get("html"), str) and len(data["html"]) > 200:
                return data["html"]
        except Exception:
            pass
    return None


async def _extract_ip_agnostic(
    session: AsyncSession,
    raw_url: str,
    referer: str,
) -> Optional[Tuple[str, str, str, bool]]:
    low = raw_url.lower()
    host = (urlparse(raw_url).hostname or "").lower()
    if any(low.endswith(ext) or f"{ext}?" in low for ext in (".mkv", ".mp4", ".m3u8")):
        is_hls = ".m3u8" in low
        return (host, raw_url, "HLS" if is_hls else ("MP4" if ".mp4" in low else "MKV"), is_hls)

    if "pixeldrain." in host:
        m = re.search(r"pixeldrain\.(?:com|net)/(?:u|l)/([A-Za-z0-9_-]+)", raw_url)
        if m:
            fid = m.group(1)
            if "/u/" in low:
                return ("Pixeldrain", f"https://pixeldrain.com/api/file/{fid}?download", "UNKNOWN", False)
            try:
                r = await session.get(f"https://pixeldrain.com/api/list/{fid}", timeout=10)
                if r.status_code == 200:
                    files = r.json().get("files") or []
                    for f in files:
                        name = str(f.get("name", "")).lower()
                        if name.endswith(".mkv") or name.endswith(".mp4"):
                            cid = "MP4" if name.endswith(".mp4") else "MKV"
                            return ("Pixeldrain", f"https://pixeldrain.com/api/file/{f['id']}?download", cid, False)
            except Exception:
                return None

    if "mediafire.com" in host:
        try:
            r = await session.get(raw_url, headers={"Referer": referer}, timeout=10)
            if r.status_code == 200:
                m = re.search(r'href="(https://download\d+\.mediafire\.com/[^"]+)"', r.text)
                if m:
                    u = m.group(1)
                    cid = "MP4" if ".mp4" in u.lower() else "MKV"
                    return ("MediaFire", u, cid, False)
        except Exception:
            return None

    if "qiwi.gg" in host:
        m = re.search(r"qiwi\.gg/file/([A-Za-z0-9_-]+)", raw_url)
        if m:
            return ("Qiwi", f"https://spyderrock.com/{m.group(1)}.mkv", "MKV", False)

    if "filedon.co" in host:
        try:
            r = await session.get(raw_url, headers={"Referer": referer}, timeout=10)
            if r.status_code == 200:
                m = re.search(r'https://[^\s"\'<>]+?(?:r2\.cloudflarestorage\.com|\.mkv|\.mp4)[^\s"\'<>]*', r.text)
                if m:
                    u = m.group(0).replace("\\/", "/")
                    cid = "MP4" if ".mp4" in u.lower() else "MKV"
                    return ("Filedon", u, cid, False)
        except Exception:
            return None

    return None


async def _resolve_animasu(
    session: AsyncSession,
    base_url: str,
    title: str,
    ep: int,
    cf_handler: Any,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    root = base_url.rstrip("/")
    search_url = f"{root}/?s={quote_plus(title)}"
    search_html = await _fetch_html(session, search_url, f"{root}/", cf_handler)
    if not search_html:
        return ([], [])

    items = parse_animasu_search(search_html, root)
    best_series: Optional[str] = None
    best_score = 0.0
    for item in items:
        sc = score_title(title, item.get("title", ""))
        if sc > best_score and sc >= 0.35:
            best_score = sc
            best_series = item["url"]
    if not best_series:
        return ([], [])

    series_html = await _fetch_html(session, best_series, search_url, cf_handler)
    if not series_html:
        return ([], [])

    detail = parse_animasu_detail(series_html, best_series)
    ep_url: Optional[str] = None
    for row in detail.get("episodes", []):
        if row.get("episode") == ep or _matches_episode(row.get("label", ""), row.get("url", ""), ep):
            ep_url = row["url"]
            break
    if not ep_url:
        return ([], [])

    ep_html = await _fetch_html(session, ep_url, best_series, cf_handler)
    if not ep_html:
        return ([], [])

    directs: List[Dict[str, Any]] = []
    ip_locked_list: List[Dict[str, Any]] = []
    seen: set[str] = set()

    for m in parse_animasu_episode_mirrors(ep_html, ep_url):
        raw_url = m.get("raw_url", "")
        if not raw_url or raw_url in seen:
            continue
        host = (urlparse(raw_url).hostname or "").lower()
        if any(b in host for b in BLACKLIST_HOSTS) or "terabox." in host:
            continue
        seen.add(raw_url)
        ip_locked_list.append(
            {
                "source": "animasu",
                "quality": m.get("quality") or "720p",
                "hoster": m.get("hoster") or m.get("label") or host or "Embed",
                "raw_url": raw_url,
                "referer": ep_url,
                "container": "MP4",
                "ip_locked": True,
            }
        )
        unwrapped = m.get("unwrapped_url")
        if unwrapped and unwrapped not in seen:
            seen.add(unwrapped)
            ip_locked_list.append(
                {
                    "source": "animasu",
                    "quality": m.get("quality") or "720p",
                    "hoster": (urlparse(unwrapped).hostname or "MiteDrive"),
                    "raw_url": unwrapped,
                    "referer": raw_url,
                    "container": "MP4",
                    "ip_locked": True,
                }
            )

    return (directs, ip_locked_list)


async def _resolve_from_source(
    session: AsyncSession,
    source_id: str,
    base_url: str,
    title: str,
    ep: int,
    cf_handler: Any,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    if source_id == "animasu":
        return await _resolve_animasu(session, base_url, title, ep, cf_handler)
    root = base_url.rstrip("/")
    search_url = f"{root}/?s={quote_plus(title)}&post_type=anime" if source_id in ("otakudesu", "samehadaku") else f"{root}/?s={quote_plus(title)}"
    search_html = await _fetch_html(session, search_url, f"{root}/", cf_handler)
    if not search_html:
        return ([], [])

    sp = _AnchorIframeParser()
    sp.feed(search_html)
    best_series: Optional[str] = None
    best_score = 0.0
    for href, text, _ in sp.anchors:
        abs_url = urljoin(root + "/", href)
        if not abs_url.startswith("http") or "/episode/" in abs_url.lower():
            continue
        sc = score_title(title, text)
        if sc > best_score and sc >= 0.35:
            best_score = sc
            best_series = abs_url
    if not best_series:
        return ([], [])

    series_html = await _fetch_html(session, best_series, search_url, cf_handler)
    if not series_html:
        return ([], [])
    ep_parser = _AnchorIframeParser()
    ep_parser.feed(series_html)
    ep_url: Optional[str] = None
    for href, text, _ in ep_parser.anchors:
        abs_url = urljoin(best_series, href)
        if abs_url.startswith("http") and _matches_episode(text, abs_url, ep):
            ep_url = abs_url
            break
    if not ep_url:
        return ([], [])

    ep_html = await _fetch_html(session, ep_url, best_series, cf_handler)
    if not ep_html:
        return ([], [])
    cand_parser = _AnchorIframeParser()
    cand_parser.feed(ep_html)

    directs: List[Dict[str, Any]] = []
    ip_locked_list: List[Dict[str, Any]] = []
    seen: set[str] = set()

    agnostic_raw: List[Tuple[str, str, str, str]] = []
    for href, text, ctx in cand_parser.anchors:
        abs_url = urljoin(ep_url, href)
        if not abs_url.startswith("http") or abs_url in seen:
            continue
        kind, host = _classify_host(abs_url)
        if kind == "skip":
            continue
        seen.add(abs_url)
        q = _detect_quality(f"{ctx} {text}", abs_url)
        cont = _detect_container(f"{ctx} {text}", abs_url)
        if kind == "agnostic":
            agnostic_raw.append((abs_url, q, cont, host))
        elif kind == "ip_locked":
            ip_locked_list.append(
                {
                    "source": source_id,
                    "quality": q,
                    "hoster": text or host,
                    "raw_url": abs_url,
                    "referer": ep_url,
                    "container": cont,
                    "ip_locked": True,
                }
            )

    for iframe_src in cand_parser.iframes:
        abs_src = urljoin(ep_url, iframe_src)
        if not abs_src.startswith("http") or abs_src in seen:
            continue
        seen.add(abs_src)
        host = (urlparse(abs_src).hostname or "").lower()
        if any(b in host for b in BLACKLIST_HOSTS):
            continue
        ip_locked_list.append(
            {
                "source": source_id,
                "quality": _detect_quality(abs_src, abs_src),
                "hoster": host or "Embed",
                "raw_url": abs_src,
                "referer": ep_url,
                "container": "MP4",
                "ip_locked": True,
            }
        )

    extract_tasks = [_extract_ip_agnostic(session, u, ep_url) for (u, _, _, _) in agnostic_raw[:8]]
    extracted_results = await asyncio.gather(*extract_tasks, return_exceptions=True)
    for (u, q, cont, _), res in zip(agnostic_raw[:8], extracted_results):
        if isinstance(res, tuple):
            hoster_name, direct_url, det_cont, is_hls = res
            directs.append(
                {
                    "source": source_id,
                    "quality": q,
                    "hoster": hoster_name,
                    "direct_url": direct_url,
                    "referer": ep_url,
                    "container": det_cont if det_cont != "UNKNOWN" else cont,
                    "is_hls": is_hls,
                    "supports_range": not is_hls,
                }
            )

    return (directs, ip_locked_list)


async def resolve_split_plane(
    title: str,
    ep: int,
    preferred_quality: str,
    source_urls: Dict[str, str],
    cf_handler: Any = None,
    source_order: Optional[List[str]] = None,
) -> Dict[str, Any]:
    effective_order = source_order if source_order else list(DEFAULT_SOURCE_ORDER)
    ordered = [
        (sid, source_urls[sid])
        for sid in effective_order
        if sid in source_urls and source_urls[sid].startswith("https://")
    ]
    order_sig = ",".join(sid for sid, _ in ordered)
    cache_key = f"{title.strip().lower()}#{ep}#{preferred_quality.lower()}#{order_sig}"
    now = time.time()
    cached = _RESOLVE_CACHE.get(cache_key)
    if cached and (now - cached[0]) <= _CACHE_TTL_SEC:
        return cached[1]

    lock = _RESOLVE_LOCKS.setdefault(cache_key, asyncio.Lock())
    async with lock:
        cached = _RESOLVE_CACHE.get(cache_key)
        if cached and (time.time() - cached[0]) <= _CACHE_TTL_SEC:
            return cached[1]

        directs: List[Dict[str, Any]] = []
        ip_locked: List[Dict[str, Any]] = []

        async with AsyncSession(impersonate="chrome124") as session:
            for sid, burl in ordered:
                d_list, lock_list = await _resolve_from_source(session, sid, burl, title, ep, cf_handler)
                directs.extend(d_list)
                ip_locked.extend(lock_list)
                if directs or ip_locked:
                    break

        q_rank = {preferred_quality.lower(): 0, "1080p": 1, "720p": 2, "480p": 3, "360p": 4}
        directs.sort(key=lambda x: q_rank.get(str(x.get("quality", "")).lower(), 9))
        ip_locked.sort(key=lambda x: q_rank.get(str(x.get("quality", "")).lower(), 9))

        payload = {
            "mode": "split_plane",
            "title": title,
            "episode": ep,
            "direct_candidates": directs[:8],
            "client_extract_candidates": ip_locked[:8],
        }
        if len(_RESOLVE_CACHE) > 256:
            _RESOLVE_CACHE.clear()
            for k in [k for k, lk in list(_RESOLVE_LOCKS.items()) if k != cache_key and not lk.locked()]:
                _RESOLVE_LOCKS.pop(k, None)
        if directs or ip_locked:
            _RESOLVE_CACHE[cache_key] = (time.time(), payload)
        return payload
