#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const BLACKLISTED_HOSTER_PATTERNS = [
  'safelink', 'ouo.io', 'ouo.press', 'shrinkme', 'exe.io', 'bc.vc',
  'adf.ly', 'bit.ly', 'tinyurl.com', 't.me', 'telegram', 'discord',
  'facebook.com', 'twitter.com', 'instagram.com', 'youtube.com', 'youtu.be',
  'saweria', 'trakteer', 'paypal', 'javascript:', 'mailto:', '#',
];

export function normalizeTitle(raw) {
  return String(raw || '')
    .toLowerCase()
    .replace(/\b(subtitle indonesia|sub indo|batch|bd|ova|movie|special)\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

export function titleTokens(raw) {
  return new Set(
    normalizeTitle(raw)
      .split(' ')
      .filter((t) => t.length >= 2)
  );
}

export function scoreTitleMatch(catalogTitle, candidateTitle) {
  const normA = normalizeTitle(catalogTitle);
  const normB = normalizeTitle(candidateTitle);
  if (!normA || !normB) return 0;
  if (normA === normB) return 1.0;

  const tokA = titleTokens(normA);
  const tokB = titleTokens(normB);
  if (tokA.size === 0 || tokB.size === 0) return 0;

  let inter = 0;
  for (const t of tokA) {
    if (tokB.has(t)) inter++;
  }
  if (inter === 0) return 0;

  const jaccard = inter / (tokA.size + tokB.size - inter);
  const containment = inter / Math.min(tokA.size, tokB.size);
  const seasonA = normA.match(/\b(?:season|s)\s*(\d+)\b/)?.[1] ?? null;
  const seasonB = normB.match(/\b(?:season|s)\s*(\d+)\b/)?.[1] ?? null;
  if (seasonA && seasonB && seasonA !== seasonB) {
    return jaccard * 0.35;
  }
  return Math.max(jaccard, containment * 0.88);
}

export function parseQuality(text) {
  const s = String(text || '').toLowerCase();
  if (s.includes('1080')) return '1080p';
  if (s.includes('720')) return '720p';
  if (s.includes('480')) return '480p';
  if (s.includes('360')) return '360p';
  return null;
}

export function parseContainer(blockTitle, qualityText) {
  const combined = `${blockTitle || ''} ${qualityText || ''}`.toLowerCase();
  if (combined.includes('mkv')) return 'mkv';
  return 'mp4';
}

export function isBlacklistedUrl(label, url) {
  const low = `${label || ''} ${url || ''}`.toLowerCase();
  if (!url || (!url.startsWith('https://') && !url.startsWith('http://'))) return true;
  return BLACKLISTED_HOSTER_PATTERNS.some((p) => low.includes(p));
}

export function extractEpisodeNumber(text, url) {
  const combined = `${text || ''} ${url || ''}`;
  const m =
    combined.match(/episode[\s\-_]*(\d{1,4})/i) ||
    combined.match(/\beps?[\s\-_]*(\d{1,4})\b/i) ||
    combined.match(/-(\d{1,4})(?:[-/]|$)/);
  if (!m) return null;
  const n = Number.parseInt(m[1], 10);
  return Number.isFinite(n) && n >= 1 && n <= 2000 ? n : null;
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

export function parseOtakudesuEpisodes(html) {
  const map = new Map();
  const re = /<a[^>]+href="(https?:\/\/[^"]+\/episode\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = m[1].trim();
    const text = m[2].replace(/<[^>]+>/g, '').trim();
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

  // Match Otakudesu <div class="download"> ... <li><strong>Mp4 720p</strong><a href="...">Pdrain</a>...</li>
  // and Oploverz <div class="soraurlx"><strong>720p</strong><a href="...">...</a></div>
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
      const href = linkMatch[1].trim().replace(/&amp;/g, '&');
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

  // Keep balanced multi-hoster coverage across qualities (max 5 per quality, max 16 total)
  const byQuality = new Map();
  for (const item of results) {
    const arr = byQuality.get(item.quality) || [];
    if (arr.length < 5) {
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

export function selectEpisodesToIndex(episodes, maxEps) {
  if (episodes.length <= maxEps) return episodes;
  const picked = [];
  const seen = new Set();
  // Always include Episode 1 (first impression / new viewers) + latest episodes
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
  const headers = { 'User-Agent': USER_AGENT, Accept: 'text/html,application/json,*/*' };
  if (referer) headers.Referer = referer;
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs), redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.text();
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

  const maxAnimes = Math.max(1, Number.parseInt(process.env.MAX_ANIMES || '250', 10) || 250);
  const maxEps = Math.max(1, Number.parseInt(process.env.MAX_EPS_PER_ANIME || '4', 10) || 4);
  const concurrency = Math.max(1, Math.min(12, Number.parseInt(process.env.CONCURRENCY || '6', 10) || 6));

  console.log(`Fetching catalog list from ${workerUrl} (maxAnimes=${maxAnimes}, maxEps=${maxEps})...`);
  const [catJson, srcJson] = await Promise.all([
    fetchJson(`${workerUrl}/v1/internal/catalog-list`, { 'X-Admin-Secret': adminSecret }),
    fetchJson(`${workerUrl}/v1/catalog/sources`).catch(() => ({ sources: [] })),
  ]);

  const catalog = (catJson.data || []).slice(0, maxAnimes);
  const sourcesMap = new Map((srcJson.sources || []).map((s) => [s.id, s.base_url]));
  const otakuBase = (sourcesMap.get('otakudesu') || 'https://otakudesu.blog').replace(/\/+$/, '');
  console.log(`Catalog entries to process: ${catalog.length} | Otakudesu base: ${otakuBase}`);

  const otakuHtml = await fetchText(`${otakuBase}/anime-list/`, `${otakuBase}/`, 25000);
  const otakuList = parseOtakudesuAnimeList(otakuHtml);
  console.log(`Indexed ${otakuList.length} anime from Otakudesu anime-list`);

  // Match catalog anime to Otakudesu entries
  const matched = [];
  for (const anime of catalog) {
    let best = null;
    let bestScore = 0;
    const titles = [anime.title, anime.title_alt].filter(Boolean);
    for (const cand of otakuList) {
      for (const t of titles) {
        const sc = scoreTitleMatch(t, cand.title);
        if (sc > bestScore) {
          bestScore = sc;
          best = cand;
        }
      }
    }
    if (best && bestScore >= 0.45) {
      matched.push({ anime, source: best, score: bestScore });
    }
  }
  console.log(`Matched ${matched.length}/${catalog.length} catalog anime to Otakudesu`);

  let totalHosters = 0;
  let totalEpisodes = 0;
  let syncedAnimes = 0;
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

  await runPool(matched, concurrency, async ({ anime, source }) => {
    const animeHtml = await fetchText(source.url, `${otakuBase}/anime-list/`, 15000);
    const eps = selectEpisodesToIndex(parseOtakudesuEpisodes(animeHtml), maxEps);
    if (eps.length === 0) return;

    const epPayloads = [];
    for (const epItem of eps) {
      try {
        const epHtml = await fetchText(epItem.url, source.url, 15000);
        const hosters = parseEpisodeHostersFromHtml(epHtml, epItem.url, source.provider);
        if (hosters.length > 0) {
          epPayloads.push({ ep: epItem.ep, hosters });
          totalHosters += hosters.length;
          totalEpisodes++;
        }
      } catch {}
    }

    if (epPayloads.length > 0) {
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

  await flushBatch(true);
  console.log(
    `DONE: syncedAnimes=${syncedAnimes}, totalEpisodes=${totalEpisodes}, totalHosters=${totalHosters}`
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error('index-hosters failed:', err);
    process.exit(1);
  });
}
