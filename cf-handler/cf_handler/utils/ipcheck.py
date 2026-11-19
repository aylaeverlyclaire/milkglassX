import logging
from typing import Optional

from curl_cffi.requests import AsyncSession

from cf_handler.utils.constants import IP_CHECK_URL, IP_CHECK_TIMEOUT_SECONDS

async def get_exit_ip(proxy: Optional[str] = None) -> Optional[str]:
    \
    proxies = {"http": proxy, "https": proxy} if proxy else None
    try:
        async with AsyncSession() as session:
            resp = await session.get(IP_CHECK_URL, proxies=proxies, timeout=IP_CHECK_TIMEOUT_SECONDS)
            return resp.text.strip() or None
    except Exception as e:
        logging.warning(f"Exit IP check failed: {e}")
        return None
