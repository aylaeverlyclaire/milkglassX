export const ALLOWED_CATALOG_PROVIDERS = ['samehadaku', 'otakudesu'];

export const ENRICH_FIELDS = `id idMal title{romaji english native} averageScore episodes coverImage{extraLarge large}`;

export const ENRICH_SEARCH_QUERY = `query($search:String){Page(page:1,perPage:5){media(search:$search,type:ANIME,isAdult:false){${ENRICH_FIELDS}}}}`;

export const SYNC_CHUNK = 150;

export function minUnique(scope) {
  return scope === 'full' ? 50 : 20;
}

export function cleanCatalogTitle(raw) {
  return String(raw || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&#8211;|&ndash;/gi, '-')
    .replace(/&#8217;|&#039;/gi, "'")
    .replace(/\(Episode[^)]*\)/gi, '')
    .replace(/\b(?:Subtitle Indonesia|Sub Indo|BD|Batch)\b/gi, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function normalizeCatalogTitle(raw) {
  return cleanCatalogTitle(raw)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

export function normalizeCatalogKey(raw) {
  return normalizeCatalogTitle(raw).replace(/\s+/g, '');
}

export function parseSamehadakuCatalog(html) {
  const out = [];
  const seen = new Set();
  const re = /<a[^>]+href="(https?:\/\/[^"]+\/anime\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(String(html || ''))) !== null) {
    const url = m[1].trim();
    const title = cleanCatalogTitle(m[2]);
    if (url.startsWith('https://') && title.length >= 2 && !seen.has(url)) {
      seen.add(url);
      out.push({ title, url, provider: 'samehadaku' });
    }
  }
  return out;
}

export function parseOtakudesuCatalog(html) {
  const out = [];
  const seen = new Set();
  const re = /<a[^>]+class="[^"]*hodebgst[^"]*"[^>]+href="(https?:\/\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(String(html || ''))) !== null) {
    const url = m[1].trim();
    const title = cleanCatalogTitle(m[2]);
    if (url.startsWith('https://') && title.length >= 2 && !seen.has(url)) {
      seen.add(url);
      out.push({ title, url, provider: 'otakudesu' });
    }
  }
  return out;
}

/**
 * Menggabungkan entri katalog HANYA dari Samehadaku dan Otakudesu.
 * Provider lain (AniList, Animasu, Oploverz, NontonAnimeID, Kusonime) selalu ditolak.
 */
export function mergeProviderCatalogs(providerLists) {
  const allowed = new Set(ALLOWED_CATALOG_PROVIDERS);
  const byKey = new Map();
  for (const list of providerLists || []) {
    for (const raw of list || []) {
      if (!raw || !allowed.has(raw.provider)) continue;
      const url = typeof raw.url === 'string' ? raw.url.trim() : '';
      if (!url.startsWith('https://')) continue;
      const title = cleanCatalogTitle(raw.title);
      const key = normalizeCatalogKey(title);
      if (key.length < 2) continue;
      const existing = byKey.get(key);
      if (existing) {
        if (!existing.source_urls[raw.provider]) {
          existing.source_urls[raw.provider] = url;
        }
      } else {
        byKey.set(key, {
          title: title.slice(0, 180),
          source_urls: { [raw.provider]: url },
        });
      }
    }
  }
  return Array.from(byKey.values());
}

function extractSeasonTag(norm) {
  const m =
    norm.match(/\bseason\s*(\d+)\b/) ||
    norm.match(/\bs(\d+)\b/) ||
    norm.match(/\b(\d+)(?:nd|rd|th)\s+season\b/);
  return m ? Number.parseInt(m[1], 10) : 1;
}

function extractPartTag(norm) {
  const m = norm.match(/\b(?:part|cour)\s*(\d+)\b/);
  return m ? Number.parseInt(m[1], 10) : 0;
}

export function isStrictIdentityMatch(sourceTitle, candidateTitle) {
  const a = normalizeCatalogTitle(sourceTitle);
  const b = normalizeCatalogTitle(candidateTitle);
  if (!a || !b) return false;
  if (extractSeasonTag(a) !== extractSeasonTag(b)) return false;
  if (extractPartTag(a) !== extractPartTag(b)) return false;
  const isMovieA = /\bmovie\b|\bgekijouban\b/.test(a);
  const isMovieB = /\bmovie\b|\bgekijouban\b/.test(b);
  if (isMovieA !== isMovieB) return false;
  if (a === b) return true;
  const tokA = new Set(a.split(' ').filter((t) => t.length >= 2));
  const tokB = new Set(b.split(' ').filter((t) => t.length >= 2));
  if (tokA.size === 0 || tokB.size === 0) return false;
  let overlap = 0;
  for (const t of tokA) if (tokB.has(t)) overlap++;
  const jaccard = overlap / (tokA.size + tokB.size - overlap);
  return jaccard >= 0.72;
}

/**
 * AniList HANYA menyediakan total eps, rating (score), poster, dan id/idMal untuk judul eps.
 * Tidak pernah mengubah title sumber atau menambahkan entri katalog baru.
 */
export function applyAniListEnrichment(item, anilistMedia) {
  if (!anilistMedia || !Number.isInteger(anilistMedia.id) || anilistMedia.id <= 0) return item;
  const out = { ...item };
  out.anime_id = anilistMedia.id;
  if (Number.isInteger(anilistMedia.idMal) && anilistMedia.idMal > 0) {
    out.mal_id = anilistMedia.idMal;
  }
  if (Number.isInteger(anilistMedia.episodes) && anilistMedia.episodes > 0) {
    out.episodes_total = anilistMedia.episodes;
  }
  if (typeof anilistMedia.averageScore === 'number' && anilistMedia.averageScore > 0) {
    out.score = Number((anilistMedia.averageScore / 10).toFixed(2));
  }
  const poster = anilistMedia.coverImage?.extraLarge || anilistMedia.coverImage?.large || '';
  if (poster.startsWith('https://')) {
    out.poster_url = poster;
  }
  return out;
}

export function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

export const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

export function retryAfterMs(header) {
  const n = Number(header);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.min(65, Math.max(5, n)) * 1000;
}

export async function fetchWithRetry(url, init, { attempts = 4, baseDelayMs = 2000, fetchImpl = fetch, sleepImpl = sleep } = {}) {
  let last = null;
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await fetchImpl(url, init);
      if (r.ok) return { ok: true, response: r, attempts: i };
      last = { status: r.status, body: (await r.text()).slice(0, 160).replace(/\s+/g, ' ') };
      if (r.status === 429) {
        if (i < attempts) await sleepImpl(retryAfterMs(r.headers.get('retry-after')) ?? baseDelayMs * i);
        continue;
      }
      if (r.status < 500) break;
      if (i < attempts) await sleepImpl(baseDelayMs * i);
    } catch (e) {
      last = { status: 0, body: String(e?.message ?? e).slice(0, 160) };
      if (i < attempts) await sleepImpl(baseDelayMs * i);
    }
  }
  return { ok: false, last };
}
