#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const BLACKLISTED_HOSTER_PATTERNS = [
  'safelink', 'ouo.io', 'ouo.press', 'shrinkme', 'exe.io', 'bc.vc',
  'adf.ly', 'bit.ly', 'tinyurl.com', 't.me', 'telegram', 'discord',
  'facebook.com', 'twitter.com', 'instagram.com', 'youtube.com', 'youtu.be',
  'saweria', 'trakteer', 'paypal', 'javascript:', 'mailto:', '#',
  'mirrored.to', 'acefile.co', 'mega.nz', 'mega.co.nz', 'megaup',
  'racaty', '(rar)', '.rar', '(zip)', '.zip', 'terabox',
  'krakenfiles', 'kfiles', 'mediafire', 'qiwi.gg', 'drive.google',
  'kuramadrive', 'fileditch', 'mixdrop', 'streamtape', 'dood',
  'yourupload', 'berkasdrive', 'mitedrive',
  'vidhide', 'ondesuhd', 'moedesu', 'nekocloud', 'otakufiles', 'vikingfile',
];

// Embed streaming langsung (Tahap 1) + 4 hoster cepat (Tahap 2: Pixeldrain, GoFile, Buzzheavier, Filedon/Odfiles)
const KNOWN_HOSTER_DOMAINS = [
  'pixeldrain.com', 'pixeldrain.net', 'pixeldrain.lu', 'pdrain.com',
  'gofile.io', 'buzzheavier.com', 'filedon.co', 'odfiles.com',
  'icefile.web.id', 'desudrive.com', 'desustream.info', 'desustream.com',
  'odstream', 'odcdn', 'otakuwatch', 'ondesu.com', 'blogger.com',
  'streamruby', 'rubyvidhub.com', 'filemoon', 'streamwish', 'mp4upload',
];

const HOST_RELIABILITY_RANK = [
  'pixeldrain', 'pdrain', 'gofile', 'buzzheavier', 'filedon', 'odfiles',
  'odstream', 'odcdn', 'otakuwatch', 'icefile', 'desustream', 'ondesu', 'blogger',
  'streamruby', 'filemoon', 'streamwish', 'mp4upload',
];

export function unwrapShortenerUrl(rawUrl) {
  const s = String(rawUrl || '').trim();
  if (!s) return '';
  try {
    const parsed = new URL(s);
    if (parsed.hostname.toLowerCase().includes('shrinkearn')) {
      const encoded = parsed.searchParams.get('url') || parsed.searchParams.get('u');
      if (encoded) {
        const decoded = Buffer.from(encoded, 'base64').toString('utf8').trim();
        if (decoded.startsWith('http://') || decoded.startsWith('https://')) {
          return decoded;
        }
      }
      return '';
    }
  } catch {}
  return s;
}

export function parseTitleMeta(raw) {
  const cleaned = String(raw || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[\[\](){}]/g, ' ');

  let formatKind = 'TV';
  if (/\b(?:movie|gekijouban|film)\b/i.test(cleaned)) formatKind = 'MOVIE';
  else if (/\b(?:ova|oad)\b/i.test(cleaned)) formatKind = 'OVA';
  else if (/\b(?:side\s*story|gaiden|spin[\s-]*off)\b/i.test(cleaned)) formatKind = 'SIDE_STORY';
  else if (/\b(?:special|recap|sp)\b/i.test(cleaned)) formatKind = 'SPECIAL';
  else if (/\bona\b/i.test(cleaned)) formatKind = 'ONA';

  let season = null;
  const sMatch =
    cleaned.match(/\bseason\s*(\d+)\b/i) ||
    cleaned.match(/\bs(\d+)\b/i) ||
    cleaned.match(/\b(\d+)(?:st|nd|rd|th)\s+season\b/i);
  if (sMatch) {
    season = Number.parseInt(sMatch[1], 10);
  } else {
    const roman = cleaned.match(/\b(II|III|IV|V)\b/);
    if (roman) {
      season = { II: 2, III: 3, IV: 4, V: 5 }[roman[1]] ?? null;
    }
  }

  let part = null;
  const pMatch = cleaned.match(/\b(?:part|cour)\s*(\d+)\b/i);
  if (pMatch) {
    part = Number.parseInt(pMatch[1], 10);
  }

  const base = cleaned
    .toLowerCase()
    .replace(/\b(subtitle indonesia|sub indo|batch|bd)\b/g, ' ')
    .replace(/\b(?:season|part|cour)\s*\d+\b/gi, ' ')
    .replace(/\bs\d+\b/gi, ' ')
    .replace(/\b\d+(?:st|nd|rd|th)\s+season\b/gi, ' ')
    .replace(/\b(?:movie|gekijouban|film|ova|oad|ona|special|recap|side\s*story|gaiden)\b/gi, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');

  const tokens = new Set(base.split(' ').filter((t) => t.length >= 2));
  return { base, formatKind, season, part, tokens };
}

export function normalizeTitle(raw) {
  return parseTitleMeta(raw).base;
}

export function titleTokens(raw) {
  return parseTitleMeta(raw).tokens;
}

export function extractMatchableTitles(anime) {
  const seen = new Set();
  const out = [];
  const push = (val) => {
    if (typeof val !== 'string') return;
    const clean = val.trim();
    if (clean.length < 3 || !/[a-z0-9]/i.test(clean)) return;
    const key = clean.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    out.push(clean);
  };
  push(anime?.title);
  push(anime?.title_alt);
  if (typeof anime?.synonyms_json === 'string' && anime.synonyms_json.trim().startsWith('[')) {
    try {
      const parsed = JSON.parse(anime.synonyms_json);
      if (Array.isArray(parsed)) {
        for (const s of parsed) push(s);
      }
    } catch {}
  } else if (Array.isArray(anime?.synonyms)) {
    for (const s of anime.synonyms) push(s);
  }
  return out;
}

export function scoreTitleMatch(catalogTitle, candidateTitle) {
  const mA = parseTitleMeta(catalogTitle);
  const mB = parseTitleMeta(candidateTitle);
  if (!mA.base || !mB.base) return 0;

  if (mA.formatKind !== mB.formatKind) {
    return 0;
  }

  if (mA.season !== null && mB.season !== null && mA.season !== mB.season) {
    return 0;
  }
  if (mA.season === null && mB.season !== null && mB.season > 1) {
    return 0;
  }
  if (mA.season !== null && mA.season > 1 && mB.season === null) {
    return 0;
  }
  if (mA.part !== null && mB.part !== null && mA.part !== mB.part) {
    return 0;
  }

  if (mA.base === mB.base) return 1.0;
  if (mA.tokens.size === 0 || mB.tokens.size === 0) return 0;

  let inter = 0;
  for (const t of mA.tokens) {
    if (mB.tokens.has(t)) inter++;
  }
  if (inter === 0) return 0;

  const jaccard = inter / (mA.tokens.size + mB.tokens.size - inter);
  const containment = inter / Math.min(mA.tokens.size, mB.tokens.size);
  return Math.max(jaccard, containment * 0.88);
}

export function parseQuality(text) {
  const s = String(text || '').toLowerCase();
  if (s.includes('1080') || s.includes('fullhd') || /\bfhd\b/.test(s)) return '1080p';
  if (s.includes('720') || s.includes('mp4hd')) return '720p';
  if (s.includes('480')) return '480p';
  if (s.includes('360')) return '360p';
  return null;
}

export function parseContainer(blockTitle, qualityText) {
  const combined = `${blockTitle || ''} ${qualityText || ''}`.toLowerCase();
  if (combined.includes('m3u8') || combined.includes('hls')) return 'hls';
  if (combined.includes('mkv')) return 'mkv';
  return 'mp4';
}

export function isBlacklistedUrl(label, url) {
  const low = `${label || ''} ${url || ''}`.toLowerCase();
  const urlLow = String(url || '').toLowerCase();
  if (!url || (!url.startsWith('https://') && !url.startsWith('http://'))) return true;
  if (low.includes('shrinkearn')) return true;
  // Otakudesu membungkus tautan Filedon/Pdrain/GoFile lewat desustream.com/safelink/link/?id=...
  // yang sudah di-unwrap otomatis oleh MkvHosterExtractors.kt di Engine.
  const isOtakudesuDesuSafelink =
    (urlLow.includes('desustream.com/safelink') || urlLow.includes('desudrive.com/safelink')) &&
    !/mega|acefile|kraken|viking|mirrored|racaty|zippy|otakufiles/i.test(String(label || ''));
  return BLACKLISTED_HOSTER_PATTERNS.some((p) => {
    if (p === 'safelink' && isOtakudesuDesuSafelink) return false;
    return low.includes(p);
  });
}

export function isKnownHoster(url) {
  const low = String(url || '').toLowerCase();
  return KNOWN_HOSTER_DOMAINS.some((d) => low.includes(d));
}

function hostReliabilityIndex(label, url) {
  const low = `${label || ''} ${url || ''}`.toLowerCase();
  const idx = HOST_RELIABILITY_RANK.findIndex((k) => low.includes(k));
  return idx >= 0 ? idx : HOST_RELIABILITY_RANK.length;
}

export function extractEpisodeNumber(text, url) {
  const combined = `${text || ''} ${url || ''}`;
  const m =
    combined.match(/episode[\s\-_]*(\d{1,4})/i) ||
    combined.match(/\beps?[\s\-_]*(\d{1,4})\b/i) ||
    combined.match(/-(\d{1,4})(?:[-/]|$)/);
  if (!m) return null;
  const n = Number.parseInt(m[1], 10);
  return Number.isFinite(n) && n >= 0 && n <= 2000 ? n : null;
}

export function parseOtakudesuAnimeList(html) {
  const out = [];
  const re = /<a[^>]+class="[^"]*hodebgst[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = m[1].trim();
    const title = m[2].replace(/<[^>]+>/g, '').trim();
    if (url.startsWith('http') && title) {
      out.push({ title, url, provider: 'otakudesu' });
    }
  }
  return out;
}

export function parseSamehadakuAnimeList(html) {
  const out = [];
  const seen = new Set();
  const re = /<a[^>]+href="(https?:\/\/[^"]+\/anime\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = m[1].trim();
    const title = m[2].replace(/<[^>]+>/g, '').trim();
    if (url.startsWith('http') && title.length >= 2 && !seen.has(url)) {
      seen.add(url);
      out.push({ title, url, provider: 'samehadaku' });
    }
  }
  return out;
}

export function parseOtakudesuEpisodes(html) {
  const map = new Map();
  const nonBatchFallback = [];
  const re = /<a[^>]+href="(https?:\/\/[^"]+\/episode\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = m[1].trim();
    if (/\/batch\//i.test(url)) continue;
    if (!nonBatchFallback.includes(url)) nonBatchFallback.push(url);
    const text = m[2].replace(/<[^>]+>/g, '').trim();
    const ep = extractEpisodeNumber(text, url);
    if (ep && !map.has(ep)) {
      map.set(ep, url);
    }
  }
  // Movie/OVA tunggal Otakudesu (mis. Kimi no Na wa BD) tidak memiliki angka "Episode 1" di teks/URL.
  if (map.size === 0 && nonBatchFallback.length === 1) {
    map.set(1, nonBatchFallback[0]);
  }
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([ep, url]) => ({ ep, url }));
}

export function parseGenericEpisodes(html, baseUrl = '') {
  const map = new Map();
  const re = /<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    let url = m[1].trim();
    if (url.startsWith('/') && baseUrl) url = `${baseUrl}${url}`;
    if (!url.startsWith('http')) continue;
    const text = m[2].replace(/<[^>]+>/g, '').trim();
    if (!/episode|eps/i.test(url) && !/episode|eps/i.test(text)) continue;
    if (/batch|daftar-anime|anime-list|schedule|jadwal/i.test(url)) continue;
    const ep = extractEpisodeNumber(text, url);
    if (ep && !map.has(ep)) {
      map.set(ep, url);
    }
  }
  return Array.from(map.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([ep, url]) => ({ ep, url }));
}

export function parseEpisodeHostersFromHtml(html, pageUrl, provider = 'otakudesu') {
  const results = [];
  const seen = new Set();

  // Prioritas 1: Panen embed streaming langsung (iframe player & mirror select base64)
  const addEmbed = (rawUrl, label, qualityHint) => {
    const href = unwrapShortenerUrl(String(rawUrl || '').trim().replace(/&amp;/g, '&'));
    if (!href || !/^https?:\/\//i.test(href)) return;
    if (/youtube\.com|youtu\.be|facebook\.com|disqus\.com|google\.com\/recaptcha|doubleclick|googlesyndication/i.test(href)) return;
    if (isBlacklistedUrl(label || 'Embed', href)) return;
    const quality = parseQuality(`${qualityHint || ''} ${label || ''} ${href}`) || '720p';
    const dedupKey = `${quality}|${href}`;
    if (seen.has(dedupKey)) return;
    seen.add(dedupKey);
    let hostLabel = label || 'Embed';
    try { hostLabel = `Embed:${new URL(href).hostname.replace(/^www\./, '')}`; } catch {}
    results.push({
      url: href,
      quality,
      hoster: hostLabel.slice(0, 60),
      provider,
      container: 'mp4',
      referer: pageUrl,
      isEmbed: true,
    });
  };

  const iframeRe = /<iframe[^>]+src=["'](https?:\/\/[^"']+)["'][^>]*>/gi;
  let im;
  while ((im = iframeRe.exec(html)) !== null) {
    addEmbed(im[1], 'Embed', '');
  }

  let pageHost = '';
  try { pageHost = new URL(pageUrl).hostname.toLowerCase(); } catch {}
  const optRe = /<option[^>]+value=["']([^"']+)["'][^>]*>([\s\S]*?)<\/option>/gi;
  let om;
  while ((om = optRe.exec(html)) !== null) {
    const val = om[1].trim();
    const label = om[2].replace(/<[^>]+>/g, '').trim();
    if (/^https?:\/\//i.test(val)) {
      // Tolak dropdown navigasi "Pilih Episode Lainnya" (host sama dengan halaman atau link /episode/)
      let optHost = '';
      try { optHost = new URL(val).hostname.toLowerCase(); } catch {}
      if ((pageHost && optHost === pageHost) || /\/(?:episode|anime)\/|subtitle-indonesia/i.test(val)) {
        continue;
      }
      addEmbed(val, label, label);
    } else if (/^[A-Za-z0-9+/=]{16,}$/.test(val)) {
      try {
        const decoded = Buffer.from(val, 'base64').toString('utf8');
        const srcM = decoded.match(/src=["'](https?:\/\/[^"']+)["']/i);
        if (srcM) addEmbed(srcM[1], label, label);
      } catch {}
    }
  }

  const rowRe = /<(?:li|div[^>]*class="[^"]*soraurlx[^"]*")[^>]*>([\s\S]*?)<\/(?:li|div)>/gi;
  let rowMatch;
  while ((rowMatch = rowRe.exec(html)) !== null) {
    const rowHtml = rowMatch[1];
    const strongMatch = rowHtml.match(/<strong[^>]*>([\s\S]*?)<\/strong>/i);
    if (!strongMatch) continue;
    const strongText = strongMatch[1].replace(/<[^>]+>/g, '').trim();
    const quality = parseQuality(strongText);
    if (!quality) continue;
    const container = parseContainer('', strongText);

    const linkRe = /<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
    let linkMatch;
    while ((linkMatch = linkRe.exec(rowHtml)) !== null) {
      const rawHref = linkMatch[1].trim().replace(/&amp;/g, '&');
      const href = unwrapShortenerUrl(rawHref);
      const label = linkMatch[2].replace(/<[^>]+>/g, '').trim() || 'Hoster';
      if (isBlacklistedUrl(label, href)) continue;
      const dedupKey = `${quality}|${href}`;
      if (seen.has(dedupKey)) continue;
      seen.add(dedupKey);
      results.push({
        url: href,
        quality,
        hoster: label.slice(0, 60),
        provider,
        container,
        referer: pageUrl,
      });
    }
  }

  if (!results.some((r) => !r.isEmbed)) {
    const anyLinkRe = /<a[^>]+href="(https?:\/\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = anyLinkRe.exec(html)) !== null) {
      const rawHref = m[1].trim().replace(/&amp;/g, '&');
      const href = unwrapShortenerUrl(rawHref);
      const label = m[2].replace(/<[^>]+>/g, '').trim() || 'Hoster';
      if (isBlacklistedUrl(label, href)) continue;
      if (!isKnownHoster(href)) continue;
      const quality = parseQuality(`${label} ${href}`) || '720p';
      const dedupKey = `${quality}|${href}`;
      if (seen.has(dedupKey)) continue;
      seen.add(dedupKey);
      results.push({
        url: href,
        quality,
        hoster: label.slice(0, 60),
        provider,
        container: parseContainer('', `${label} ${href}`),
        referer: pageUrl,
      });
    }
  }

  results.sort((a, b) => (a.isEmbed ? 0 : 1) - (b.isEmbed ? 0 : 1) || hostReliabilityIndex(a.hoster, a.url) - hostReliabilityIndex(b.hoster, b.url));
  const byQuality = new Map();
  for (const item of results) {
    const arr = byQuality.get(item.quality) || [];
    if (arr.length < 4) {
      arr.push(item);
      byQuality.set(item.quality, arr);
    }
  }
  const balanced = [];
  for (const q of ['1080p', '720p', '480p', '360p']) {
    for (const item of byQuality.get(q) || []) {
      if (balanced.length < 16) balanced.push(item);
    }
  }
  return balanced;
}

const ALLOWED_CATALOG_PROVIDERS = new Set(['samehadaku', 'otakudesu']);

export function buildSourceFirstCatalog(catalog, providerIndices) {
  const allowedIndices = (providerIndices || []).filter((p) => p && ALLOWED_CATALOG_PROVIDERS.has(p.provider));
  providerIndices = allowedIndices;
  const matched = [];
  const claimedUrls = new Set();
  const syncItems = [];

  for (const anime of catalog) {
    const titles = extractMatchableTitles(anime);
    const hits = [];
    const sourceUrls = {};
    for (const pIdx of providerIndices) {
      let best = null;
      let bestScore = 0;
      for (const cand of pIdx.list) {
        for (const t of titles) {
          const sc = scoreTitleMatch(t, cand.title);
          if (sc > bestScore) {
            bestScore = sc;
            best = cand;
          }
        }
      }
      if (best && bestScore >= 0.55) {
        hits.push({ ...best, base: pIdx.base, score: bestScore });
        if (best.url && best.url.startsWith('https://')) {
          sourceUrls[pIdx.provider] = best.url;
          claimedUrls.add(best.url);
        }
      }
    }
    if (hits.length > 0) {
      matched.push({ anime, hits });
      if (Object.keys(sourceUrls).length > 0) {
        syncItems.push({ anime_id: anime.id, title: anime.title, source_urls: sourceUrls });
      }
    }
  }

  // Source-First: sertakan anime yang ada di Samehadaku/Otakudesu
  const sourceOnlyByKey = new Map();
  for (const pIdx of providerIndices) {
    for (const cand of pIdx.list) {
      if (!cand.url || !cand.url.startsWith('https://') || claimedUrls.has(cand.url)) continue;
      const cleanTitle = String(cand.title || '')
        .replace(/\(Episode[^)]*\)/gi, '')
        .replace(/\b(?:Subtitle Indonesia|Sub Indo|BD)\b/gi, '')
        .replace(/\s+/g, ' ')
        .trim();
      const key = normalizeTitle(cleanTitle).replace(/\s+/g, '');
      if (key.length < 3) continue;
      const existing = sourceOnlyByKey.get(key);
      if (existing) {
        if (!existing.source_urls[pIdx.provider]) existing.source_urls[pIdx.provider] = cand.url;
      } else {
        sourceOnlyByKey.set(key, {
          title: cleanTitle.slice(0, 180),
          source_urls: { [pIdx.provider]: cand.url },
        });
      }
    }
  }

  for (const entry of sourceOnlyByKey.values()) {
    syncItems.push(entry);
  }
  return { matched, syncItems };
}

export function selectEpisodesToIndex(episodes, maxEps) {
  if (episodes.length <= maxEps) return episodes;
  const picked = [];
  const seen = new Set();
  const first = episodes[0];
  if (first) {
    picked.push(first);
    seen.add(first.ep);
  }
  const tail = episodes.slice(-(maxEps - picked.length));
  for (const e of tail) {
    if (!seen.has(e.ep)) {
      picked.push(e);
      seen.add(e.ep);
    }
  }
  return picked;
}

async function fetchText(url, referer = '', timeoutMs = 15000) {
  const headers = {
    'User-Agent': USER_AGENT,
    Accept: 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
    'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
  };
  if (referer) headers.Referer = referer;
  try {
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs), redirect: 'follow' });
    if (res.ok) return await res.text();
    if (res.status !== 403 && res.status !== 503) {
      throw new Error(`HTTP ${res.status} for ${url}`);
    }
  } catch (err) {
    if (!process.env.CF_HANDLER_URL) throw err;
  }

  const cfHandler = (process.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
  if (!cfHandler) throw new Error(`Blocked fetching ${url}`);
  const cfHeaders = { 'User-Agent': USER_AGENT, Accept: 'text/html,*/*' };
  const cfToken = (process.env.CF_HANDLER_TOKEN || '').trim();
  if (cfToken) cfHeaders['x-auth-token'] = cfToken;
  const proxyRes = await fetch(`${cfHandler}/fetch?url=${encodeURIComponent(url)}`, {
    headers: cfHeaders,
    signal: AbortSignal.timeout(timeoutMs + 10000),
  });
  if (!proxyRes.ok) throw new Error(`CF_HANDLER HTTP ${proxyRes.status} for ${url}`);
  return proxyRes.text();
}

async function fetchJson(url, headers = {}, timeoutMs = 20000) {
  const res = await fetch(url, {
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json', ...headers },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.json();
}

async function runPool(items, concurrency, fn) {
  const out = [];
  let idx = 0;
  async function worker() {
    while (idx < items.length) {
      const i = idx++;
      try {
        const val = await fn(items[i], i);
        if (val !== undefined && val !== null) out.push(val);
      } catch {}
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, concurrency) }, () => worker()));
  return out;
}

async function main() {
  const workerUrl = (process.env.WORKER_URL || 'https://milkglass-backend.milkyliquid.workers.dev').replace(/\/+$/, '');
  const adminSecret = (process.env.ADMIN_SECRET || '').trim();
  if (!adminSecret) {
    throw new Error('ADMIN_SECRET wajib diisi.');
  }

  const maxAnimes = Math.max(1, Number.parseInt(process.env.MAX_ANIMES || '1500', 10) || 1500);
  const maxEps = Math.max(1, Number.parseInt(process.env.MAX_EPS_PER_ANIME || '4', 10) || 4);
  const concurrency = Math.max(1, Math.min(12, Number.parseInt(process.env.CONCURRENCY || '6', 10) || 6));

  console.log(`Fetching catalog list from ${workerUrl} (maxAnimes=${maxAnimes}, maxEps=${maxEps})...`);
  const [catJson, srcJson] = await Promise.all([
    fetchJson(`${workerUrl}/v1/internal/catalog-list`, { 'X-Admin-Secret': adminSecret }),
    fetchJson(`${workerUrl}/v1/catalog/sources`).catch(() => ({ sources: [] })),
  ]);

  const catalog = (catJson.data || []).slice(0, maxAnimes);
  const sourcesMap = new Map((srcJson.sources || []).map((s) => [s.id, s.base_url]));
  const sameBase = (sourcesMap.get('samehadaku') || 'https://samehadaku.li').replace(/\/+$/, '');
  const otakuBase = (sourcesMap.get('otakudesu') || 'https://otakudesu.blog').replace(/\/+$/, '');

  // Katalog eksklusif Samehadaku & Otakudesu saja.
  const providerIndices = [];
  async function fetchSamehadakuAllPages() {
    const all = [];
    const seenUrls = new Set();
    for (let page = 1; page <= 15; page++) {
      const pageUrl = page === 1 ? `${sameBase}/az-list/` : `${sameBase}/az-list/page/${page}/`;
      let html = '';
      try {
        html = await fetchText(pageUrl, `${sameBase}/`, 20000);
      } catch {
        if (page === 1) {
          html = await fetchText(`${sameBase}/daftar-anime-2/?list`, `${sameBase}/`, 20000);
          return parseSamehadakuAnimeList(html);
        }
        break;
      }
      const items = parseSamehadakuAnimeList(html);
      let added = 0;
      for (const it of items) {
        if (!seenUrls.has(it.url)) {
          seenUrls.add(it.url);
          all.push(it);
          added++;
        }
      }
      if (added === 0 || !new RegExp(`/az-list/page/${page + 1}/`, 'i').test(html)) break;
    }
    return all;
  }

  const [sameRes, otakuRes] = await Promise.allSettled([
    fetchSamehadakuAllPages(),
    fetchText(`${otakuBase}/anime-list/`, `${otakuBase}/`, 20000).then(parseOtakudesuAnimeList),
  ]);

  const orderedProviderResults = [
    ['otakudesu', otakuBase, otakuRes],
    ['samehadaku', sameBase, sameRes],
  ];
  for (const [provider, base, res] of orderedProviderResults) {
    if (res.status === 'fulfilled' && res.value.length > 0) {
      console.log(`[${provider}] indexed ${res.value.length} anime`);
      providerIndices.push({ provider, base, list: res.value });
    } else {
      console.warn(`[${provider}] unavailable: ${res.status === 'rejected' ? res.reason?.message : 'empty'}`);
    }
  }

  if (providerIndices.length === 0) {
    throw new Error('Tidak ada satu pun provider sumber anime yang dapat diakses.');
  }

  const { matched, syncItems } = buildSourceFirstCatalog(catalog, providerIndices);
  console.log(
    `Matched ${matched.length}/${catalog.length} anime + ${syncItems.length - matched.length} source-only anime across ${providerIndices.length} provider(s)`
  );

  // Sinkronkan seluruh source_urls_json ke D1 (Source-First Catalog) agar katalog langsung lengkap & bypass search saat Play
  for (let i = 0; i < syncItems.length; i += 150) {
    const chunk = syncItems.slice(i, i + 150);
    try {
      const res = await fetch(`${workerUrl}/v1/internal/sync-source-catalog`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Admin-Secret': adminSecret,
          'User-Agent': USER_AGENT,
        },
        body: JSON.stringify({ items: chunk }),
        signal: AbortSignal.timeout(30000),
      });
      if (res.ok) {
        const j = await res.json();
        console.log(`[sync-source-catalog] batch ${i / 150 + 1}: updated=${j.updated}, inserted=${j.inserted}`);
      }
    } catch (err) {
      console.warn(`[sync-source-catalog] batch ${i / 150 + 1} error: ${err.message}`);
    }
  }

  let totalHosters = 0;
  let totalEpisodes = 0;
  let syncedAnimes = 0;
  let diagLogs = 0;
  const batchBuffer = [];

  async function flushBatch(forceReconcile = false) {
    if (batchBuffer.length === 0 && !forceReconcile) return;
    const payload = {
      items: batchBuffer.splice(0, batchBuffer.length),
      reconcile: forceReconcile,
    };
    const res = await fetch(`${workerUrl}/v1/internal/sync-hosters`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Secret': adminSecret,
        'User-Agent': USER_AGENT,
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(30000),
    });
    if (!res.ok) {
      const txt = await res.text().catch(() => '');
      throw new Error(`sync-hosters HTTP ${res.status}: ${txt.slice(0, 300)}`);
    }
    const j = await res.json();
    console.log(
      `[sync-hosters] batch flushed: upserted_hosters=${j.upserted_hosters}, touched_animes=${j.touched_animes}, reconciled=${j.reconciled}`
    );
  }

  await runPool(matched, concurrency, async ({ anime, hits }) => {
    const epHostersMap = new Map();

    for (const source of hits) {
      try {
        const animeHtml = await fetchText(source.url, `${source.base}/`, 15000);
        const rawEps =
          source.provider === 'otakudesu'
            ? parseOtakudesuEpisodes(animeHtml)
            : parseGenericEpisodes(animeHtml, source.base);
        const eps = selectEpisodesToIndex(rawEps, maxEps);
        if (eps.length === 0) continue;

        for (const epItem of eps) {
          try {
            const epHtml = await fetchText(epItem.url, source.url, 15000);
            const hosters = parseEpisodeHostersFromHtml(epHtml, epItem.url, source.provider);
            if (hosters.length > 0) {
              const existing = epHostersMap.get(epItem.ep) || [];
              const existingUrls = new Set(existing.map((x) => x.url));
              for (const h of hosters) {
                if (!existingUrls.has(h.url) && existing.length < 20) {
                  existing.push(h);
                  existingUrls.add(h.url);
                }
              }
              epHostersMap.set(epItem.ep, existing);
            }
          } catch (err) {
            if (diagLogs < 6) {
              diagLogs++;
              console.log(`[diag:${source.provider}-ep-err] ${epItem.url}: ${err.message}`);
            }
          }
        }
        if (epHostersMap.size >= 2) break;
      } catch (err) {
        if (diagLogs < 6) {
          diagLogs++;
          console.log(`[diag:${source.provider}-series-err] ${source.url}: ${err.message}`);
        }
      }
    }

    if (epHostersMap.size > 0) {
      const epPayloads = Array.from(epHostersMap.entries())
        .sort((a, b) => a[0] - b[0])
        .map(([ep, hosters]) => {
          totalHosters += hosters.length;
          totalEpisodes++;
          return { ep, hosters };
        });
      syncedAnimes++;
      batchBuffer.push({
        anime_id: anime.id,
        title: anime.title,
        title_alt: anime.title_alt || null,
        episodes: epPayloads,
      });
      if (batchBuffer.length >= 12) {
        await flushBatch(false);
      }
    }
  });

  await flushBatch(syncedAnimes >= 100);
  console.log(
    `DONE: syncedAnimes=${syncedAnimes}, totalEpisodes=${totalEpisodes}, totalHosters=${totalHosters}`
  );
  if (syncedAnimes === 0) {
    throw new Error('0 anime berhasil diindeks dari semua provider.');
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error('index-hosters failed:', err);
    process.exit(1);
  });
}
