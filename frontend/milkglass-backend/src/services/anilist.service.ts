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

import { DEFAULT_SOURCE_URLS, type SourceBaseUrls } from '../utils/sources';
export type { SourceBaseUrls };
export const DEFAULT_SOURCE_BASE_URLS: Readonly<SourceBaseUrls> = DEFAULT_SOURCE_URLS;

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

const ENRICH_MEDIA_FIELDS = `
  id
  idMal
  title {
    romaji
    english
    native
  }
  averageScore
  episodes
  genres
  synonyms
  coverImage {
    extraLarge
    large
  }
`;

const SINGLE_MEDIA_QUERY = `
query ($id: Int) {
  Media(id: $id, type: ANIME, isAdult: false) {
    ${ENRICH_MEDIA_FIELDS}
  }
}
`;

const BACKFILL_QUERY = `
query ($ids: [Int], $perPage: Int) {
  Page(page: 1, perPage: $perPage) {
    media(id_in: $ids, type: ANIME) {
      ${ENRICH_MEDIA_FIELDS}
    }
  }
}
`;

const ENRICH_SEARCH_QUERY = `
query ($search: String) {
  Page(page: 1, perPage: 5) {
    media(search: $search, type: ANIME, isAdult: false) {
      ${ENRICH_MEDIA_FIELDS}
    }
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

/**
 * AniList HANYA melengkapi metadata (total episode, rating/score, poster, mal_id)
 * pada entri katalog yang sudah berasal dari Samehadaku atau Otakudesu.
 * Tidak pernah meng-insert entri katalog baru dari AniList.
 */
const ENRICH_ANIME_SQL = `
  UPDATE animes
  SET
    mal_id = COALESCE(?1, mal_id),
    title = COALESCE(NULLIF(?2, ''), title),
    title_alt = COALESCE(NULLIF(?3, ''), title_alt),
    score = CASE WHEN ?4 > 0 THEN ?4 ELSE score END,
    poster_url = COALESCE(NULLIF(?5, ''), poster_url),
    episodes_total = CASE WHEN ?6 > episodes_total THEN ?6 ELSE episodes_total END,
    genres_json = CASE WHEN ?7 != '[]' THEN ?7 ELSE genres_json END,
    synonyms_json = CASE WHEN ?8 != '[]' THEN ?8 ELSE synonyms_json END,
    updated_at = ?9
  WHERE id = ?10
    AND (source_urls_json LIKE '%"samehadaku"%' OR source_urls_json LIKE '%"otakudesu"%')
`;

export function bindAnimeEnrichOnly(
  db: D1Database,
  targetAnimeId: number,
  item: AniListRawMedia,
  nowSec: number
): D1PreparedStatement {
  const titleMain = (item.title?.romaji || item.title?.english || '').trim();
  const titleAlt = (item.title?.english || item.title?.native || '').trim();
  const scoreTenScale = item.averageScore ? Number((item.averageScore / 10).toFixed(2)) : 0;
  const posterUrl = item.coverImage?.extraLarge || item.coverImage?.large || '';
  const episodesTotal = item.episodes && item.episodes > 0 ? item.episodes : 0;
  const genres = Array.isArray(item.genres)
    ? item.genres.filter((g): g is string => typeof g === 'string' && g.trim().length > 0)
    : [];
  const synonyms = Array.isArray(item.synonyms)
    ? item.synonyms.filter((x): x is string => typeof x === 'string' && x.trim().length > 1).slice(0, 12)
    : [];
  return db
    .prepare(ENRICH_ANIME_SQL)
    .bind(
      item.idMal ?? null,
      titleMain,
      titleAlt,
      scoreTenScale,
      posterUrl,
      episodesTotal,
      JSON.stringify(genres),
      JSON.stringify(synonyms),
      nowSec,
      targetAnimeId
    );
}

const META_LAST_IMPORT_AT = 'last_import_at';

const UPSERT_META_SQL = `
  INSERT INTO catalog_meta (key, value, updated_at) VALUES (?1, ?2, ?3)
  ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
`;

/**
 * Memperkaya (UPDATE-only) total episode, rating, dan poster pada entri katalog
 * Samehadaku/Otakudesu yang sudah ada di D1. Tidak pernah menambah katalog baru dari AniList.
 */
export async function importAniListMedia(
  db: D1Database,
  items: AniListRawMedia[],
  _scope?: 'ongoing' | 'full'
): Promise<number> {
  const nowSec = Math.floor(Date.now() / 1000);
  const valid = items.filter((m) => m && Number.isInteger(m.id) && m.id > 0);
  if (valid.length === 0) return 0;
  await db.batch(valid.map((m) => bindAnimeEnrichOnly(db, m.id, m, nowSec)));
  return valid.length;
}

export async function ensureAnimeExistsFastInD1(
  db: D1Database,
  animeId: number,
  malId?: number,
  _fallbackTitle = 'Anime'
): Promise<{ exists: boolean; malId: number | null; needsEnrichment: boolean }> {
  const existing = await db
    .prepare(
      `SELECT id, mal_id, poster_url, score, genres_json FROM animes
       WHERE id = ?1
         AND (source_urls_json LIKE '%"samehadaku"%' OR source_urls_json LIKE '%"otakudesu"%')`
    )
    .bind(animeId)
    .first<{ id: number; mal_id: number | null; poster_url: string | null; score: number | null; genres_json: string | null }>();
  if (!existing) {
    return { exists: false, malId: null, needsEnrichment: false };
  }
  const needsEnrichment =
    !existing.poster_url || !existing.score || !existing.genres_json || existing.genres_json === '[]';
  return {
    exists: true,
    malId: existing.mal_id ?? malId ?? null,
    needsEnrichment,
  };
}

export async function enrichSingleAnimeFromAniListAsync(
  db: D1Database,
  animeId: number
): Promise<void> {
  if (animeId >= 900_000_000) return;
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
        await bindAnimeEnrichOnly(db, animeId, media, nowSec).run();
      }
    }
  } catch (err: unknown) {
    console.error('[AniList] enrichSingleAnimeFromAniListAsync failed', {
      animeId,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

function normalizeIdentityTitle(raw: string): string {
  return raw
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/\b(?:subtitle indonesia|sub indo|batch|bd)\b/gi, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

const ROMAN_SEASON_MAP: Record<string, number> = { ii: 2, iii: 3, iv: 4, v: 5 };

function extractSeasonTag(rawTitle: string, norm: string): number {
  const m =
    norm.match(/\bseason\s*(\d+)\b/) ||
    norm.match(/\bs(\d+)\b/) ||
    norm.match(/\b(\d+)(?:st|nd|rd|th)\s+season\b/);
  if (m) return Number.parseInt(m[1], 10);
  const roman = rawTitle.match(/\b(II|III|IV|V)\b/);
  if (roman) return ROMAN_SEASON_MAP[roman[1].toLowerCase()] ?? 1;
  return 1;
}

function extractPartTag(norm: string): number {
  const m = norm.match(/\b(?:part|cour)\s*(\d+)\b/);
  return m ? Number.parseInt(m[1], 10) : 0;
}

function extractFormatKind(norm: string): string {
  if (/\b(?:movie|gekijouban|film)\b/.test(norm)) return 'MOVIE';
  if (/\b(?:ova|oad)\b/.test(norm)) return 'OVA';
  if (/\b(?:side\s*story|gaiden|spin\s*off|nikki|diaries|coleus|bullet)\b/.test(norm)) return 'SIDE_STORY';
  if (/\b(?:special|recap)\b/.test(norm)) return 'SPECIAL';
  return 'TV';
}

function stripSeasonAndPart(norm: string): string {
  return norm
    .replace(/\bseason\s*\d+\b/g, ' ')
    .replace(/\bs\d+\b/g, ' ')
    .replace(/\b\d+(?:st|nd|rd|th)\s+season\b/g, ' ')
    .replace(/\b(?:ii|iii|iv|v)\b/g, ' ')
    .replace(/\b(?:part|cour)\s*\d+\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function isStrictTitleMatch(sourceTitle: string, candidateTitle: string): boolean {
  const cleanA = sourceTitle.replace(/\+\s*(?:ova|oad|special)\b/gi, ' ');
  const cleanB = candidateTitle.replace(/\+\s*(?:ova|oad|special)\b/gi, ' ');
  const a = normalizeIdentityTitle(cleanA);
  const b = normalizeIdentityTitle(cleanB);
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
 * Melengkapi metadata (total eps, rating, poster) dari AniList HANYA untuk entri katalog
 * yang berasal dari Samehadaku atau Otakudesu.
 */
export async function backfillIncompleteAnime(db: D1Database, limit = 50): Promise<number> {
  const rows = await db
    .prepare(
      `SELECT id, title FROM animes
       WHERE (source_urls_json LIKE '%"samehadaku"%' OR source_urls_json LIKE '%"otakudesu"%')
         AND (poster_url IS NULL OR poster_url = '' OR score = 0 OR episodes_total = 0 OR genres_json IS NULL OR genres_json = '' OR genres_json = '[]')
       ORDER BY (CASE WHEN status = 1 THEN 0 ELSE 1 END) ASC, updated_at ASC
       LIMIT ?1`
    )
    .bind(limit)
    .all<{ id: number; title: string }>();

  const allRows = rows.results ?? [];
  if (allRows.length === 0) return 0;

  const anilistIdRows = allRows.filter((r) => Number.isInteger(r.id) && r.id > 0 && r.id < 900_000_000);
  const syntheticRows = allRows.filter((r) => Number.isInteger(r.id) && r.id >= 900_000_000).slice(0, 6);

  const nowSec = Math.floor(Date.now() / 1000);
  const stmts: D1PreparedStatement[] = [];

  if (anilistIdRows.length > 0) {
    const ids = anilistIdRows.map((r) => r.id);
    const response = await fetchAniListGraphQL(
      JSON.stringify({ query: BACKFILL_QUERY, variables: { ids, perPage: ids.length } }),
      8000,
      'MilkglassCatalogSync/2.0',
      `backfill:n=${ids.length}`
    );
    if (response.ok) {
      const payload = (await response.json()) as { data?: { Page?: { media?: AniListRawMedia[] } } };
      for (const m of payload.data?.Page?.media ?? []) {
        if (m && Number.isInteger(m.id) && m.id > 0) {
          stmts.push(bindAnimeEnrichOnly(db, m.id, m, nowSec));
        }
      }
    } else {
      console.error('[AniList] backfill http error', { status: response.status, ids: ids.length });
    }
  }

  for (const row of syntheticRows) {
    if (!row.title || row.title.trim().length < 2) continue;
    try {
      const res = await fetchAniListGraphQL(
        JSON.stringify({ query: ENRICH_SEARCH_QUERY, variables: { search: row.title.trim() } }),
        4000,
        'MilkglassCatalogSync/2.0',
        `backfill-title:${row.id}`
      );
      if (!res.ok) continue;
      const payload = (await res.json()) as { data?: { Page?: { media?: AniListRawMedia[] } } };
      const candidates = payload.data?.Page?.media ?? [];
      const match = candidates.find((m) =>
        [m.title?.romaji, m.title?.english]
          .filter((t): t is string => typeof t === 'string' && t.length > 0)
          .some((t) => isStrictTitleMatch(row.title, t))
      );
      if (match) {
        stmts.push(bindAnimeEnrichOnly(db, row.id, match, nowSec));
      } else {
        // Rotasi baris miss ke belakang antrean agar tidak memblokir baris lain di cron berikutnya
        stmts.push(db.prepare(`UPDATE animes SET updated_at = ?1 WHERE id = ?2`).bind(nowSec, row.id));
      }
    } catch {
      // Lewati baris yang gagal di-enrich agar tidak memblokir antrean lain
    }
  }

  if (stmts.length === 0) return 0;

  await db.batch([
    ...stmts,
    db.prepare(UPSERT_META_SQL).bind(META_LAST_IMPORT_AT, String(nowSec), nowSec),
  ]);
  return stmts.length;
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
         WHERE key IN ('otakudesu_base_url', 'samehadaku_base_url')`
      )
      .all<{ key: string; value: string }>();

    for (const row of results ?? []) {
      const clean = (row.value || '').trim().replace(/\/+$/, '');
      if (!clean.startsWith('https://')) continue;
      if (row.key === 'otakudesu_base_url') resolved.otakudesu = clean;
      else if (row.key === 'samehadaku_base_url') resolved.samehadaku = clean;
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
