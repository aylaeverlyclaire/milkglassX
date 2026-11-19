# run from the repo root: python -m examples.turnstile_example
import asyncio
import os

from cf_handler.browser.pool import BrowserPool
from cf_handler.core.handler import CFhandler

TARGET_URL = "https://example.com/login"
# Cloudflare official dummy test keys:
# '1x00000000000000000000AA' -> visible (always passes)
# '1x00000000000000000000BB' -> invisible (always passes)
# '3x00000000000000000000FF' -> interactive (auto-clicked checkbox)
SITEKEY = os.environ.get("CF_SITEKEY", "1x00000000000000000000AA")
PROXY = os.environ.get("CF_PROXY")  # optional


async def main():
    handler = CFhandler(pool=BrowserPool(1))
    await handler.start()
    try:
        print(f"Solving Turnstile for {TARGET_URL} (sitekey: {SITEKEY})...")
        result = await handler.get_turnstile_token(
            url=TARGET_URL,
            sitekey=SITEKEY,
            action="login",
            proxy=PROXY,
        )
        if result and result.get("token"):
            print("Successfully solved Turnstile!")
            print("Token:", result["token"])
            print("User-Agent:", result["user_agent"])
            print(f"Elapsed: {result['elapsed_time']:.2f}s")
        else:
            print("Failed to solve Turnstile.")
    finally:
        await handler.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
