import type { D1ReadClient } from '../utils/db';
const ANILIST_GRAPHQL_URL = 'https://graphql.anilist.co';

export type AniListMediaItem = AniListRawMedia;

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
  synonyms?: string[] | null;
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

export interface AniListSearchMetadata {
  id: number;
  titleEnglish: string;
  titleRomaji: string;
  posterUrl: string;
  bannerUrl: string | null;
  synopsis: string;
  episodes: number | null;
  averageScore: number | null;
  status: string;
}

export interface SourceBaseUrls {
  otakudesu: string;
  samehadaku: string;
  oploverz: string;
  kusonime: string;
}

export const DEFAULT_SOURCE_BASE_URLS: Readonly<SourceBaseUrls> = {
  otakudesu: 'https://otakudesu.blog',
  samehadaku: 'https://v2.samehadaku.how',
  oploverz: 'https://oploverz.ch',
  kusonime: 'https://kusonime.com',
};

function parseRetryAfterMs(headerVal: string | null, fallbackMs = 1500, maxMs = 5000): number {
  if (!headerVal) return fallbackMs;
  const trimmed = headerVal.trim();
  const seconds = Number(trimmed);
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.min(maxMs, Math.max(250, Math.ceil(seconds * 1000)));
  }
  const dateMs = Date.parse(trimmed);
  if (Number.isFinite(dateMs)) {
    return Math.min(maxMs, Math.max(250, dateMs - Date.now()));
  }
  return fallbackMs;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchAniListGraphQL(
  body: string,
  timeoutMs: number,
  userAgent = 'MilkglassCatalogSync/2.0',
  contextLabel = 'request'
): Promise<Response> {
  const doFetch = () =>
    fetch(ANILIST_GRAPHQL_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'User-Agent': userAgent,
      },
      body,
      signal: AbortSignal.timeout(timeoutMs),
    });

  let response = await doFetch();
  if (response.status === 429) {
    const retryAfterHeader = response.headers.get('Retry-After');
    const delayMs = parseRetryAfterMs(retryAfterHeader);
    console.error('[AniList] rate limited (429), retrying after backoff', {
      context: contextLabel,
      retryAfter: retryAfterHeader,
      delayMs,
    });
    await sleep(delayMs);
    response = await doFetch();
  }
  return response;
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
  synonyms
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
    genres_json, popularity, updated_at, episodes_total, next_airing_at, next_airing_ep,
    title_native, synonyms_json, norm_compact
  ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22)
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
    next_airing_ep = excluded.next_airing_ep,
    title_native = COALESCE(excluded.title_native, animes.title_native),
    synonyms_json = COALESCE(excluded.synonyms_json, animes.synonyms_json),
    norm_compact = excluded.norm_compact
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
      item.nextAiringEpisode?.episode ?? 0,
      item.title?.native || null,
      JSON.stringify((item.synonyms ?? []).filter((s) => typeof s === 'string' && s.trim().length > 0).slice(0, 12)),
      [item.title?.romaji, item.title?.english, item.title?.native]
        .filter(Boolean)
        .join(' ')
        .normalize('NFKD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9\u3040-\u30ff\u4e00-\u9fff]+/g, '')
        .slice(0, 200)
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
    try {
      const response = await fetchAniListGraphQL(
        JSON.stringify({
          query: CATALOG_PAGE_QUERY,
          variables: { page: plan.page, perPage, status: plan.status },
        }),
        8000,
        'MilkglassCatalogSync/2.0',
        `sync:page=${plan.page}:status=${plan.status ?? 'ALL'}`
      );
      entry.http = response.status;
      if (response.status === 429) {
        entry.error = 'rate_limited (429)';
        break;
      }
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
      console.error('[AniList] syncAniListCatalogDetailed page failed', {
        page: plan.page,
        status: plan.status ?? 'ALL',
        error: msg,
      });
    }
  }
  if (totalSynced > 0) {
    try {
      await db.batch(metaStatements(db, 'ongoing', totalSynced, nowSec));
    } catch {}
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
  try {
    const response = await fetchAniListGraphQL(
      JSON.stringify({
        query: SINGLE_MEDIA_QUERY,
        variables: { id: animeId },
      }),
      3500,
      'MilkglassCatalogSync/2.0',
      `enrich:animeId=${animeId}`
    );
    if (response.ok) {
      const payload = (await response.json()) as { data?: { Media?: AniListRawMedia } };
      const media = payload.data?.Media;
      if (media) {
        const nowSec = Math.floor(Date.now() / 1000);
        await bindAnimeUpsert(db, media, nowSec).run();
      }
    }
  } catch (err: unknown) {
    console.error('[AniList] enrichSingleAnimeFromAniListAsync failed', {
      animeId,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

let cachedSourceUrls: { expiresAt: number; value: SourceBaseUrls } | null = null;
const SOURCE_URLS_CACHE_TTL_MS = 5 * 60 * 1000;

export function invalidateSourceBaseUrlsCache(): void {
  cachedSourceUrls = null;
}

export async function getSourceBaseUrls(db: D1ReadClient): Promise<SourceBaseUrls> {
  const now = Date.now();
  if (cachedSourceUrls && cachedSourceUrls.expiresAt > now) {
    return { ...cachedSourceUrls.value };
  }
  const resolved: SourceBaseUrls = { ...DEFAULT_SOURCE_BASE_URLS };
  try {
    const { results } = await db
      .prepare(
        `SELECT key, value FROM catalog_meta
         WHERE key IN ('otakudesu_base_url', 'samehadaku_base_url', 'oploverz_base_url', 'kusonime_base_url')`
      )
      .all<{ key: string; value: string }>();

    for (const row of results ?? []) {
      const clean = (row.value || '').trim().replace(/\/+$/, '');
      if (!clean.startsWith('https://')) continue;
      if (row.key === 'otakudesu_base_url') resolved.otakudesu = clean;
      else if (row.key === 'samehadaku_base_url') resolved.samehadaku = clean;
      else if (row.key === 'oploverz_base_url') resolved.oploverz = clean;
      else if (row.key === 'kusonime_base_url') resolved.kusonime = clean;
    }
    cachedSourceUrls = { expiresAt: now + SOURCE_URLS_CACHE_TTL_MS, value: { ...resolved } };
  } catch (err: unknown) {
    console.error('[AniList] getSourceBaseUrls D1 read failed', {
      error: err instanceof Error ? err.message : String(err),
    });
  }
  return resolved;
}

export async function getOtakudesuBaseUrl(db: D1ReadClient): Promise<string> {
  return (await getSourceBaseUrls(db)).otakudesu;
}

export async function searchAniListMetadata(searchQuery: string): Promise<AniListSearchMetadata[]> {
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
    const res = await fetchAniListGraphQL(
      JSON.stringify({ query: graphqlQuery, variables: { search: searchQuery } }),
      5000,
      'MilkglassCatalogSync/2.0',
      'search'
    );
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
  } catch (err: unknown) {
    console.error('[AniList] searchAniListMetadata failed', {
      queryLength: searchQuery.length,
      error: err instanceof Error ? err.message : String(err),
    });
    return [];
  }
}

export async function fetchTrendingAniList(): Promise<AniListSearchMetadata[]> {
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
    const res = await fetchAniListGraphQL(
      JSON.stringify({ query: graphqlQuery }),
      5000,
      'MilkglassCatalogSync/2.0',
      'trending'
    );
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
  } catch (err: unknown) {
    console.error('[AniList] fetchTrendingAniList failed', {
      error: err instanceof Error ? err.message : String(err),
    });
    return [];
  }
}
