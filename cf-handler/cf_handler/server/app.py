import hmac
import logging
import os

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from cf_handler.utils.constants import APP_VERSION
from cf_handler.utils.security import AccessLogRedactor
from .routes import setup_routes, lifespan

def create_app() -> FastAPI:
    app = FastAPI(
        title="Cloudflare handler",
        description="DrissionPage-based Cloudflare handler with request mirroring",
        version=APP_VERSION,
        lifespan=lifespan
    )

    auth_token = os.environ.get("CF_AUTH_TOKEN", "")
    if auth_token:
        expected = auth_token.encode()

        @app.middleware("http")
        async def require_auth_token(request: Request, call_next):
            if request.method == "OPTIONS" and "access-control-request-method" in request.headers:
                return Response(status_code=200)
            given = request.headers.get("x-auth-token", "").encode()
            if not hmac.compare_digest(given, expected):
                return JSONResponse(status_code=401, content={"detail": "Missing or invalid x-auth-token header"})
            return await call_next(request)

    allowed_origins = [
        o.strip()
        for o in os.environ.get("CF_CORS_ORIGINS", "").split(",")
        if o.strip()
    ]
    if allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=allowed_origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "OPTIONS"],
            allow_headers=["x-auth-token", "content-type"],
        )

    logging.basicConfig(
        level=logging.INFO,
        format='[%(asctime)s] %(levelname)s - %(name)s - %(message)s',
        datefmt='%H:%M:%S'
    )

    access_log = logging.getLogger("uvicorn.access")
    if not any(isinstance(f, AccessLogRedactor) for f in access_log.filters):
        access_log.addFilter(AccessLogRedactor())

    setup_routes(app)
    return app
