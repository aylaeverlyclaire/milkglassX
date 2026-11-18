const ANILIST_GRAPHQL_URL = 'https://graphql.anilist.co';
const MIN_EPISODE_BYTES = 15 * 1024 * 1024;      
const MAX_SINGLE_EP_BYTES = 3_950 * 1024 * 1024; 

export interface AniListRawMedia {
  id: number;
  idMal?: number | null;
  title?: {
    romaji?: string | null;
    english?: string | null;
    native?: string | null;
  } | null;
  description?: string | null;
  averageScore?: number | null;
  format?: string | null;
  status?: string | null;
  duration?: number | null;
  season?: string | null;
  seasonYear?: number | null;
  popularity?: number | null;
  episodes?: number | null;
  genres?: string[] | null;
  coverImage?: {
    extraLarge?: string | null;
    large?: string | null;
  } | null;
  studios?: {
    nodes?: Array<{ name: string }> | null;
  } | null;
  nextAiringEpisode?: {
    airingAt?: number | null;
    episode?: number | null;
  } | null;
}

const MEDIA_FIELDS = `
  id
  idMal
  title {
    romaji
    english
    native
  }
  description(asHtml: false)
  averageScore
  format
  status
  duration
  season
  seasonYear
  popularity
  episodes
  genres
  coverImage {
    extraLarge
    large
  }
  studios(isMain: true) {
    nodes {
      name
    }
  }
  nextAiringEpisode {
    airingAt
    episode
  }
`;

const CATALOG_PAGE_QUERY = `
query ($page: Int, $perPage: Int, $status: MediaStatus) {
  Page(page: $page, perPage: $perPage) {
    pageInfo {
      hasNextPage
    }
    media(type: ANIME, status: $status, sort: [POPULARITY_DESC], isAdult: false) {
      ${MEDIA_FIELDS}
    }
  }
}
`;

const SINGLE_MEDIA_QUERY = `
query ($id: Int) {
  Media(id: $id, type: ANIME, isAdult: false) {
    ${MEDIA_FIELDS}
  }
}
`;

export function mapAniListStatus(status: string | null | undefined): number {
  switch (status) {
    case 'RELEASING':
      return 1;
    case 'FINISHED':
      return 2;
    case 'NOT_YET_RELEASED':
      return 3;
    case 'HIATUS':
      return 4;
    case 'CANCELLED':
      return 5;
    default:
      return 2;
  }
}

export function computeScheduleDayWib(airingAtEpochSec: number | null | undefined): number {
  if (!airingAtEpochSec) return 0;
  const wibDate = new Date((airingAtEpochSec + 7 * 3600) * 1000);
  const utcDay = wibDate.getUTCDay();
  return utcDay === 0 ? 7 : utcDay;
}

const UPSERT_ANIME_SQL = `
  INSERT INTO animes (
    id, mal_id, title, title_alt, synopsis, score, type, status,
    schedule_day, duration, studio, release_season, poster_url,
    genres_json, popularity, updated_at, episodes_total, next_airing_at, next_airing_ep
  ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)
  ON CONFLICT(id) DO UPDATE SET
    mal_id = COALESCE(excluded.mal_id, animes.mal_id),
    title = excluded.title,
    title_alt = excluded.title_alt,
    synopsis = excluded.synopsis,
    score = excluded.score,
    type = excluded.type,
    status = excluded.status,
    schedule_day = CASE WHEN excluded.schedule_day > 0 THEN excluded.schedule_day ELSE animes.schedule_day END,
    duration = excluded.duration,
    studio = excluded.studio,
    release_season = excluded.release_season,
    poster_url = excluded.poster_url,
    genres_json = excluded.genres_json,
    popularity = excluded.popularity,
    updated_at = excluded.updated_at,
    episodes_total = CASE WHEN excluded.episodes_total > 0 THEN excluded.episodes_total ELSE animes.episodes_total END,
    next_airing_at = excluded.next_airing_at,
    next_airing_ep = excluded.next_airing_ep
`;

export function bindAnimeUpsert(
  db: D1Database,
  item: AniListRawMedia,
  nowSec: number
): D1PreparedStatement {
  const cleanSynopsis = (item.description ?? '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .trim();
  const scoreTenScale = item.averageScore ? Number((item.averageScore / 10).toFixed(2)) : 0;
  const statusInt = mapAniListStatus(item.status);
  const scheduleDay = computeScheduleDayWib(item.nextAiringEpisode?.airingAt);
  const durationText = item.duration ? `${item.duration} min` : '24 min';
  const studioName = item.studios?.nodes?.[0]?.name ?? '-';
  const releaseSeason = [item.season, item.seasonYear].filter(Boolean).join(' ') || '-';
  const posterUrl = item.coverImage?.extraLarge || item.coverImage?.large || '';

  return db
    .prepare(UPSERT_ANIME_SQL)
    .bind(
      item.id,
      item.idMal ?? null,
      item.title?.romaji || item.title?.english || 'Untitled',
      item.title?.english || item.title?.native || '',
      cleanSynopsis,
      scoreTenScale,
      item.format || 'TV',
      statusInt,
      scheduleDay,
      durationText,
      studioName,
      releaseSeason,
      posterUrl,
      JSON.stringify(item.genres ?? []),
      item.popularity ?? 0,
      nowSec,
      item.episodes && item.episodes > 0 ? item.episodes : 0,
      item.nextAiringEpisode?.airingAt ?? 0,
      item.nextAiringEpisode?.episode ?? 0
    );
}

const META_LAST_IMPORT_AT = 'last_import_at';
const META_LAST_FULL_IMPORT_AT = 'last_full_import_at';
const META_LAST_IMPORT_COUNT = 'last_import_count';

const UPSERT_META_SQL = `
  INSERT INTO catalog_meta (key, value, updated_at) VALUES (?1, ?2, ?3)
  ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
`;

function metaStatements(
  db: D1Database,
  scope: 'ongoing' | 'full' | undefined,
  count: number,
  nowSec: number
): D1PreparedStatement[] {
  const stmts = [
    db.prepare(UPSERT_META_SQL).bind(META_LAST_IMPORT_AT, String(nowSec), nowSec),
    db.prepare(UPSERT_META_SQL).bind(META_LAST_IMPORT_COUNT, String(count), nowSec),
  ];
  if (scope === 'full') {
    stmts.push(db.prepare(UPSERT_META_SQL).bind(META_LAST_FULL_IMPORT_AT, String(nowSec), nowSec));
  }
  return stmts;
}

export async function importAniListMedia(
  db: D1Database,
  items: AniListRawMedia[],
  scope?: 'ongoing' | 'full'
): Promise<number> {
  const nowSec = Math.floor(Date.now() / 1000);
  const valid = items.filter(
    (m) =>
      m &&
      Number.isInteger(m.id) &&
      m.id > 0 &&
      m.title &&
      typeof m.title.romaji === 'string'
  );
  if (valid.length === 0) return 0;
  await db.batch([
    ...valid.map((m) => bindAnimeUpsert(db, m, nowSec)),
    ...metaStatements(db, scope, valid.length, nowSec),
  ]);
  return valid.length;
}

export interface SyncPageLog {
  page: number;
  status: string;
  http?: number;
  media?: number;
  error?: string;
}

export async function syncAniListCatalogDetailed(
  db: D1Database,
  startPage = 1,
  perPage = 50,
  maxPages = 3
): Promise<{ synced: number; log: SyncPageLog[] }> {
  let totalSynced = 0;
  const log: SyncPageLog[] = [];
  const nowSec = Math.floor(Date.now() / 1000);
  const syncPlans: Array<{ page: number; status: string | undefined }> = [
    { page: 1, status: 'RELEASING' },
    { page: 2, status: 'RELEASING' },
  ];
  for (let p = startPage; p <= Math.max(1, maxPages - 2); p++) {
    syncPlans.push({ page: p, status: undefined });
  }

  for (const plan of syncPlans) {
    const entry: SyncPageLog = { page: plan.page, status: plan.status ?? 'ALL' };
    log.push(entry);
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(ANILIST_GRAPHQL_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'User-Agent': 'MilkglassCatalogSync/2.0',
        },
        body: JSON.stringify({
          query: CATALOG_PAGE_QUERY,
          variables: { page: plan.page, perPage, status: plan.status },
        }),
        signal: controller.signal,
      });
      entry.http = response.status;
      if (!response.ok) {
        entry.error = (await response.text().catch(() => '')).slice(0, 200);
        continue;
      }
      const payload = (await response.json()) as {
        errors?: Array<{ message?: string }>;
        data?: { Page?: { media?: AniListRawMedia[] } };
      };
      if (payload.errors?.length) {
        entry.error = `graphql: ${payload.errors[0]?.message ?? 'unknown'}`.slice(0, 200);
      }
      const mediaList = payload.data?.Page?.media ?? [];
      entry.media = mediaList.length;
      if (mediaList.length === 0) continue;
      const statements = mediaList.map((item) => bindAnimeUpsert(db, item, nowSec));
      await db.batch(statements);
      totalSynced += statements.length;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      entry.error = msg.slice(0, 200);
    } finally {
      clearTimeout(timeoutId);
    }
  }
  return { synced: totalSynced, log };
}

export async function syncAniListCatalog(
  db: D1Database,
  startPage = 1,
  perPage = 50,
  maxPages = 3
): Promise<number> {
  return (await syncAniListCatalogDetailed(db, startPage, perPage, maxPages)).synced;
}

export async function ensureAnimeExistsFastInD1(
  db: D1Database,
  animeId: number,
  malId?: number,
  fallbackTitle = 'Anime'
): Promise<{ malId: number | null; wasCreatedPlaceholder: boolean }> {
  const existing = await db
    .prepare(`SELECT id, mal_id, poster_url FROM animes WHERE id = ?1`)
    .bind(animeId)
    .first<{ id: number; mal_id: number | null; poster_url: string | null }>();
  if (existing) {
    return {
      malId: existing.mal_id ?? malId ?? null,
      wasCreatedPlaceholder: !existing.poster_url,
    };
  }
  const nowSec = Math.floor(Date.now() / 1000);
  await db
    .prepare(
      `INSERT OR IGNORE INTO animes (
         id, mal_id, title, title_alt, synopsis, score, type, status,
         schedule_day, duration, studio, release_season, poster_url,
         genres_json, popularity, updated_at
       ) VALUES (?1, ?2, ?3, '', '', 0, 'TV', 1, 0, '24 min', '-', '-', '', '[]', 0, ?4)`
    )
    .bind(animeId, malId ?? null, fallbackTitle, nowSec)
    .run();
  return { malId: malId ?? null, wasCreatedPlaceholder: true };
}

export async function enrichSingleAnimeFromAniListAsync(
  db: D1Database,
  animeId: number
): Promise<void> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 3500);
  try {
    const response = await fetch(ANILIST_GRAPHQL_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'User-Agent': 'MilkglassCatalogSync/2.0',
      },
      body: JSON.stringify({
        query: SINGLE_MEDIA_QUERY,
        variables: { id: animeId },
      }),
      signal: controller.signal,
    });
    if (response.ok) {
      const payload = (await response.json()) as { data?: { Media?: AniListRawMedia } };
      const media = payload.data?.Media;
      if (media) {
        const nowSec = Math.floor(Date.now() / 1000);
        await bindAnimeUpsert(db, media, nowSec).run();
      }
    }
  } catch {
    
  } finally {
    clearTimeout(timeoutId);
  }
}

export interface OtakudesuStreamNode {
  provider: 'pdrain' | 'kfiles';
  url: string;
  resolution: '1080p' | '720p' | '480p';
  bitrate_rank: number;
  container: 'mkv';
}

const DEFAULT_OTAKUDESU_BASE_URL = 'https://otakudesu.blog';
const FALLBACK_OTAKUDESU_CANDIDATES = [
  'https://otakudesu.or.at',
  'https://otakudesu.best',
  'https://otakudesu.blog',
  'https://otakudesu.cloud',
];

async function saveOtakudesuBaseUrl(db: D1Database, baseUrl: string): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  try {
    await db
      .prepare(
        `INSERT INTO catalog_meta (key, value, updated_at)
         VALUES ('otakudesu_base_url', ?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
      )
      .bind(baseUrl, now)
      .run();
  } catch {
  }
}

export async function getOtakudesuBaseUrl(db: D1Database): Promise<string> {
  try {
    const row = await db
      .prepare(`SELECT value FROM catalog_meta WHERE key = 'otakudesu_base_url'`)
      .first<{ value: string }>();
    const raw = (row?.value || '').trim().replace(/\/+$/, '');
    if (raw.startsWith('https://')) return raw;
  } catch {
  }
  return DEFAULT_OTAKUDESU_BASE_URL;
}

async function fetchHtmlWithRedirectCheck(
  url: string,
  referer?: string
): Promise<{ html: string; finalOrigin: string } | null> {
  try {
    const res = await fetch(url, {
      redirect: 'follow',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36',
        Accept: 'text/html,application/xhtml+xml',
        ...(referer ? { Referer: referer } : {}),
      },
      signal: AbortSignal.timeout(4500),
    });
    if (!res.ok) return null;
    const html = await res.text();
    const finalOrigin = new URL(res.url).origin;
    return { html, finalOrigin };
  } catch {
    return null;
  }
}

async function discoverOtakudesuBaseUrl(db: D1Database, currentBase: string): Promise<string | null> {
  const candidates = new Set<string>();
  const ddg = await fetchHtmlWithRedirectCheck('https://html.duckduckgo.com/html/?q=otakudesu+subtitle+indonesia');
  if (ddg) {
    const matches = ddg.html.match(/https?:\/\/[a-z0-9.-]*otakudesu[a-z0-9.-]*\.[a-z]{2,}/gi) || [];
    for (const m of matches) {
      const normalized = m.replace(/^http:\/\//i, 'https://').replace(/\/+$/, '');
      if (normalized !== currentBase) candidates.add(normalized);
    }
  }
  for (const fb of FALLBACK_OTAKUDESU_CANDIDATES) {
    if (fb !== currentBase) candidates.add(fb);
  }

  for (const cand of Array.from(candidates).slice(0, 4)) {
    const probe = await fetchHtmlWithRedirectCheck(`${cand}/`);
    if (probe && /otakudesu/i.test(probe.html)) {
      await saveOtakudesuBaseUrl(db, probe.finalOrigin);
      return probe.finalOrigin;
    }
  }
  return null;
}

function detectResolutionTag(text: string): '1080p' | '720p' | '480p' | null {
  if (/1080p|fullhd|fhd/i.test(text)) return '1080p';
  if (/720p|hd/i.test(text)) return '720p';
  if (/480p|sd/i.test(text)) return '480p';
  return null;
}

function rankForNode(provider: 'pdrain' | 'kfiles', res: OtakudesuStreamNode['resolution']): number {
  const resScore = res === '1080p' ? 400 : res === '720p' ? 300 : 200;
  const provScore = provider === 'pdrain' ? 20 : 10;
  return resScore + provScore;
}

function extractHosterLinksFromHtml(html: string): OtakudesuStreamNode[] {
  const nodes: OtakudesuStreamNode[] = [];
  const seen = new Set<string>();
  const tokenRegex =
    /(?:\b(mkv|mp4)\s*(1080p|720p|480p|360p|240p)\b|\b(1080p|720p|480p|360p|240p)\s*(mkv|mp4)\b|https?:\/\/(?:pixeldrain\.com\/(?:u|api\/file)\/[A-Za-z0-9_-]+|krakenfiles\.com\/view\/[A-Za-z0-9_-]+(?:\/file\.html)?))/gi;

  let activeFormat: 'mkv' | 'mp4' | null = null;
  let activeRes: '1080p' | '720p' | '480p' | null = null;
  let match: RegExpExecArray | null;

  while ((match = tokenRegex.exec(html)) !== null) {
    if (match[1] && match[2]) {
      activeFormat = match[1].toLowerCase() === 'mkv' ? 'mkv' : 'mp4';
      activeRes = detectResolutionTag(match[2]);
      continue;
    }
    if (match[3] && match[4]) {
      activeFormat = match[4].toLowerCase() === 'mkv' ? 'mkv' : 'mp4';
      activeRes = detectResolutionTag(match[3]);
      continue;
    }

    const url = match[0].replace(/^http:\/\//i, 'https://');
    if (seen.has(url)) continue;

    let fmt = activeFormat;
    let res = activeRes;
    if (!fmt || !res) {
      const ctxStart = Math.max(0, match.index - 400);
      const ctxEnd = Math.min(html.length, match.index + match[0].length + 120);
      const ctx = html.slice(ctxStart, ctxEnd).replace(/<[^>]+>/g, ' ');
      if (/mkv/i.test(ctx)) fmt = 'mkv';
      res = detectResolutionTag(ctx);
    }

    if (fmt !== 'mkv' || !res) continue;
    seen.add(url);

    const provider: 'pdrain' | 'kfiles' = /pixeldrain\.com/i.test(url) ? 'pdrain' : 'kfiles';
    nodes.push({
      provider,
      url,
      resolution: res,
      bitrate_rank: rankForNode(provider, res),
      container: 'mkv',
    });
  }

  return nodes.sort((a, b) => b.bitrate_rank - a.bitrate_rank);
}

function findAnimeUrlInHtml(html: string, activeOrigin: string): string | null {
  const escaped = activeOrigin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = html.match(new RegExp(`href=["'](${escaped}/anime/[^"']+)["']`, 'i'))
    || html.match(/href=["'](https?:\/\/[^"']*otakudesu[^"']*\/anime\/[^"']+)["']/i);
  return match ? match[1] : null;
}

function findEpisodeUrlInHtml(html: string, episode: number): string | null {
  const epUrlMatch = html.match(new RegExp(`href=["']([^"']*episode-${episode}(?:-|/|["'])[^"']*)["']`, 'i'));
  if (epUrlMatch) return epUrlMatch[1];

  const anchorRegex = /<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let m: RegExpExecArray | null;
  while ((m = anchorRegex.exec(html)) !== null) {
    const text = m[2].replace(/<[^>]+>/g, ' ').trim();
    if (new RegExp(`\bepisode\s*${episode}\b`, 'i').test(text)) {
      return m[1];
    }
  }
  return null;
}

export async function sweepOtakudesuStreams(
  db: D1Database,
  titles: string[],
  episode: number
): Promise<OtakudesuStreamNode[]> {
  let baseUrl = await getOtakudesuBaseUrl(db);
  const uniqueQueries = Array.from(new Set(titles.filter(Boolean))).slice(0, 2);

  for (const query of uniqueQueries) {
    const searchUrl = `${baseUrl}/?s=${encodeURIComponent(query)}&post_type=anime`;
    let searchRes = await fetchHtmlWithRedirectCheck(searchUrl, baseUrl);
    if (!searchRes) {
      const discovered = await discoverOtakudesuBaseUrl(db, baseUrl);
      if (!discovered) continue;
      baseUrl = discovered;
      searchRes = await fetchHtmlWithRedirectCheck(`${baseUrl}/?s=${encodeURIComponent(query)}&post_type=anime`, baseUrl);
    }
    if (!searchRes) continue;

    if (searchRes.finalOrigin !== baseUrl) {
      baseUrl = searchRes.finalOrigin;
      await saveOtakudesuBaseUrl(db, baseUrl);
    }

    const animeUrl = findAnimeUrlInHtml(searchRes.html, baseUrl);
    if (!animeUrl) continue;

    const animeRes = await fetchHtmlWithRedirectCheck(animeUrl, searchUrl);
    if (!animeRes) continue;

    const epUrl = findEpisodeUrlInHtml(animeRes.html, episode);
    if (!epUrl) continue;

    const epRes = await fetchHtmlWithRedirectCheck(epUrl, animeUrl);
    if (!epRes) continue;

    const nodes = extractHosterLinksFromHtml(epRes.html);
    if (nodes.length > 0) return nodes;
  }

  return [];
}


function normalizeTitleTokens(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function extractOtakudesuAnimeMatches(html: string, activeOrigin: string): Array<{ title: string; url: string }> {
  const results: Array<{ title: string; url: string }> = [];
  const seen = new Set<string>();
  const anchorRegex = /<a\s+[^>]*href=["']([^"']*\/anime\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let m: RegExpExecArray | null;
  while ((m = anchorRegex.exec(html)) !== null) {
    const url = m[1].trim();
    const rawTitle = m[2].replace(/<[^>]+>/g, ' ').trim();
    if (!url.startsWith(activeOrigin) && !/otakudesu/i.test(url)) continue;
    if (!rawTitle || seen.has(url)) continue;
    seen.add(url);
    results.push({ title: normalizeTitleTokens(rawTitle), url });
  }
  return results;
}

export async function crossCheckOtakudesuCatalog(
  db: D1Database,
  query: string
): Promise<Array<{ title: string; url: string }>> {
  let baseUrl = await getOtakudesuBaseUrl(db);
  let res = await fetchHtmlWithRedirectCheck(`${baseUrl}/?s=${encodeURIComponent(query)}&post_type=anime`, baseUrl);
  if (!res) {
    const discovered = await discoverOtakudesuBaseUrl(db, baseUrl);
    if (!discovered) return [];
    baseUrl = discovered;
    res = await fetchHtmlWithRedirectCheck(`${baseUrl}/?s=${encodeURIComponent(query)}&post_type=anime`, baseUrl);
  }
  if (!res) return [];
  if (res.finalOrigin !== baseUrl) {
    baseUrl = res.finalOrigin;
    await saveOtakudesuBaseUrl(db, baseUrl);
  }
  return extractOtakudesuAnimeMatches(res.html, baseUrl);
}

export function matchAnimeAgainstOtakudesu(
  titles: Array<string | null | undefined>,
  otakudesuMatches: Array<{ title: string; url: string }>
): string | null {
  if (otakudesuMatches.length === 0) return null;
  for (const raw of titles) {
    if (!raw) continue;
    const norm = normalizeTitleTokens(raw);
    if (norm.length < 3) continue;
    const hit = otakudesuMatches.find(
      (m) => m.title.includes(norm) || norm.includes(m.title)
    );
    if (hit) return hit.url;
  }
  return null;
}

export function buildHttpCouponRecipe(node: OtakudesuStreamNode, baseUrl: string) {
  return {
    mode: 'HTTP_RANGE_PROXY',
    provider: node.provider,
    target_url: node.url,
    referer: baseUrl,
    resolution: node.resolution,
    container: node.container,
    supports_range: true,
  };
}

export async function searchAniListMetadata(searchQuery: string): Promise<AniListMediaItem[]> {
  const graphqlQuery = `
    query ($search: String) {
      Page(page: 1, perPage: 12) {
        media(search: $search, type: ANIME, isAdult: false, sort: [POPULARITY_DESC]) {
          id
          title { romaji english }
          coverImage { extraLarge large }
          bannerImage
          description(asHtml: false)
          episodes
          averageScore
          status
        }
      }
    }
  `;

  try {
    const res = await fetch(ANILIST_GRAPHQL_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query: graphqlQuery, variables: { search: searchQuery } }),
    });
    if (!res.ok) return [];
    const json = (await res.json()) as {
      data?: {
        Page?: {
          media?: Array<{
            id: number;
            title?: { romaji?: string; english?: string };
            coverImage?: { extraLarge?: string; large?: string };
            bannerImage?: string | null;
            description?: string | null;
            episodes?: number | null;
            averageScore?: number | null;
            status?: string | null;
          }>;
        };
      };
    };
    const list = json?.data?.Page?.media || [];

    return list.map((m) => ({
      id: m.id,
      titleEnglish: m.title?.english || m.title?.romaji || 'Unknown',
      titleRomaji: m.title?.romaji || m.title?.english || 'Unknown',
      posterUrl: m.coverImage?.extraLarge || m.coverImage?.large || '',
      bannerUrl: m.bannerImage || null,
      synopsis: (m.description || '').replace(/<[^>]+>/g, ''),
      episodes: m.episodes || null,
      averageScore: m.averageScore || null,
      status: m.status || 'FINISHED',
    }));
  } catch {
    return [];
  }
}

export async function fetchTrendingAniList(): Promise<AniListMediaItem[]> {
  const graphqlQuery = `
    query {
      Page(page: 1, perPage: 20) {
        media(type: ANIME, isAdult: false, sort: [TRENDING_DESC, POPULARITY_DESC]) {
          id
          title { romaji english }
          coverImage { extraLarge large }
          bannerImage
          description(asHtml: false)
          episodes
          averageScore
          status
        }
      }
    }
  `;

  try {
    const res = await fetch(ANILIST_GRAPHQL_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query: graphqlQuery }),
    });
    if (!res.ok) return [];
    const json = (await res.json()) as {
      data?: {
        Page?: {
          media?: Array<{
            id: number;
            title?: { romaji?: string; english?: string };
            coverImage?: { extraLarge?: string; large?: string };
            bannerImage?: string | null;
            description?: string | null;
            episodes?: number | null;
            averageScore?: number | null;
            status?: string | null;
          }>;
        };
      };
    };
    const list = json?.data?.Page?.media || [];

    return list.map((m) => ({
      id: m.id,
      titleEnglish: m.title?.english || m.title?.romaji || 'Unknown',
      titleRomaji: m.title?.romaji || m.title?.english || 'Unknown',
      posterUrl: m.coverImage?.extraLarge || m.coverImage?.large || '',
      bannerUrl: m.bannerImage || null,
      synopsis: (m.description || '').replace(/<[^>]+>/g, ''),
      episodes: m.episodes || null,
      averageScore: m.averageScore || null,
      status: m.status || 'RELEASING',
    }));
  } catch {
    return [];
  }
}
