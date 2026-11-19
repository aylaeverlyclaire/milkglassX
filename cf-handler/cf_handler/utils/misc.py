from hashlib import md5
from typing import Optional, Union
import asyncio

def md5_hash(text: Union[str, bytes]) -> str:
    if isinstance(text, str):
        text = text.encode('utf-8')
    return md5(text).hexdigest()

def cache_key(hostname: str, proxy: Optional[str] = None) -> str:
    \
    return md5_hash(f"{hostname}\x00{proxy or ''}")

def per_loop(registry: dict, factory):
    \
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = None
    value = registry.get(loop)
    if value is None:
        value = factory()
        registry[loop] = value
    return value
