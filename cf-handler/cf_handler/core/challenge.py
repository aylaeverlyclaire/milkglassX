import base64
import json
import logging
from collections import namedtuple
from time import monotonic, sleep
from typing import Any, Callable, Dict, Optional
from urllib.parse import urlsplit

from DrissionPage._elements.chromium_element import ChromiumElement
from DrissionPage._pages.chromium_frame import ChromiumFrame

from cf_handler.utils.constants import (
    CHALLENGE_POLL_SECONDS,
    CHALLENGE_TIMEOUT_SECONDS,
    CLICK_COOLDOWN_SECONDS,
    DEFAULT_TIMEOUT_MS,
    HTML_SETTLE_MAX_SECONDS,
    HTML_SETTLE_POLL_SECONDS,
    HTML_SETTLE_STABLE_ROUNDS,
)

ChallengeResult = namedtuple("ChallengeResult", ("success", "cf_detected", "status"))

BLOCK_MARKERS = (
    "you have been blocked",
    "sorry, you have been blocked",
    "error 1020",
    "access denied",
)

_NAV_STATUS_JS = "return (performance.getEntriesByType('navigation')[0] || {}).responseStatus || 0"

def _attrs(node: dict) -> dict:
    flat = node.get("attributes", [])
    return dict(zip(flat[::2], flat[1::2]))

def _open_frame(owner: Any, backend_id: int) -> Any:
    \
\
\
\
\
    node = owner._run_cdp("DOM.describeNode", backendNodeId=backend_id)["node"]
    frame_id = node.get("frameId")
    stale = ChromiumFrame._Frames.get(frame_id)
    if stale is not None and getattr(stale, "_type", None) != "ChromiumFrame":
        ChromiumFrame._Frames.pop(frame_id, None)
    try:
        return ChromiumFrame(owner, ChromiumElement(owner, backend_id=backend_id), info={"node": node})
    except BaseException:
        ChromiumFrame._Frames.pop(frame_id, None)
        raise

def forget_frames(tab: Any) -> None:
    \
    for frame_id, frame in list(ChromiumFrame._Frames.items()):
        if getattr(frame, "_tab", None) is tab or getattr(frame, "_type", None) != "ChromiumFrame":
            ChromiumFrame._Frames.pop(frame_id, None)

def find_turnstile_checkbox(owner: Any, _depth: int = 0) -> Optional[ChromiumElement]:
    \
    if _depth > 5:
        return None

    root = owner._run_cdp("DOM.getDocument", depth=-1, pierce=True)["root"]
    stack, iframes = [root], []
    while stack:
        node = stack.pop()
        name = node.get("nodeName", "")
        if name == "INPUT" and _attrs(node).get("type") == "checkbox":
            return ChromiumElement(owner, backend_id=node["backendNodeId"])
        if name == "IFRAME" and not node.get("contentDocument"):
            iframes.append(node["backendNodeId"])

        stack.extend(sr for sr in node.get("shadowRoots", []) if sr.get("shadowRootType") != "user-agent")
        stack.extend(node.get("children", []))
        if node.get("contentDocument"):
            stack.append(node["contentDocument"])
    for backend_id in iframes:
        try:
            frame = _open_frame(owner, backend_id)
            found = find_turnstile_checkbox(frame, _depth + 1)
        except Exception as e:
            logging.debug(f"Skipping iframe {backend_id}: {e}")
            continue
        if found:
            return found
    return None

def click_turnstile(tab: Any, log: Callable[[str], None] = logging.info) -> bool:
    \
    try:
        checkbox = find_turnstile_checkbox(tab)
        if checkbox is None:
            return False
        if checkbox.attr("aria-checked") == "true" or checkbox.attr("disabled") is not None:
            return True
        checkbox.click()
        log("Clicked Turnstile checkbox")
        return True
    except Exception as e:
        log(f"Turnstile click failed: {e}")
        return False

def is_Handleed(tab: Any, log: Callable[[str], None] = logging.info) -> bool:
    \
    try:
        if "just a moment" in (tab.title or "").lower():
            return False
        lowered = tab.html.lower()
        if "please complete the captcha" in lowered:
            return False
        return not any(marker in lowered for marker in BLOCK_MARKERS)
    except Exception as e:
        log(f"Error checking Handle status: {e}")
        return False

def _nav_status(tab: Any, default: int) -> int:
    try:
        return tab.run_js(_NAV_STATUS_JS) or default
    except Exception:
        return default

def solve(tab: Any, url: str, log: Callable[[str], None] = logging.info) -> ChallengeResult:
    \
    cf_detected = False
    status = 200
    try:
        log(f"Navigating to {url}")
        try:

            loaded = tab.get(url, retry=0, timeout=DEFAULT_TIMEOUT_MS / 1000)
            status = _nav_status(tab, status)
        except Exception as nav_err:
            log(f"Navigation failed: {nav_err}")
            return ChallengeResult(False, cf_detected, 0)

        if not loaded or (tab.url or "").startswith("chrome-error://"):
            log(f"Navigation failed (proxy or network error) for {url}")
            return ChallengeResult(False, cf_detected, 0)

        try:
            html_content = tab.html
        except Exception:

            log("Could not read page content -- treating as unconfirmed")
            return ChallengeResult(is_Handleed(tab, log), cf_detected, status)

        if "cloudflare" not in html_content.lower():
            log("No Cloudflare protection detected -- either not protected or already Handleed")
            return ChallengeResult(True, cf_detected, status)

        cf_detected = True
        log("Cloudflare challenge detected. Polling for the checkbox...")
        deadline = monotonic() + CHALLENGE_TIMEOUT_SECONDS
        next_click = 0.0
        while True:
            if is_Handleed(tab, log):
                log("Cloudflare challenge solved successfully!")
                return ChallengeResult(True, cf_detected, _nav_status(tab, status))
            now = monotonic()
            if now >= deadline:
                break

            if now >= next_click and click_turnstile(tab, log):
                next_click = now + CLICK_COOLDOWN_SECONDS
            sleep(CHALLENGE_POLL_SECONDS)

        log("Failed to solve Cloudflare challenge")
        return ChallengeResult(False, cf_detected, status)
    except Exception as e:
        log(f"Error solving Cloudflare challenge: {e}")
        return ChallengeResult(False, cf_detected, status)

def stable_html(tab: Any) -> str:
    \
    try:
        tab.wait.doc_loaded(timeout=DEFAULT_TIMEOUT_MS / 1000)
    except Exception:
        pass

    html = tab.html
    if HTML_SETTLE_STABLE_ROUNDS <= 0 or HTML_SETTLE_POLL_SECONDS <= 0:
        return html

    deadline = monotonic() + HTML_SETTLE_MAX_SECONDS
    stable = 0
    while stable < HTML_SETTLE_STABLE_ROUNDS and monotonic() < deadline:
        sleep(HTML_SETTLE_POLL_SECONDS)
        try:
            current = tab.html
        except Exception:
            break
        stable = stable + 1 if len(current) == len(html) else 0
        html = current
    return html

_FETCH_JS = """
async function(url, maxBytes) {
    const r = await fetch(url, {credentials: 'include', redirect: 'follow'});
    const buf = await r.arrayBuffer();
    if (buf.byteLength > maxBytes) return JSON.stringify({error: 'too large', size: buf.byteLength});
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return JSON.stringify({status: r.status, type: r.headers.get('content-type'), url: r.url, body: btoa(bin)});
}
"""

def origin_of(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme}://{parts.netloc}/"

def fetch_in_page(tab: Any, url: str, max_bytes: int) -> Dict[str, Any]:
    \
    raw = tab.run_js(_FETCH_JS, url, max_bytes, timeout=DEFAULT_TIMEOUT_MS / 1000)
    result = json.loads(raw) if isinstance(raw, str) else raw
    if result.get("error"):
        raise ValueError(f"fetch failed: {result['error']} ({result.get('size')} bytes)")
    return {
        "status_code": result["status"],
        "content_type": result.get("type") or "application/octet-stream",
        "url": result["url"],
        "body": base64.b64decode(result["body"]),
    }

def find_file_url(tab: Any, url: str, log: Callable[[str], None] = logging.info,
                  idle: float = 6, total: float = 25) -> Optional[str]:
    \
\
    tab.listen.start(res_type=("Document",))
    try:
        if not solve(tab, url, log).success:
            return None
        deadline = monotonic() + total
        for packet in tab.listen.steps(timeout=idle):
            if monotonic() > deadline:
                return None
            response = packet.response
            mime = ((response.headers or {}).get("content-type", "") if response else "").lower()
            if response and response.status == 200 and mime and "text/html" not in mime:
                return packet.url
        return None
    finally:
        try:
            tab.listen.stop()
        except Exception:
            pass
