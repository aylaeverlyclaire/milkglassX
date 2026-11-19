import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { recheckAfterSec } from '../services/sync-schedule';
import {
  getSourceBaseUrls,
  searchAniListMetadata,
  fetchTrendingAniList,
} from '../services/anilist.service';
import { AppBindings } from '../index';

export const catalogRoutes = new Hono<{ Bindings: AppBindings }>();

const CATALOG_STALE_AFTER_SEC = 18 * 3600;
const LIST_COLUMNS = `id, mal_id, title, title_alt, score, type, status, schedule_day,
                duration, studio, release_season, poster_url, popularity, updated_at,
                genres_json, episodes_total, next_airing_at, next_airing_ep`;

const META_QUERY = `SELECT key, value FROM catalog_meta WHERE key IN ('last_import_at', 'last_full_import_at')`;

function shapeAnimeRow(row: Record<string, unknown>) {
  const { genres_json, ...rest } = row;
  let genres: string[] = [];
  try {
    const parsed = JSON.parse(String(genres_json || '[]'));
    if (Array.isArray(parsed)) {
      genres = parsed.filter((g): g is string => typeof g === 'string');
    }
  } catch {
    genres = [];
  }
  return { ...rest, genres };
}

function shapeRows(rows: Array<Record<string, unknown>> | undefined) {
  return (rows ?? []).map((r) => shapeAnimeRow(r));
}

function readSyncMarker(rows: Array<{ key: string; value: string }> | undefined) {
  const map = new Map<string, string>();
  for (const r of rows ?? []) map.set(r.key, r.value);
  const num = (k: string) => {
    const n = Number(map.get(k));
    return Number.isFinite(n) && n > 0 ? n : null;
  };
  return { synced_at: num('last_import_at'), full_synced_at: num('last_full_import_at') };
}

function catalogVersion(
  marker: { synced_at: number | null; full_synced_at: number | null },
  total: number
) {
  return `${marker.synced_at ?? 0}.${total}`;
}

function escapeSqlLike(input: string): string {
  return input.replace(/[\\%_]/g, '\\$&');
}

catalogRoutes.get('/home', edgeCache(300, 600, false), async (c) => {
  const db = c.env.DB;
  const [ongoingRes, popularRes, completedRes, metaRes] = await db.batch([
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE status = 1
         ORDER BY popularity DESC
         LIMIT 20`
    ),
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         ORDER BY popularity DESC
         LIMIT 20`
    ),
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE status = 2
         ORDER BY popularity DESC
         LIMIT 20`
    ),
    db.prepare(META_QUERY),
  ]);

  if ((popularRes.results ?? []).length === 0) {
    c.header('X-Milkglass-NoCache', '1');
  }

  const popular = shapeRows(popularRes.results as Array<Record<string, unknown>>);
  return c.json({
    status: 'ok',
    data: {
      ongoing: shapeRows(ongoingRes.results as Array<Record<string, unknown>>),
      popular,
      tamat: shapeRows(completedRes.results as Array<Record<string, unknown>>),
      extras: popular.slice(0, 5),
    },
    meta: readSyncMarker(metaRes.results as Array<{ key: string; value: string }>),
  });
});

catalogRoutes.get('/status', edgeCache(60, 120, false), async (c) => {
  const db = c.env.DB;
  const [metaRes, countRes] = await db.batch([
    db.prepare(META_QUERY),
    db.prepare(`SELECT COUNT(*) AS total FROM animes`),
  ]);

  const marker = readSyncMarker(metaRes.results as Array<{ key: string; value: string }>);
  const total = Number((countRes.results?.[0] as { total?: number } | undefined)?.total ?? 0);
  const nowSec = Math.floor(Date.now() / 1000);
  const ageSec = marker.synced_at === null ? null : Math.max(0, nowSec - marker.synced_at);
  const stale = ageSec === null || ageSec > CATALOG_STALE_AFTER_SEC;

  if (total === 0) {
    c.header('X-Milkglass-NoCache', '1');
  }

  return c.json({
    status: 'ok',
    data: {
      total,
      version: catalogVersion(marker, total),
      synced_at: marker.synced_at,
      full_synced_at: marker.full_synced_at,
      age_sec: ageSec,
      stale,
      stale_after_sec: CATALOG_STALE_AFTER_SEC,
      recheck_after_sec: recheckAfterSec(nowSec, marker.synced_at),
    },
  });
});

catalogRoutes.get(
  '/snapshot',
  rateLimit(20, 60),
  edgeCache(7 * 86400, 0, true, { immutable: true }),
  zValidator('query', z.object({ v: z.string().regex(/^\d+\.\d+$/).max(40).optional() })),
  async (c) => {
    const db = c.env.DB;
    const { v } = c.req.valid('query');
    const statements: D1PreparedStatement[] = [
      db.prepare(META_QUERY),
      db.prepare(`SELECT COUNT(*) AS total FROM animes`),
    ];
    if (v) {
      statements.push(
        db.prepare(`SELECT ${LIST_COLUMNS} FROM animes ORDER BY popularity DESC LIMIT 1000`)
      );
    }
    const batchRes = await db.batch(statements);
    const metaRes = batchRes[0];
    const countRes = batchRes[1];
    const marker = readSyncMarker(metaRes.results as Array<{ key: string; value: string }>);
    const total = Number((countRes.results?.[0] as { total?: number } | undefined)?.total ?? 0);
    const version = catalogVersion(marker, total);
    if (v !== version) {
      const target = new URL(c.req.url);
      target.search = `?v=${encodeURIComponent(version)}`;
      c.header('Cache-Control', 'no-store');
      return c.redirect(target.toString(), 307);
    }
    if (total === 0) {
      c.header('X-Milkglass-NoCache', '1');
    }
    const results = (batchRes[2]?.results ?? []) as Array<Record<string, unknown>>;
    return c.json({
      status: 'ok',
      data: shapeRows(results),
      meta: {
        version,
        synced_at: marker.synced_at,
        full_synced_at: marker.full_synced_at,
        count: total,
      },
    });
  }
);

const browseQuerySchema = z.object({
  q: z.string().trim().max(100).optional(),
  genre: z.string().trim().max(40).optional(),
  status: z.coerce.number().int().min(1).max(5).optional(),
  schedule_day: z.coerce.number().int().min(0).max(7).optional(),
  page: z.coerce.number().int().min(1).max(100).default(1),
});

catalogRoutes.get(
  '/browse',
  rateLimit(40, 60),
  edgeCache(300, 600, true),
  zValidator('query', browseQuerySchema),
  async (c) => {
    const { q, genre, status, schedule_day, page } = c.req.valid('query');
    const limit = 24;
    const offset = (page - 1) * limit;
    const conditions = ['1 = 1'];
    const params: Array<string | number> = [];

    if (q && q.length > 0) {
      const escapedQ = escapeSqlLike(q);
      params.push(`%${escapedQ}%`);
      conditions.push(
        `(title LIKE ?${params.length} ESCAPE '\\' OR title_alt LIKE ?${params.length} ESCAPE '\\')`
      );
    }
    if (genre && genre.length > 0) {
      const escapedGenre = escapeSqlLike(genre);
      params.push(`%"${escapedGenre}"%`);
      conditions.push(`genres_json LIKE ?${params.length} ESCAPE '\\'`);
    }
    if (status !== undefined) {
      params.push(status);
      conditions.push(`status = ?${params.length}`);
    }
    if (schedule_day !== undefined) {
      params.push(schedule_day);
      conditions.push(`schedule_day = ?${params.length}`);
    }

    params.push(limit, offset);
    const sql = `
      SELECT ${LIST_COLUMNS}
      FROM animes
      WHERE ${conditions.join(' AND ')}
      ORDER BY popularity DESC
      LIMIT ?${params.length - 1} OFFSET ?${params.length}
    `;
    const { results } = await c.env.DB.prepare(sql).bind(...params).all<Record<string, unknown>>();
    return c.json({
      status: 'ok',
      data: shapeRows(results),
      meta: { page, limit },
    });
  }
);

const idParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});

catalogRoutes.get(
  '/anime/:id',
  edgeCache(300, 600, false),
  zValidator('param', idParamSchema),
  async (c) => {
    const { id } = c.req.valid('param');
    const db = c.env.DB;
    const [animeRes, episodesRes] = await db.batch([
      db.prepare(`SELECT * FROM animes WHERE id = ?1`).bind(id),
      db
        .prepare(
          `SELECT id, anime_id, episode_number, name, op_start, op_end, ed_start, ed_end, created_at, updated_at
           FROM episodes
           WHERE anime_id = ?1
           ORDER BY episode_number DESC`
        )
        .bind(id),
    ]);

    const anime = animeRes.results?.[0] as Record<string, unknown> | undefined;
    if (!anime) {
      return c.json({ status: 'error', message: 'Anime tidak ditemukan.' }, 404);
    }

    return c.json({
      status: 'ok',
      data: {
        ...shapeAnimeRow(anime),
        episodes: episodesRes.results ?? [],
      },
    });
  }
);

catalogRoutes.get(
  '/episode/:id',
  edgeCache(60, 120, false),
  zValidator('param', idParamSchema),
  async (c) => {
    const { id } = c.req.valid('param');
    const nowSec = Math.floor(Date.now() / 1000);
    const db = c.env.DB;
    const [episodeRes, sourcesRes] = await db.batch([
      db
        .prepare(
          `SELECT id, anime_id, episode_number, name, op_start, op_end, ed_start, ed_end, created_at, updated_at
           FROM episodes
           WHERE id = ?1`
        )
        .bind(id),
      db
        .prepare(
          `SELECT id, episode_id, type, quality, url_payload, release_group,
                  sub_kind, duration_sec, file_size_bytes, fail_count, cooldown_until
           FROM stream_sources
           WHERE episode_id = ?1 AND is_active = 1
           ORDER BY (cooldown_until > ?2) ASC, fail_count ASC, quality DESC`
        )
        .bind(id, nowSec),
    ]);

    const episode = episodeRes.results?.[0] as
      | {
          id: number;
          anime_id: number;
          episode_number: number;
          name: string;
          op_start: number | null;
          op_end: number | null;
          ed_start: number | null;
          ed_end: number | null;
        }
      | undefined;

    if (!episode) {
      return c.json({ status: 'error', message: 'Episode tidak ditemukan.' }, 404);
    }

    return c.json({
      status: 'ok',
      data: {
        id: episode.id,
        anime_id: episode.anime_id,
        episode_number: episode.episode_number,
        name: episode.name,
        skip_times: {
          op:
            episode.op_start !== null && episode.op_end !== null
              ? { start: episode.op_start, end: episode.op_end }
              : null,
          ed:
            episode.ed_start !== null && episode.ed_end !== null
              ? { start: episode.ed_start, end: episode.ed_end }
              : null,
        },
        stream_sources: sourcesRes.results ?? [],
      },
    });
  }
);

catalogRoutes.get('/', edgeCache(300), async (c) => {
  const rows = await c.env.DB.prepare(
    'SELECT slug, anilist_id, title, romaji_title, episode, poster_url, banner_url, synopsis, updated_at FROM catalog_episodes ORDER BY updated_at DESC LIMIT 60'
  ).all();

  if (rows.results && rows.results.length > 0) {
    return c.json({ status: 'ok', items: rows.results });
  }

  const trending = await fetchTrendingAniList();
  return c.json({ status: 'ok', items: trending });
});

catalogRoutes.get('/sources', edgeCache(300, 600, false), async (c) => {
  const urls = await getSourceBaseUrls(c.env.DB);
  return c.json({
    status: 'ok',
    resolution_mode: 'CLIENT_CASCADE_MKV',
    target_format: 'MKV',
    target_resolutions: ['1080p', '720p', '480p'],
    cf_handler_available: Boolean(c.env.CF_HANDLER_URL && c.env.CF_HANDLER_URL.trim().startsWith('http')),
    sources: [
      { rank: 1, id: 'otakudesu', base_url: urls.otakudesu },
      { rank: 2, id: 'samehadaku', base_url: urls.samehadaku },
      { rank: 3, id: 'oploverz', base_url: urls.oploverz },
      { rank: 4, id: 'kusonime', base_url: urls.kusonime },
    ],
  });
});

catalogRoutes.get('/search', edgeCache(300, 600, true), async (c) => {
  const q = (c.req.query('q') || '').trim();
  if (q.length < 2) {
    return c.json({ status: 'ok', data: [], metadata: [], resolution_mode: 'CLIENT_CASCADE_MKV' });
  }

  const escapedQ = escapeSqlLike(q);
  const pattern = `%${escapedQ}%`;
  const [d1Animes, anilistItems] = await Promise.all([
    c.env.DB.prepare(
      `SELECT ${LIST_COLUMNS}
       FROM animes
       WHERE title LIKE ?1 ESCAPE '\\' OR title_alt LIKE ?1 ESCAPE '\\'
       ORDER BY popularity DESC
       LIMIT 24`
    )
      .bind(pattern)
      .all<Record<string, unknown>>(),
    searchAniListMetadata(q),
  ]);

  return c.json({
    status: 'ok',
    query: q,
    resolution_mode: 'CLIENT_CASCADE_MKV',
    data: shapeRows(d1Animes.results),
    metadata: anilistItems,
  });
});

catalogRoutes.use('/play/*', rateLimit(20, 60));

catalogRoutes.get('/play/:slug', async (c) => {
  const slug = c.req.param('slug');
  const urls = await getSourceBaseUrls(c.env.DB);
  return c.json({
    status: 'ok',
    slug,
    resolution_mode: 'CLIENT_CASCADE_MKV',
    target_format: 'MKV',
    resolutions: ['1080p', '720p', '480p'],
    source_cascade: ['otakudesu', 'samehadaku', 'oploverz', 'kusonime'],
    otakudesu_base_url: urls.otakudesu,
    source_urls: urls,
    sub_state: 'MKV_EMBEDDED',
    seek_capability: 'HTTP_RANGE',
  });
});

export default catalogRoutes;
