import { Hono } from 'hono';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { zValidatorParam, zValidatorQuery } from '../middleware/validator';
import { recheckAfterSec } from '../services/sync-schedule';
import {
  getSourceBaseUrls,
  searchAniListMetadata,
  fetchTrendingAniList,
} from '../services/anilist.service';
import { AppBindings } from '../index';
import { readDb } from '../utils/db';

const R2_KEY_HOME = 'catalog/v1/home.json';
const R2_KEY_STATUS = 'catalog/v1/status.json';
const R2_KEY_SNAPSHOT = 'catalog/v1/snapshot.json';

const DEFAULT_ENGINE_RULES = {
  otakudesu: { search_path: '/?s={q}&post_type=anime', selectors: { search_item: 'ul.chivsrc > li', search_link: 'h2 > a[href]', episode_box: 'div.episodelist', episode_link: 'ul li a[href]', download_block: 'div.download ul', download_row: 'li', quality_text: 'strong', size_text: 'i', hoster_link: 'a[href]' } },
  samehadaku: { search_path: '/?s={q}&post_type=anime', selectors: { search_item: 'div.animposx > a[href], article.animpost div.animposx > a[href], article.animepost div.animposx a[href]', search_title: '.data .title h2', series_link: "a[href*='/anime/'], div.nvs.nvsc a[href]", episode_row: 'div.lstepsiode.listeps ul li, .listeps ul li, .lstepsiode ul li, .episode-lainnya ul li', episode_badge: '.epsright, .epl-num, .shrink-0 a, .shrink-0 div', episode_link: "h4 a[href], .lchx a[href], a[href*='-episode-'], a[href]", download_block: 'div.download-eps, div#downloadb', download_header: 'p b, p', download_row: 'ul li', quality_text: 'strong', hoster_link: 'a[href]' } },
  oploverz: { back_api_url: 'https://backapi.oploverz.ac', search_path: '/?s={q}', selectors: { search_item: 'div.bsx, article.bs', search_title: 'div.tt h4, h4, .tt', series_link: "div.nvs.nvsc a[href], a[aria-label='All Episodes'], a[href*='/series/'], .year a[href*='/anime/']", episode_row: 'div.eplister ul li, ul#episodelist li', episode_badge: 'div.epl-num', download_block: 'div.soraddlx, div.mctnx, div.download-box', download_header: '.sorattlx, h3, h4', download_row: '.soraurlx, li', quality_text: 'strong', hoster_link: 'a[href]' } },
  kusonime: { search_path: '/?s={q}', selectors: { search_item: 'div.kover a[href], h2.episodeye a[href]', download_block: 'div.smokeddlrh, div.dlbodz', download_header: '.smokettlrh, h3', download_row: 'div.smokeurlrh', quality_text: 'strong', hoster_link: 'a[href]' } },
};


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
  if (c.env.CATALOG_R2) {
    try {
      const obj = await c.env.CATALOG_R2.get(R2_KEY_HOME);
      if (obj) {
        return new Response(obj.body, {
          status: 200,
          headers: { 'Content-Type': 'application/json; charset=utf-8', 'X-Milkglass-Source': 'R2' },
        });
      }
    } catch {}
  }
  const db = readDb(c.env);
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
  if (c.env.CATALOG_R2) {
    try {
      const obj = await c.env.CATALOG_R2.get(R2_KEY_STATUS);
      if (obj) {
        const doc = await obj.json<{ total: number; version: string; synced_at: number | null; full_synced_at: number | null }>();
        if (doc && typeof doc.total === 'number' && doc.total > 0 && typeof doc.version === 'string') {
          const nowSec = Math.floor(Date.now() / 1000);
          const ageSec = doc.synced_at === null ? null : Math.max(0, nowSec - doc.synced_at);
          const stale = ageSec === null || ageSec > CATALOG_STALE_AFTER_SEC;
          c.header('X-Milkglass-Source', 'R2');
          return c.json({
            status: 'ok',
            data: {
              total: doc.total,
              version: doc.version,
              synced_at: doc.synced_at,
              full_synced_at: doc.full_synced_at,
              age_sec: ageSec,
              stale,
              stale_after_sec: CATALOG_STALE_AFTER_SEC,
              recheck_after_sec: recheckAfterSec(nowSec, doc.synced_at),
            },
          });
        }
      }
    } catch {}
  }
  const db = readDb(c.env);
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
  edgeCache(7 * 86400, 0, true, { immutable: true, allowedQueryParams: ['v'] }),
  zValidatorQuery(z.object({ v: z.string().regex(/^\d+\.\d+$/).max(40).optional() })),
  async (c) => {
    const db = readDb(c.env);
    const { v } = c.req.valid('query');
    if (c.env.CATALOG_R2) {
      try {
        if (!v) {
          const head = await c.env.CATALOG_R2.head(R2_KEY_SNAPSHOT);
          const ver = head?.customMetadata?.version;
          if (ver) {
            const target = new URL(c.req.url);
            target.search = `?v=${encodeURIComponent(ver)}`;
            c.header('Cache-Control', 'no-store');
            c.header('X-Milkglass-Source', 'R2');
            return c.redirect(target.toString(), 307);
          }
        } else {
          const obj = await c.env.CATALOG_R2.get(R2_KEY_SNAPSHOT);
          const ver = obj?.customMetadata?.version;
          if (obj && ver) {
            if (ver === v) {
              return new Response(obj.body, {
                status: 200,
                headers: { 'Content-Type': 'application/json; charset=utf-8', 'X-Milkglass-Source': 'R2' },
              });
            }
            await obj.body?.cancel?.();
            const target = new URL(c.req.url);
            target.search = `?v=${encodeURIComponent(ver)}`;
            c.header('Cache-Control', 'no-store');
            c.header('X-Milkglass-Source', 'R2');
            return c.redirect(target.toString(), 307);
          }
          await obj?.body?.cancel?.();
        }
      } catch {}
    }
    const statements: D1PreparedStatement[] = [
      db.prepare(META_QUERY),
      db.prepare(`SELECT COUNT(*) AS total FROM animes`),
    ];
    if (v) {
      statements.push(
        db.prepare(`SELECT ${LIST_COLUMNS} FROM animes ORDER BY popularity DESC LIMIT 5000`)
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
      c.header('X-Milkglass-Source', 'D1');
      return c.redirect(target.toString(), 307);
    }
    if (total === 0) {
      c.header('X-Milkglass-NoCache', '1');
    }
    c.header('X-Milkglass-Source', 'D1');
    const results = (batchRes[2]?.results ?? []) as Array<Record<string, unknown>>;
    const shaped = shapeRows(results);
    return c.json({
      status: 'ok',
      data: shaped,
      meta: {
        version,
        synced_at: marker.synced_at,
        full_synced_at: marker.full_synced_at,
        count: shaped.length,
        total_in_db: total,
      },
    });
  }
);

const emptyToUndefined = (v: unknown) => {
  if (v === null || v === undefined) return undefined;
  if (typeof v === 'string') {
    const t = v.trim();
    return t === '' ? undefined : t;
  }
  return v;
};

const STATUS_NAME_MAP: Record<string, number> = {
  RELEASING: 1,
  FINISHED: 2,
  NOT_YET_RELEASED: 3,
  HIATUS: 4,
  CANCELLED: 5,
};

const statusPreprocess = (v: unknown) => {
  const cleaned = emptyToUndefined(v);
  if (typeof cleaned === 'string') {
    const mapped = STATUS_NAME_MAP[cleaned.toUpperCase()];
    if (mapped !== undefined) return mapped;
  }
  return cleaned;
};

function buildFtsQuery(raw: string): string {
  const tokens = raw
    .trim()
    .split(/[^a-zA-Z0-9]+/)
    .filter((tok) => tok.length > 0);
  const usable = tokens.length >= 2 ? tokens : tokens.filter((tok) => tok.length >= 2);
  return usable.slice(0, 8).map((tok) => `"${tok}"*`).join(' ');
}

const browseQuerySchema = z.object({
  q: z.string().trim().max(100).optional(),
  genre: z.string().trim().max(40).optional(),
  status: z.preprocess(statusPreprocess, z.coerce.number().int().min(1).max(5).optional()),
  schedule_day: z.preprocess(emptyToUndefined, z.coerce.number().int().min(0).max(7).optional()),
  page: z.preprocess(emptyToUndefined, z.coerce.number().int().min(1).max(100).default(1)),
});

catalogRoutes.get(
  '/browse',
  edgeCache(300, 600, true, { allowedQueryParams: ['q', 'genre', 'status', 'schedule_day', 'page'] }),
  rateLimit(40, 60),
  zValidatorQuery(browseQuerySchema),
  async (c) => {
    const { q, genre, status, schedule_day, page } = c.req.valid('query');
    const limit = 24;
    const offset = (page - 1) * limit;
    const conditions = ['1 = 1'];
    const params: Array<string | number> = [];

    if (q && q.length > 0) {
      const ftsQ = buildFtsQuery(q);
      if (ftsQ.length > 0) {
        params.push(ftsQ);
        conditions.push(`id IN (SELECT rowid FROM animes_fts WHERE animes_fts MATCH ?${params.length})`);
      } else {
        const escapedQ = escapeSqlLike(q);
        params.push(`%${escapedQ}%`);
        conditions.push(
          `(title LIKE ?${params.length} ESCAPE '\\' OR title_alt LIKE ?${params.length} ESCAPE '\\')`
        );
      }
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
    const { results } = await readDb(c.env).prepare(sql).bind(...params).all<Record<string, unknown>>();
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
  zValidatorParam(idParamSchema),
  async (c) => {
    const { id } = c.req.valid('param');
    const db = readDb(c.env);
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
  zValidatorParam(idParamSchema),
  async (c) => {
    const { id } = c.req.valid('param');
    const nowSec = Math.floor(Date.now() / 1000);
    const db = readDb(c.env);
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
  const rows = await readDb(c.env).prepare(
    'SELECT slug, anilist_id, title, romaji_title, episode, poster_url, banner_url, synopsis, updated_at FROM catalog_episodes ORDER BY updated_at DESC LIMIT 60'
  ).all();

  if (rows.results && rows.results.length > 0) {
    return c.json({ status: 'ok', items: rows.results });
  }

  const trending = await fetchTrendingAniList();
  return c.json({ status: 'ok', items: trending });
});

catalogRoutes.get('/sources', edgeCache(300, 600, false), async (c) => {
  const urls = await getSourceBaseUrls(readDb(c.env));
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

catalogRoutes.get(
  '/search',
  edgeCache(300, 600, true, { allowedQueryParams: ['q'] }),
  rateLimit(30, 60),
  async (c) => {
    const q = (c.req.query('q') || '').trim().slice(0, 100);
    if (q.length < 2) {
      return c.json({ status: 'ok', query: q, data: [], metadata: [], resolution_mode: 'CLIENT_CASCADE_MKV' });
    }

    const escapedQ = escapeSqlLike(q);
    const pattern = `%${escapedQ}%`;
    const ftsQuery = buildFtsQuery(q);
    const queryD1Search = async () => {
      if (ftsQuery.length > 0) {
        try {
          return await readDb(c.env).prepare(
            `SELECT ${LIST_COLUMNS.split(',').map((col) => 'a.' + col.trim()).join(', ')}
             FROM animes_fts
             JOIN animes a ON a.id = animes_fts.rowid
             WHERE animes_fts MATCH ?1
             ORDER BY (bm25(animes_fts, 2.0, 1.0) - (2.0 * CAST(a.popularity AS REAL) / (CAST(a.popularity AS REAL) + 50000.0))) ASC, a.popularity DESC
             LIMIT 24`
          )
            .bind(ftsQuery)
            .all<Record<string, unknown>>();
        } catch {
        }
      }
      return readDb(c.env).prepare(
        `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE title LIKE ?1 ESCAPE '\\' OR title_alt LIKE ?1 ESCAPE '\\'
         ORDER BY popularity DESC
         LIMIT 24`
      )
        .bind(pattern)
        .all<Record<string, unknown>>();
    };
    const d1Animes = await queryD1Search();
    const d1Rows = d1Animes.results ?? [];
    // Conditional AniList enrichment: skip slow external GraphQL round-trip when D1 already has >= 3 matches.
    const anilistItems = d1Rows.length >= 3 ? [] : await searchAniListMetadata(q);

    return c.json({
      status: 'ok',
      query: q,
      resolution_mode: 'CLIENT_CASCADE_MKV',
      data: shapeRows(d1Rows),
      metadata: anilistItems,
    });
  }
);

catalogRoutes.use('/play/*', rateLimit(20, 60));

catalogRoutes.get('/play/:slug', async (c) => {
  const slug = c.req.param('slug');
  const urls = await getSourceBaseUrls(readDb(c.env));
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

catalogRoutes.get('/rules', edgeCache(1800, 3600, false), async (c) => {
  const db = readDb(c.env);
  const [urls, metaRow] = await Promise.all([
    getSourceBaseUrls(db),
    db.prepare(`SELECT value FROM catalog_meta WHERE key = 'engine_rules_v1'`)
      .first<{ value: string }>()
      .catch(() => null),
  ]);

  let overrideSources: Record<string, { base_url?: string; back_api_url?: string; search_path?: string; selectors?: Record<string, string> }> = {};
  if (metaRow?.value) {
    try {
      const parsed = JSON.parse(metaRow.value);
      if (parsed && typeof parsed.sources === 'object') overrideSources = parsed.sources;
    } catch {}
  }

  const merge = (id: keyof typeof urls, defaults: { search_path: string; back_api_url?: string; selectors: Record<string, string> }) => {
    const ov = overrideSources[id];
    return {
      base_url: ov?.base_url || urls[id],
      ...(defaults.back_api_url || ov?.back_api_url ? { back_api_url: ov?.back_api_url || defaults.back_api_url } : {}),
      search_path: ov?.search_path || defaults.search_path,
      selectors: { ...defaults.selectors, ...(ov?.selectors ?? {}) },
    };
  };

  return c.json({
    schema_version: 1,
    updated_at: Math.floor(Date.now() / 1000),
    sources: {
      otakudesu: merge('otakudesu', DEFAULT_ENGINE_RULES.otakudesu),
      samehadaku: merge('samehadaku', DEFAULT_ENGINE_RULES.samehadaku),
      oploverz: merge('oploverz', DEFAULT_ENGINE_RULES.oploverz),
      kusonime: merge('kusonime', DEFAULT_ENGINE_RULES.kusonime),
    },
  });
});
