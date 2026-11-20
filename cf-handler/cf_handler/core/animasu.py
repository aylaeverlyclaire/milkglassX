import asyncio
import base64
import html as html_lib
import re
import time
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, quote_plus, urljoin, urlparse

from bs4 import BeautifulSoup

DEFAULT_ANIMASU_BASE_URL = "https://animasu.love"
_SEARCH_CACHE: Dict[str, Tuple[float, List[Dict[str, Any]]]] = {}
_SEARCH_LOCKS: Dict[str, asyncio.Lock] = {}
_SEARCH_CACHE_TTL_SEC = 300.0


def _clean_text(text: Optional[str]) -> str:
    if not text:
        return ""
    return re.sub(r"\s+", " ", text).strip()


def _parse_episode_count(ep_label: str) -> int:
    m = re.search(r"(\d+)", ep_label or "")
    return int(m.group(1)) if m else 0


def _parse_status_code(status_label: str) -> int:
    low = (status_label or "").lower()
    if any(k in low for k in ("tayang", "ongoing", "releasing")):
        return 1
    if any(k in low for k in ("segera", "upcoming", "belum")):
        return 3
    return 2


def _extract_slug(url: str) -> str:
    path = urlparse(url).path.strip("/")
    if not path:
        return ""
    parts = [p for p in path.split("/") if p]
    if len(parts) >= 2 and parts[0] in ("anime", "nonton"):
        return parts[1]
    return parts[-1]


def parse_animasu_search(html: str, base_url: str = DEFAULT_ANIMASU_BASE_URL) -> List[Dict[str, Any]]:
    """Parse halaman hasil pencarian Animasu (`/?s=<query>`)."""
    soup = BeautifulSoup(html, "html.parser")
    root = base_url.rstrip("/") + "/"
    items: List[Dict[str, Any]] = []
    seen_urls: set[str] = set()

    cards = soup.select(".postbody .listupd .bs .bsx > a[href], .listupd .bs .bsx > a[href]")
    for a in cards:
        href = (a.get("href") or "").strip()
        if not href:
            continue
        abs_url = urljoin(root, href)
        if "/anime/" not in urlparse(abs_url).path.lower() or abs_url in seen_urls:
            continue
        seen_urls.add(abs_url)

        tt_el = a.select_one(".tt")
        title = _clean_text(tt_el.get_text(" ", strip=True) if tt_el else (a.get("title") or a.get_text(" ", strip=True)))
        title = re.sub(r"^Nonton\s+Anime\s+", "", title, flags=re.I)
        title = re.sub(r"\s+Sub(?:title)?\s+Indo(?:nesia)?$", "", title, flags=re.I).strip()
        if not title:
            continue

        type_el = a.select_one(".typez")
        ep_el = a.select_one(".epx")
        sb_el = a.select_one(".sb")
        img_el = a.select_one("img")

        anime_type = _clean_text(type_el.get_text(" ", strip=True)) if type_el else "TV"
        ep_label = _clean_text(ep_el.get_text(" ", strip=True)) if ep_el else ""
        status_label = _clean_text(sb_el.get_text(" ", strip=True)) if sb_el else ""
        poster = ""
        if img_el:
            poster = (
                img_el.get("src")
                or img_el.get("data-src")
                or img_el.get("data-lazy-src")
                or ""
            ).strip()
            if poster and not poster.startswith("http"):
                poster = urljoin(root, poster)

        items.append(
            {
                "slug": _extract_slug(abs_url),
                "url": abs_url,
                "title": title,
                "type": anime_type or "TV",
                "episodes_label": ep_label,
                "episodes_total": _parse_episode_count(ep_label),
                "status_label": status_label,
                "status": _parse_status_code(status_label),
                "poster_url": poster,
                "source": "animasu",
            }
        )
    return items


def parse_animasu_detail(html: str, page_url: str) -> Dict[str, Any]:
    """Parse halaman detail anime Animasu (`/anime/<slug>/`)."""
    soup = BeautifulSoup(html, "html.parser")
    infox = soup.select_one(".infox")
    h1 = infox.select_one("h1") if infox else soup.select_one("h1")
    title = _clean_text(h1.get_text(" ", strip=True) if h1 else "")
    title = re.sub(r"\s+Sub(?:title)?\s+Indo(?:nesia)?$", "", title, flags=re.I).strip()

    alt_el = infox.select_one(".alter") if infox else None
    title_alt = _clean_text(alt_el.get_text(" ", strip=True) if alt_el else "")

    sinopsis_el = soup.select_one(".sinopsis")
    synopsis = _clean_text(sinopsis_el.get_text(" ", strip=True) if sinopsis_el else "")

    thumb_el = soup.select_one(".thumb img")
    poster_url = (thumb_el.get("src") or thumb_el.get("data-src") or "").strip() if thumb_el else ""

    rating_el = soup.select_one(".rating")
    rating = 0.0
    if rating_el:
        rm = re.search(r"(\d+(?:\.\d+)?)", rating_el.get_text(" ", strip=True))
        if rm:
            try:
                rating = float(rm.group(1))
            except ValueError:
                rating = 0.0

    meta: Dict[str, str] = {}
    genres: List[str] = []
    if infox:
        for sp in infox.select(".spe span"):
            txt = _clean_text(sp.get_text(" ", strip=True))
            if ":" in txt:
                k, v = txt.split(":", 1)
                key = k.strip().lower()
                val = v.strip()
                if key == "genre":
                    genres = [_clean_text(g.get_text()) for g in sp.select("a") if _clean_text(g.get_text())]
                    if not genres:
                        genres = [x.strip() for x in val.split(",") if x.strip()]
                elif key != "karakter":
                    meta[key] = val

    episodes: List[Dict[str, Any]] = []
    seen_ep_urls: set[str] = set()
    for li in soup.select("#daftarepisode li"):
        anchors = li.select("a[href]")
        if not anchors:
            continue
        a = anchors[0]
        href = urljoin(page_url, (a.get("href") or "").strip())
        if not href.startswith("http") or href in seen_ep_urls:
            continue
        seen_ep_urls.add(href)
        label = _clean_text(a.get_text(" ", strip=True))
        ep_num: Optional[int] = None
        m = re.search(r"(?:episode|eps?|ep)[-_:\s]*0*(\d{1,4})(?!\d)", f"{label} {href}", re.I)
        if m:
            ep_num = int(m.group(1))
        episodes.append({"episode": ep_num, "label": label, "url": href})

    downloads: List[Dict[str, Any]] = []
    for box in soup.select(".soraddlx"):
        for row in box.select(".soraurlx"):
            q_el = row.select_one("strong")
            quality = _clean_text(q_el.get_text(" ", strip=True)) if q_el else "720p"
            for a in row.select("a[href]"):
                href = (a.get("href") or "").strip()
                if href.startswith("http"):
                    downloads.append(
                        {
                            "quality": quality,
                            "hoster": _clean_text(a.get_text(" ", strip=True)) or (urlparse(href).hostname or "Hoster"),
                            "url": href,
                        }
                    )

    return {
        "slug": _extract_slug(page_url),
        "url": page_url,
        "title": title,
        "title_alt": title_alt,
        "synopsis": synopsis,
        "poster_url": poster_url,
        "rating": rating,
        "genres": genres,
        "status": _parse_status_code(meta.get("status", "")),
        "status_label": meta.get("status", ""),
        "studio": meta.get("studio", "-"),
        "duration": meta.get("durasi", "24 min"),
        "release_season": meta.get("musim", "-"),
        "type": meta.get("jenis", "TV"),
        "episodes_total": _parse_episode_count(meta.get("episode", "")) or len(episodes),
        "episodes": episodes,
        "downloads": downloads,
    }


def _detect_mirror_quality(label: str, url: str) -> str:
    hay = f"{label} {url}".lower()
    if "1080" in hay or "fhd" in hay or "fullhd" in hay:
        return "1080p"
    if "720" in hay or "hd" in hay:
        return "720p"
    if "480" in hay:
        return "480p"
    if "360" in hay:
        return "360p"
    return "720p"


def _decode_b64_safe(val: str) -> str:
    cleaned = val.strip()
    if not cleaned:
        return ""
    pad = (-len(cleaned)) % 4
    try:
        return base64.b64decode(cleaned + ("=" * pad)).decode("utf-8", errors="ignore")
    except Exception:
        return ""


def _extract_iframe_src(fragment: str) -> Optional[str]:
    if not fragment:
        return None
    m = re.search(r"""<iframe\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", fragment, re.I)
    if not m:
        return None
    return html_lib.unescape(m.group(1)).strip()


def _unwrap_berkasdrive(url: str) -> Optional[str]:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if "berkasdrive." not in host:
        return None
    qs = parse_qs(parsed.query)
    raw_id = (qs.get("id") or [""])[0]
    if not raw_id:
        return None
    decoded = _decode_b64_safe(raw_id).strip()
    if decoded.startswith("http://") or decoded.startswith("https://"):
        return decoded
    return None


def parse_animasu_episode_mirrors(html: str, ep_url: str) -> List[Dict[str, Any]]:
    """Decode semua mirror Base64 dari `select.mirror option` dan iframe default `#tonton`."""
    soup = BeautifulSoup(html, "html.parser")
    mirrors: List[Dict[str, Any]] = []
    seen_urls: set[str] = set()

    for opt in soup.select("select.mirror option"):
        val = (opt.get("value") or "").strip()
        label = _clean_text(opt.get_text(" ", strip=True))
        if not val:
            continue
        decoded_html = _decode_b64_safe(val)
        iframe_src = _extract_iframe_src(decoded_html)
        if not iframe_src or not iframe_src.startswith("http"):
            continue
        abs_src = urljoin(ep_url, iframe_src)
        if abs_src in seen_urls:
            continue
        seen_urls.add(abs_src)

        host = (urlparse(abs_src).hostname or "").lower()
        quality = _detect_mirror_quality(label, abs_src)
        mirrors.append(
            {
                "label": label or host,
                "quality": quality,
                "hoster": host or label or "Embed",
                "raw_url": abs_src,
                "unwrapped_url": _unwrap_berkasdrive(abs_src),
                "referer": ep_url,
            }
        )

    for ifr in soup.select("#tonton iframe[src], .video-content iframe[src]"):
        src = html_lib.unescape((ifr.get("src") or "").strip())
        if not src.startswith("http") or src in seen_urls:
            continue
        seen_urls.add(src)
        host = (urlparse(src).hostname or "").lower()
        mirrors.append(
            {
                "label": "Default",
                "quality": _detect_mirror_quality(src, src),
                "hoster": host or "Embed",
                "raw_url": src,
                "unwrapped_url": _unwrap_berkasdrive(src),
                "referer": ep_url,
            }
        )

    return mirrors


async def search_animasu(
    query: str,
    base_url: str = DEFAULT_ANIMASU_BASE_URL,
    cf_handler: Any = None,
) -> List[Dict[str, Any]]:
    clean_q = query.strip()
    if len(clean_q) < 2:
        return []
    root = (base_url or DEFAULT_ANIMASU_BASE_URL).strip().rstrip("/")
    cache_key = f"{root}::{clean_q.lower()}"
    now = time.time()
    cached = _SEARCH_CACHE.get(cache_key)
    if cached and (now - cached[0]) <= _SEARCH_CACHE_TTL_SEC:
        return cached[1]

    lock = _SEARCH_LOCKS.setdefault(cache_key, asyncio.Lock())
    async with lock:
        cached = _SEARCH_CACHE.get(cache_key)
        if cached and (time.time() - cached[0]) <= _SEARCH_CACHE_TTL_SEC:
            return cached[1]

        search_url = f"{root}/?s={quote_plus(clean_q)}"
        html_text: Optional[str] = None
        from curl_cffi.requests import AsyncSession
        async with AsyncSession(impersonate="chrome124") as session:
            try:
                resp = await session.get(
                    search_url,
                    headers={"Accept": "text/html,application/xhtml+xml,*/*", "Referer": f"{root}/"},
                    timeout=12,
                    allow_redirects=True,
                )
                if (
                    resp.status_code == 200
                    and len(resp.text) > 200
                    and "cf-browser-verification" not in resp.text.lower()
                ):
                    html_text = resp.text
            except Exception:
                html_text = None

        if not html_text and cf_handler is not None:
            try:
                data = await cf_handler.get_or_generate_html(search_url, None, Handle_cache=False)
                if data and isinstance(data.get("html"), str) and len(data["html"]) > 200:
                    html_text = data["html"]
            except Exception:
                html_text = None

        results = parse_animasu_search(html_text, root) if html_text else []
        if len(_SEARCH_CACHE) > 256:
            _SEARCH_CACHE.clear()
        if results:
            _SEARCH_CACHE[cache_key] = (time.time(), results)
        return results
