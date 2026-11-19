import ipaddress
import logging
import socket
from typing import Optional
from urllib.parse import parse_qsl, urlencode, urlparse

def _ip_is_blocked(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    \
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return (
        ip.is_loopback
        or ip.is_private
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )

def _parse_ip_literal(host: str) -> Optional[ipaddress.IPv4Address | ipaddress.IPv6Address]:
    \
    h = host.strip("[]")
    try:
        return ipaddress.ip_address(h)
    except ValueError:
        pass

    try:
        return ipaddress.ip_address(int(h, 0))
    except (ValueError, OverflowError):
        pass
    try:
        packed = socket.inet_aton(h)
        return ipaddress.ip_address(packed)
    except OSError:
        return None

def is_safe_url(url: str) -> bool:
    \
    try:
        parsed_url = urlparse(url)
        if parsed_url.scheme == "file":
            return False
        hostname = parsed_url.hostname
        if not hostname:
            return False

        ip_literal = _parse_ip_literal(hostname)
        if ip_literal is not None:
            return not _ip_is_blocked(ip_literal)

        infos = socket.getaddrinfo(hostname, None)
        if not infos:
            return False
        for info in infos:
            addr = info[4][0]
            try:
                ip = ipaddress.ip_address(addr)
            except ValueError:
                return False
            if _ip_is_blocked(ip):
                return False
        return True
    except Exception:
        return False

def redact_proxy(proxy: Optional[str]) -> Optional[str]:
    \
    if not proxy or "@" not in proxy:
        return proxy
    scheme, sep, rest = proxy.partition("://")
    if not sep:
        scheme, rest = "", proxy
    userinfo, _, hostport = rest.rpartition("@")
    if ":" in userinfo:
        userinfo = userinfo.split(":", 1)[0] + ":***"
    return f"{scheme}{sep}{userinfo}@{hostport}"

def redact_query(path: str) -> str:
    \
    base, sep, query = path.partition("?")
    if not sep or "proxy" not in query.lower():
        return path
    pairs = []
    for name, value in parse_qsl(query, keep_blank_values=True):
        if name.lower() == "proxy":
            value = redact_proxy(value)
        pairs.append((name, value))
    return f"{base}?{urlencode(pairs)}"

class AccessLogRedactor(logging.Filter):
\

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple) and len(record.args) >= 3 and isinstance(record.args[2], str):
            args = list(record.args)
            args[2] = redact_query(args[2])
            record.args = tuple(args)
        return True
