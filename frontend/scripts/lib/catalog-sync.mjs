export const ALLOWED_CATALOG_PROVIDERS = ['ylnime', 'otakudesu'];

export const MAX_ALLOWED_EPISODES = 500;

export const BANNED_FRANCHISE_RE =
  /\b(?:detective[\s\-_]*conan|meitantei[\s\-_]*conan|case[\s\-_]*closed|naruto|boruto|one[\s\-_]*piece|1piece)\b/i;


export function isExcludedCatalogAnime(title, episodesTotal = 0, sourceUrls = null) {
  const eps = Number(episodesTotal ?? 0);
  if (Number.isFinite(eps) && eps > MAX_ALLOWED_EPISODES) return true;
  if (typeof title === 'string' && BANNED_FRANCHISE_RE.test(title)) return true;
  if (typeof sourceUrls === 'string' && BANNED_FRANCHISE_RE.test(sourceUrls)) return true;
  if (sourceUrls && typeof sourceUrls === 'object') {
    for (const v of Object.values(sourceUrls)) {
      if (typeof v === 'string' && BANNED_FRANCHISE_RE.test(v)) return true;
    }
  }
  return false;
}

export function isExcludedCatalogEntry(entry) {
  if (!entry || typeof entry !== 'object') return false;
  const eps = Number(entry.episodes_total ?? entry.episodes ?? 0);
  if (Number.isFinite(eps) && eps > MAX_ALLOWED_EPISODES) return true;
  const fields = [
    entry.title,
    entry.title_alt,
    entry.url,
    ...(entry.source_urls && typeof entry.source_urls === 'object' ? Object.values(entry.source_urls) : []),
  ];
  return fields.some((f) => typeof f === 'string' && BANNED_FRANCHISE_RE.test(f));
}

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
    .replace(/^\s*\((?:HD|Uncen|Uncensored|BD|Sub Indo)\)\s*/i, '')
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

export function canonicalCatalogKey(raw) {
  const cleaned = cleanCatalogTitle(raw);
  const norm = normalizeCatalogTitle(cleaned);
  if (!norm) return '';
  const baseCompact = stripSeasonAndPart(norm).replace(/\s+/g, '');
  if (baseCompact.length < 2) return '';
  return `${baseCompact}|s${extractSeasonTag(cleaned, norm)}|p${extractPartTag(norm)}|${extractFormatKind(norm)}`;
}

export function parseYlnimeCatalog(html, baseUrl = 'https://ylnime.com') {
  const out = [];
  const seen = new Set();
  const rawHtml = String(html || '');
  const root = String(baseUrl || 'https://ylnime.com').replace(/\/+$/, '');
  const re = /<a\b([^>]*?)\bhref=(["'])([^"']+)\2([^>]*)>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(rawHtml)) !== null) {
    const rawHref = m[3].trim().replace(/&amp;/g, '&');
    if (!/[?&]series=[^&#]+/i.test(rawHref) || /[?&]episode=/i.test(rawHref)) continue;
    const url = rawHref.startsWith('http')
      ? rawHref
      : rawHref.startsWith('?')
        ? `${root}/${rawHref}`
        : `${root}/${rawHref.replace(/^\/+/, '')}`;
    const attrs = `${m[1] || ''} ${m[4] || ''}`;
    const innerText = cleanCatalogTitle(m[5]);
    let title = innerText;
    if (title.length < 2) {
      title = cleanCatalogTitle(
        (attrs.match(/\btitle=(["'])([^"']+)\1/i)?.[2]) ||
        (m[5].match(/\balt=(["'])([^"']+)\1/i)?.[2]) ||
        ''
      );
    }
    const beforeSlice = rawHtml.slice(Math.max(0, m.index - 450), m.index);
    const afterSlice = rawHtml.slice(m.index + m[0].length, m.index + m[0].length + 450);
    if (title.length < 2) {
      const cardTitleMatch = afterSlice.match(/<(?:h[1-6]|div|span)[^>]*class=(["'])[^"']*card-title[^"']*\1[^>]*>([\s\S]*?)<\/(?:h[1-6]|div|span)>/i);
      const imgAltMatches = [...beforeSlice.matchAll(/<img\b[^>]*\balt=(["'])([^"']+)\1/gi)];
      const nearestImgAlt = imgAltMatches.length > 0 ? imgAltMatches[imgAltMatches.length - 1][2] : '';
      title = cleanCatalogTitle(cardTitleMatch?.[2] || nearestImgAlt || '');
    }
    const imgSrcMatches = [...beforeSlice.matchAll(/<img\b[^>]*\bsrc=(["'])(https?:\/\/[^"']+)\1/gi)];
    const posterUrl = imgSrcMatches.length > 0 ? imgSrcMatches[imgSrcMatches.length - 1][2] : '';
    const item = { title, url, provider: 'ylnime' };
    if (posterUrl.startsWith('https://')) item.poster_url = posterUrl;
    if (
      url.startsWith('https://') &&
      title.length >= 2 &&
      !/^\[?info\]?/i.test(title) &&
      !/^\(live\s*action\)/i.test(title) &&
      !isExcludedCatalogEntry(item) &&
      !seen.has(url)
    ) {
      seen.add(url);
      out.push(item);
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
    const item = { title, url, provider: 'otakudesu', status: isOngoing ? 1 : 2 };
    if (url.startsWith('https://') && title.length >= 2 && !isExcludedCatalogEntry(item) && !seen.has(url)) {
      seen.add(url);
      out.push(item);
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

export function inferCatalogMediaType(title) {
  const t = String(title || '');
  if (/\b(?:movie|gekijouban|film)\b/i.test(t)) return 'MOVIE';
  if (/\b(?:ova|oad)\b/i.test(t)) return 'OVA';
  if (/\bspecials?\b/i.test(t)) return 'SPECIAL';
  return 'TV';
}

export function parseYlnimeSchedule(html) {
  const map = new Map();
  const text = String(html || '');
  const isGeneric = (v) => /^(?:cover|poster|thumbnail|thumb|image|img|logo|banner|watch|tonton|nonton|detail)$/i.test(String(v || '').trim());
  const sectionRe = /(Senin|Selasa|Rabu|Kamis|Jumat|Sabtu|Minggu)\s*(?:<[^>]+>\s*)*\d{1,2}\s+[A-Za-z]+\s+\d{4}([\s\S]*?)(?=(?:Senin|Selasa|Rabu|Kamis|Jumat|Sabtu|Minggu)\s*(?:<[^>]+>\s*)*\d{1,2}\s+[A-Za-z]+\s+\d{4}|$)/gi;
  let sec;
  while ((sec = sectionRe.exec(text)) !== null) {
    const dayNum = DAY_NAME_TO_NUM[sec[1].toLowerCase()];
    if (!dayNum) continue;
    const block = sec[2];
    const linkRe = /<a\b[^>]*\bhref=(["'])(?:(?:https?:\/\/[^"']+\/|\/)?(?:index\.php)?\?series=[^"'&]+)\1[^>]*>([\s\S]*?)<\/a>/gi;
    let lm;
    while ((lm = linkRe.exec(block)) !== null) {
      let rawTitle = cleanCatalogTitle(lm[2]);
      if (rawTitle.length < 2 || isGeneric(rawTitle)) {
        const beforeSlice = block.slice(Math.max(0, lm.index - 450), lm.index);
        const afterSlice = block.slice(lm.index + lm[0].length, lm.index + lm[0].length + 450);
        const cardTitleMatch = afterSlice.match(/<(?:h[1-6]|div|span)[^>]*class=(["'])[^"']*card-title[^"']*\1[^>]*>([\s\S]*?)<\/(?:h[1-6]|div|span)>/i);
        const imgAlts = [...beforeSlice.matchAll(/<img\b[^>]*\balt=(["'])([^"']+)\1/gi)]
          .map((x) => cleanCatalogTitle(x[2]))
          .filter((t) => t.length >= 2 && !isGeneric(t));
        rawTitle = cleanCatalogTitle(cardTitleMatch?.[2] || (imgAlts.length > 0 ? imgAlts[imgAlts.length - 1] : ''));
      }
      const key = canonicalCatalogKey(rawTitle);
      if (key && !map.has(key)) map.set(key, dayNum);
    }
  }
  return map;
}

export function mergeProviderCatalogs(providerLists, scheduleMap = null) {
  const allowed = new Set(ALLOWED_CATALOG_PROVIDERS);
  const byKey = new Map();
  for (const list of providerLists || []) {
    for (const raw of list || []) {
      if (!raw || !allowed.has(raw.provider)) continue;
      if (isExcludedCatalogEntry(raw)) continue;
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
        if (!existing.poster_url && typeof raw.poster_url === 'string' && raw.poster_url.startsWith('https://')) {
          existing.poster_url = raw.poster_url;
        }
      } else {
        const mediaType = inferCatalogMediaType(title);
        const entry = {
          title: title.slice(0, 180),
          type: mediaType,
          source_urls: { [raw.provider]: url },
        };
        if (mediaType === 'MOVIE') entry.episodes_total = 1;
        if (incomingStatus !== undefined) entry.status = incomingStatus;
        if (schedDay > 0) entry.schedule_day = schedDay;
        if (typeof raw.poster_url === 'string' && raw.poster_url.startsWith('https://')) {
          entry.poster_url = raw.poster_url;
        }
        byKey.set(key, entry);
      }
    }
  }
  return Array.from(byKey.values()).filter((e) => !isExcludedCatalogEntry(e));
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
