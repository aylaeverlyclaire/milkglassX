export const ALLOWED_CATALOG_PROVIDERS = ['samehadaku', 'otakudesu'];

export const ENRICH_FIELDS = `id idMal title{romaji english native} synonyms averageScore episodes genres coverImage{extraLarge large}`;

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
    .replace(/\+\s*(?:ova|oad|special)\b/gi, '')
    .replace(/\b(?:Subtitle Indonesia|Sub Indo|On-?Going|BD|Batch)\b/gi, '')
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

const ROMAN_SEASON_MAP = { ii: 2, iii: 3, iv: 4, v: 5 };

function extractSeasonTag(rawTitle, norm) {
  const m =
    norm.match(/\bseason\s*(\d+)\b/) ||
    norm.match(/\bs(\d+)\b/) ||
    norm.match(/\b(\d+)(?:st|nd|rd|th)\s+season\b/);
  if (m) return Number.parseInt(m[1], 10);
  const roman = String(rawTitle || '').match(/\b(II|III|IV|V)\b/);
  if (roman) return ROMAN_SEASON_MAP[roman[1].toLowerCase()] ?? 1;
  return 1;
}

function extractPartTag(norm) {
  const m = norm.match(/\b(?:part|cour)\s*(\d+)\b/);
  return m ? Number.parseInt(m[1], 10) : 0;
}

function extractFormatKind(norm) {
  if (/\b(?:movie|gekijouban|film)\b/.test(norm)) return 'MOVIE';
  if (/\b(?:ova|oad)\b/.test(norm)) return 'OVA';
  if (/\b(?:side\s*story|gaiden|spin\s*off|nikki|diaries|coleus|bullet)\b/.test(norm)) return 'SIDE_STORY';
  if (/\b(?:special|recap)\b/.test(norm)) return 'SPECIAL';
  return 'TV';
}

function stripSeasonAndPart(norm) {
  return norm
    .replace(/\bseason\s*\d+\b/g, ' ')
    .replace(/\bs\d+\b/g, ' ')
    .replace(/\b\d+(?:st|nd|rd|th)\s+season\b/g, ' ')
    .replace(/\b(?:ii|iii|iv|v)\b/g, ' ')
    .replace(/\b(?:part|cour)\s*\d+\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Kunci kanonik anti-duplikat lintas Otakudesu & Samehadaku:
 * "Date A Live II", "Date A Live Season 2", dan "Date A Live 2nd Season" menghasilkan kunci identik,
 * sementara season, part/cour, atau side-story berbeda tetap terpisah.
 */
export function canonicalCatalogKey(raw) {
  const cleaned = cleanCatalogTitle(raw);
  const norm = normalizeCatalogTitle(cleaned);
  if (!norm) return '';
  const baseCompact = stripSeasonAndPart(norm).replace(/\s+/g, '');
  if (baseCompact.length < 2) return '';
  return `${baseCompact}|s${extractSeasonTag(cleaned, norm)}|p${extractPartTag(norm)}|${extractFormatKind(norm)}`;
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
    const rawText = String(m[2] || '').replace(/<[^>]+>/g, ' ');
    const isOngoing = /\bon-?going\b/i.test(rawText);
    const title = cleanCatalogTitle(rawText);
    if (url.startsWith('https://') && title.length >= 2 && !seen.has(url)) {
      seen.add(url);
      out.push({ title, url, provider: 'otakudesu', status: isOngoing ? 1 : 2 });
    }
  }
  return out;
}

const DAY_NAME_TO_NUM = {
  senin: 1,
  selasa: 2,
  rabu: 3,
  kamis: 4,
  jumat: 5,
  sabtu: 6,
  minggu: 7,
};

/**
 * Mem-parsing halaman /jadwal-rilis/ Otakudesu menjadi peta normalizedKey -> schedule_day (1=Senin..7=Minggu).
 */
export function parseOtakudesuSchedule(html) {
  const map = new Map();
  const text = String(html || '');
  const sectionRe = /<h2[^>]*>\s*(Senin|Selasa|Rabu|Kamis|Jumat|Sabtu|Minggu)\s*<\/h2>([\s\S]*?)(?=<h2[^>]*>|$)/gi;
  let sec;
  while ((sec = sectionRe.exec(text)) !== null) {
    const dayNum = DAY_NAME_TO_NUM[sec[1].toLowerCase()];
    if (!dayNum) continue;
    const linkRe = /<a[^>]+href="https?:\/\/[^"]+\/anime\/[^"]+"[^>]*>([\s\S]*?)<\/a>/gi;
    let lm;
    while ((lm = linkRe.exec(sec[2])) !== null) {
      const key = canonicalCatalogKey(lm[1]);
      if (key.length >= 2 && !map.has(key)) {
        map.set(key, dayNum);
      }
    }
  }
  return map;
}

/**
 * Menggabungkan entri katalog HANYA dari Samehadaku dan Otakudesu.
 * Provider di luar Samehadaku dan Otakudesu selalu ditolak.
 */
export function mergeProviderCatalogs(providerLists, scheduleMap = null) {
  const allowed = new Set(ALLOWED_CATALOG_PROVIDERS);
  const byKey = new Map();
  for (const list of providerLists || []) {
    for (const raw of list || []) {
      if (!raw || !allowed.has(raw.provider)) continue;
      const url = typeof raw.url === 'string' ? raw.url.trim() : '';
      if (!url.startsWith('https://')) continue;
      const title = cleanCatalogTitle(raw.title);
      const key = canonicalCatalogKey(title);
      if (!key) continue;
      const schedDay = scheduleMap instanceof Map ? (scheduleMap.get(key) ?? 0) : 0;
      const incomingStatus = raw.status === 1 || raw.status === 2 ? raw.status : (schedDay > 0 ? 1 : undefined);
      const existing = byKey.get(key);
      if (existing) {
        if (!existing.source_urls[raw.provider]) {
          existing.source_urls[raw.provider] = url;
        }
        if (incomingStatus === 1) existing.status = 1;
        else if (existing.status === undefined && incomingStatus !== undefined) existing.status = incomingStatus;
        if (schedDay > 0 && !existing.schedule_day) existing.schedule_day = schedDay;
      } else {
        const entry = {
          title: title.slice(0, 180),
          source_urls: { [raw.provider]: url },
        };
        if (incomingStatus !== undefined) entry.status = incomingStatus;
        if (schedDay > 0) entry.schedule_day = schedDay;
        byKey.set(key, entry);
      }
    }
  }
  return Array.from(byKey.values());
}

export function isStrictIdentityMatch(sourceTitle, candidateTitle) {
  const cleanA = cleanCatalogTitle(sourceTitle);
  const cleanB = cleanCatalogTitle(candidateTitle);
  const a = normalizeCatalogTitle(cleanA);
  const b = normalizeCatalogTitle(cleanB);
  if (!a || !b) return false;
  if (extractFormatKind(a) !== extractFormatKind(b)) return false;
  if (extractSeasonTag(cleanA, a) !== extractSeasonTag(cleanB, b)) return false;
  if (extractPartTag(a) !== extractPartTag(b)) return false;
  const baseA = stripSeasonAndPart(a);
  const baseB = stripSeasonAndPart(b);
  if (!baseA || !baseB) return false;
  if (baseA === baseB) return true;
  const tokA = new Set(baseA.split(' ').filter((t) => t.length >= 2));
  const tokB = new Set(baseB.split(' ').filter((t) => t.length >= 2));
  if (tokA.size === 0 || tokB.size === 0) return false;
  let overlap = 0;
  for (const t of tokA) if (tokB.has(t)) overlap++;
  const jaccard = overlap / (tokA.size + tokB.size - overlap);
  const minSize = Math.min(tokA.size, tokB.size);
  const containment = minSize >= 2 ? overlap / minSize : 0;
  return jaccard >= 0.72 || (containment >= 1.0 && minSize >= 2);
}

/**
 * AniList HANYA menyediakan total eps, rating (score), poster, dan id/idMal untuk judul eps.
 * Tidak pernah mengubah title sumber atau menambahkan entri katalog baru.
 */
export function applyAniListEnrichment(item, anilistMedia) {
  if (!anilistMedia || !Number.isInteger(anilistMedia.id) || anilistMedia.id <= 0) return item;
  const out = { ...item };
  if (!Number.isInteger(out.anime_id) || out.anime_id <= 0) {
    out.anime_id = anilistMedia.id;
  }
  if (Number.isInteger(anilistMedia.idMal) && anilistMedia.idMal > 0) {
    out.mal_id = anilistMedia.idMal;
  }
  const cleanMain = String(anilistMedia.title?.romaji || anilistMedia.title?.english || '').trim();
  const cleanAlt = String(anilistMedia.title?.english || anilistMedia.title?.native || '').trim();
  if (cleanMain) out.title = cleanMain.slice(0, 180);
  if (cleanAlt) out.title_alt = cleanAlt.slice(0, 180);
  if (Number.isInteger(anilistMedia.episodes) && anilistMedia.episodes > 0) {
    out.episodes_total = anilistMedia.episodes;
  }
  if (typeof anilistMedia.averageScore === 'number' && anilistMedia.averageScore > 0) {
    out.score = Number((anilistMedia.averageScore / 10).toFixed(2));
  }
  if (Array.isArray(anilistMedia.synonyms)) {
    const cleanSyns = anilistMedia.synonyms.filter((x) => typeof x === 'string' && x.trim().length > 1).slice(0, 12);
    if (cleanSyns.length > 0) out.synonyms = cleanSyns;
  }
  if (Array.isArray(anilistMedia.genres)) {
    const cleanGenres = anilistMedia.genres.filter((g) => typeof g === 'string' && g.trim().length > 0).slice(0, 20);
    if (cleanGenres.length > 0) out.genres = cleanGenres;
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
