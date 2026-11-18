import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { recheckAfterSec } from '../services/sync-schedule';
import {
  getOtakudesuBaseUrl,
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

async function encryptRecipeHkdf(
  recipeJson: string,
  masterSecret: string,
  slugContext: string
): Promise<{ salt_b64: string; nonce_b64: string; payload_b64: string }> {
  const enc = new TextEncoder();
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const nonce = crypto.getRandomValues(new Uint8Array(12));

  const baseKey = await crypto.subtle.importKey(
    'raw',
    enc.encode(masterSecret || 'milkglass-default-32byte-secret!'),
    'HKDF',
    false,
    ['deriveKey']
  );

  const aesKey = await crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt,
      info: enc.encode(`milkglass-ticket:${slugContext}`),
    },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt']
  );

  const cipherBuf = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce },
    aesKey,
    enc.encode(recipeJson)
  );

  const toB64 = (buf: ArrayBuffer | Uint8Array) =>
    btoa(String.fromCharCode(...new Uint8Array(buf)));

  return {
    salt_b64: toB64(salt),
    nonce_b64: toB64(nonce),
    payload_b64: toB64(cipherBuf),
  };
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
  edgeCache(7 * 86400, 0, true, { immutable: true }),
  zValidator('query', z.object({ v: z.string().max(40).optional() })),
  async (c) => {
    const db = c.env.DB;
    const { v } = c.req.valid('query');
    const [metaRes, countRes] = await db.batch([
      db.prepare(META_QUERY),
      db.prepare(`SELECT COUNT(*) AS total FROM animes`),
    ]);
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
    const { results } = await db
      .prepare(`SELECT ${LIST_COLUMNS} FROM animes ORDER BY popularity DESC LIMIT 1000`)
      .all<Record<string, unknown>>();
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
    const anime = await db
      .prepare(`SELECT * FROM animes WHERE id = ?1`)
      .bind(id)
      .first<Record<string, unknown>>();
    if (!anime) {
      return c.json({ status: 'error', message: 'Anime tidak ditemukan.' }, 404);
    }
    const { results: episodes } = await db
      .prepare(
        `SELECT id, anime_id, episode_number, name, op_start, op_end, ed_start, ed_end, created_at, updated_at
         FROM episodes
         WHERE anime_id = ?1
         ORDER BY episode_number DESC`
      )
      .bind(id)
      .all();

    let genres: string[] = [];
    try {
      genres = JSON.parse(String(anime.genres_json || '[]'));
    } catch {
      genres = [];
    }
    return c.json({
      status: 'ok',
      data: {
        ...anime,
        genres,
        episodes: episodes ?? [],
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
    const episode = await db
      .prepare(
        `SELECT id, anime_id, episode_number, name, op_start, op_end, ed_start, ed_end, created_at, updated_at
         FROM episodes
         WHERE id = ?1`
      )
      .bind(id)
      .first<{
        id: number;
        anime_id: number;
        episode_number: number;
        name: string;
        op_start: number | null;
        op_end: number | null;
        ed_start: number | null;
        ed_end: number | null;
      }>();

    if (!episode) {
      return c.json({ status: 'error', message: 'Episode tidak ditemukan.' }, 404);
    }
    const { results: sources } = await db
      .prepare(
        `SELECT id, episode_id, type, quality, url_payload, release_group,
                sub_kind, duration_sec, file_size_bytes, fail_count, cooldown_until
         FROM stream_sources
         WHERE episode_id = ?1 AND is_active = 1
         ORDER BY (cooldown_until > ?2) ASC, fail_count ASC, quality DESC`
      )
      .bind(id, nowSec)
      .all();

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
        stream_sources: sources ?? [],
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
  const otakudesuUrl = await getOtakudesuBaseUrl(c.env.DB);
  const metaRows = await c.env.DB.prepare(
    `SELECT key, value FROM catalog_meta WHERE key IN ('samehadaku_base_url', 'oploverz_base_url', 'kusonime_base_url')`
  ).all<{ key: string; value: string }>();
  const map = new Map<string, string>();
  for (const r of metaRows.results ?? []) map.set(r.key, r.value);

  return c.json({
    status: 'ok',
    resolution_mode: 'CLIENT_CASCADE_MKV',
    target_format: 'MKV',
    target_resolutions: ['1080p', '720p', '480p'],
    sources: [
      { rank: 1, id: 'otakudesu', base_url: otakudesuUrl },
      { rank: 2, id: 'samehadaku', base_url: map.get('samehadaku_base_url') || 'https://v2.samehadaku.how' },
      { rank: 3, id: 'oploverz', base_url: map.get('oploverz_base_url') || 'https://oploverz.ch' },
      { rank: 4, id: 'kusonime', base_url: map.get('kusonime_base_url') || 'https://kusonime.com' },
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
  const otakudesuUrl = await getOtakudesuBaseUrl(c.env.DB);
  return c.json({
    status: 'ok',
    slug,
    resolution_mode: 'CLIENT_CASCADE_MKV',
    target_format: 'MKV',
    resolutions: ['1080p', '720p', '480p'],
    source_cascade: ['otakudesu', 'samehadaku', 'oploverz', 'kusonime'],
    otakudesu_base_url: otakudesuUrl,
    sub_state: 'MKV_EMBEDDED',
    seek_capability: 'HTTP_RANGE',
  });
});

export default catalogRoutes;
