import asyncio
import os

from cf_handler.browser.pool import BrowserPool
from cf_handler.core.handler import CFhandler
from cf_handler.core.mirror import RequestMirror

URL = "https://cloudflare.hackmap.win/"
PROXY = os.environ.get("CF_PROXY")

async def main():

    handler = CFhandler(pool=BrowserPool(2))
    await handler.start()
    mirror = RequestMirror(handler)
    try:

        data = await handler.get_or_generate_cookies(URL, proxy=PROXY)
        print("cookies:", data["cookies"])
        print("user-agent:", data["user_agent"])

        page = await handler.get_or_generate_html(URL, proxy=PROXY)
        print("html:", page["status_code"], len(page["html"]), "chars")

        raw = await handler.fetch_bytes(URL, proxy=PROXY)
        print("fetch:", raw["status_code"], raw["content_type"], len(raw["body"]), "bytes")

        status, headers, body = await mirror.mirror_request(
            method="GET", path="/", headers={}, hostname="cloudflare.hackmap.win", proxy=PROXY,
        )
        print("mirror:", status, headers.get("content-type"), len(body), "bytes")

        print("pool:", handler.pool.stats())
    finally:
        await mirror.cleanup()
        await handler.cleanup()

asyncio.run(main())
