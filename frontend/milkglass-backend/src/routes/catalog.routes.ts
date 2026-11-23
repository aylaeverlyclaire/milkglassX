import { buildSourceCatalogList, parseSourceCascadeOrder, SOURCE_CASCADE_ORDER, TARGET_RESOLUTIONS } from '../utils/sources';
import { isCfHandlerConfigured } from '../utils/cfHandler';
import { Hono } from 'hono';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { zValidatorParam, zValidatorQuery } from '../middleware/validator';
import { recheckAfterSec } from '../services/sync-schedule';
import { getSourceBaseUrls } from '../services/anilist.service';
import { AppBindings } from '../index';
import { readDb } from '../utils/db';


const ALLOWED_CATALOG_PROVIDERS = new Set(['ylnime', 'otakudesu']);
const CATALOG_SOURCE_FILTER = `(has_live_source = 1 AND (source_urls_json LIKE '%"ylnime"%' OR source_urls_json LIKE '%"otakudesu"%'))`;
const CATALOG_SOURCE_FILTER_A = `(a.has_live_source = 1 AND (a.source_urls_json LIKE '%"ylnime"%' OR a.source_urls_json LIKE '%"otakudesu"%'))`;

const DEFAULT_ENGINE_RULES = {
  otakudesu: { search_path: '/?s={q}&post_type=anime', selectors: { search_item: 'ul.chivsrc > li', search_link: 'h2 > a[href]', episode_box: 'div.episodelist', episode_link: 'ul li a[href]', download_block: 'div.download ul', download_row: 'li', quality_text: 'strong', size_text: 'i', hoster_link: 'a[href]', ajax_nonce_action: 'aa1208d27f29ca340c92c66d1926f13f', ajax_mirror_action: '2a3505c93b0035d3f455df82bf976b84' } },
  ylnime: { search_path: '/index.php?search={q}', selectors: { search_item: "a[href*='?series=']", series_link: "a[href*='?series=']", episode_link: "a[href*='episode=']", video_source: 'video source[src], video[src]', hoster_link: "a[href*='pixeldrain.']", player_iframe: 'iframe[src]' } },
};

export const catalogRoutes = new Hono<{ Bindings: AppBindings }>();

const CATALOG_STALE_AFTER_SEC = 18 * 3600;
const LIST_COLUMNS = `id, mal_id, title, title_alt, score, type, status, schedule_day,
                duration, studio, release_season, poster_url, popularity, updated_at,
                genres_json, episodes_total, next_airing_at, next_airing_ep, source_urls_json`;

const META_QUERY = `SELECT key, value FROM catalog_meta WHERE key IN ('last_import_at', 'last_full_import_at')`;

function shapeAnimeRow(
  row: Record<string, unknown>
): Record<string, unknown> & { genres: string[]; source_urls: Record<string, string> } {
  const { genres_json, source_urls_json, ...rest } = row;
  let genres: string[] = [];
  try {
    const parsed = JSON.parse(String(genres_json || '[]'));
    if (Array.isArray(parsed)) {
      genres = parsed.filter((g): g is string => typeof g === 'string');
    }
  } catch {
    genres = [];
  }
  const source_urls: Record<string, string> = {};
  if (typeof source_urls_json === 'string' && source_urls_json.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(source_urls_json) as Record<string, unknown>;
      if (parsed && typeof parsed === 'object') {
        for (const [k, v] of Object.entries(parsed)) {
          const key = k.trim().toLowerCase();
          if (ALLOWED_CATALOG_PROVIDERS.has(key) && typeof v === 'string' && v.startsWith('https://')) {
            source_urls[key] = v;
          }
        }
      }
    } catch {}
  }
  return { ...rest, genres, source_urls };
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
  const db = readDb(c.env);
  const [ongoingRes, popularRes, completedRes, metaRes] = await db.batch([
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE ${CATALOG_SOURCE_FILTER} AND status = 1
         ORDER BY popularity DESC, updated_at DESC
         LIMIT 20`
    ),
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE ${CATALOG_SOURCE_FILTER}
         ORDER BY popularity DESC, updated_at DESC
         LIMIT 20`
    ),
    db.prepare(
      `SELECT ${LIST_COLUMNS}
         FROM animes
         WHERE ${CATALOG_SOURCE_FILTER} AND status = 2
         ORDER BY popularity DESC, updated_at DESC
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
  const db = readDb(c.env);
  const [metaRes, countRes, totalAllRes, hosterRes] = await db.batch([
    db.prepare(META_QUERY),
    db.prepare(`SELECT COUNT(*) AS total FROM animes WHERE ${CATALOG_SOURCE_FILTER}`),
    db.prepare(`SELECT COUNT(*) AS total_all FROM animes`),
    db.prepare(`SELECT COUNT(*) AS live_hosters FROM episode_hosters WHERE is_live = 1`),
  ]);

  const marker = readSyncMarker(metaRes.results as Array<{ key: string; value: string }>);
  const total = Number((countRes.results?.[0] as { total?: number } | undefined)?.total ?? 0);
  const totalCatalog = Number((totalAllRes.results?.[0] as { total_all?: number } | undefined)?.total_all ?? 0);
  const liveHosters = Number((hosterRes.results?.[0] as { live_hosters?: number } | undefined)?.live_hosters ?? 0);
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
      catalog_sources: ['otakudesu', 'ylnime'],
      total_catalog: totalCatalog,
      live_hosters: liveHosters,
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
    const statements: D1PreparedStatement[] = [
      db.prepare(META_QUERY),
      db.prepare(`SELECT COUNT(*) AS total FROM animes WHERE ${CATALOG_SOURCE_FILTER}`),
    ];
    if (v) {
      statements.push(
        db.prepare(`SELECT ${LIST_COLUMNS} FROM animes WHERE ${CATALOG_SOURCE_FILTER} ORDER BY popularity DESC, updated_at DESC LIMIT 8000`)
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
    const conditions = [CATALOG_SOURCE_FILTER];
    const params: Array<string | number> = [];

    if (q && q.length > 0) {
      const ftsQ = buildFtsQuery(q);
      const escapedQ = escapeSqlLike(q);
      if (ftsQ.length > 0) {
        params.push(ftsQ);
        const ftsParamIdx = params.length;
        params.push(`%${escapedQ}%`);
        const likeParamIdx = params.length;
        conditions.push(
          `(id IN (SELECT rowid FROM animes_fts WHERE animes_fts MATCH ?${ftsParamIdx}) OR synonyms_json LIKE ?${likeParamIdx} ESCAPE '\\')`
        );
      } else {
        params.push(`%${escapedQ}%`);
        conditions.push(
          `(title LIKE ?${params.length} ESCAPE '\\' OR title_alt LIKE ?${params.length} ESCAPE '\\' OR synonyms_json LIKE ?${params.length} ESCAPE '\\')`
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
      ORDER BY popularity DESC, updated_at DESC
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
      db.prepare(`SELECT * FROM animes WHERE id = ?1 AND ${CATALOG_SOURCE_FILTER}`).bind(id),
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
  const rows = await readDb(c.env)
    .prepare(
      `SELECT ${LIST_COLUMNS} FROM animes WHERE ${CATALOG_SOURCE_FILTER} ORDER BY updated_at DESC LIMIT 60`
    )
    .all<Record<string, unknown>>();

  return c.json({ status: 'ok', items: shapeRows(rows.results) });
});

catalogRoutes.get('/sources', edgeCache(300, 600, false), async (c) => {
  const rdb = readDb(c.env);
  const [urls, orderRow] = await Promise.all([
    getSourceBaseUrls(rdb),
    rdb
      .prepare("SELECT value FROM app_config WHERE key = 'source_cascade_order' LIMIT 1")
      .first<{ value?: string }>()
      .catch(() => null),
  ]);
  const order = parseSourceCascadeOrder(orderRow?.value);
  return c.json({
    status: 'ok',
    resolution_mode: 'CLIENT_CASCADE_MKV',
    target_format: 'MKV',
    target_resolutions: [...TARGET_RESOLUTIONS],
    cf_handler_available: true,
    cf_handler_mode: isCfHandlerConfigured(c.env) ? 'hybrid' : 'on_device',
    remote_cf_handler_available: isCfHandlerConfigured(c.env),
    sources: buildSourceCatalogList(urls, order),
  });
});

const CJK_CHAR_RE = /[\u3040-\u30FF\u4E00-\u9FFF]/;

function normalizeSearchSpaced(raw: string): string {
  return raw
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[\u00d7\u2715\u2716\u2717]/g, 'x')
    .replace(/[^a-z0-9\u3040-\u30ff\u4e00-\u9fff]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');
}

function damerauLevenshtein(a: string, b: string, maxDist: number): number {
  const m = a.length;
  const n = b.length;
  if (Math.abs(m - n) > maxDist) return maxDist + 1;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    let rowMin = maxDist + 1;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let val = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        val = Math.min(val, dp[i - 2][j - 2] + 1);
      }
      dp[i][j] = val;
      if (val < rowMin) rowMin = val;
    }
    if (rowMin > maxDist) return maxDist + 1;
  }
  return dp[m][n];
}

function maxTypoForToken(tok: string): number {
  if (/^\d+$/.test(tok) || tok.length <= 3) return 0;
  if (tok.length <= 6) return 1;
  return 2;
}

function scoreMultilingualRow(qSpaced: string, qCompact: string, row: Record<string, unknown>): number {
  const fields: string[] = [
    String(row.title || ''),
    String(row.title_alt || ''),
    String(row.title_native || ''),
  ];
  if (typeof row.synonyms_json === 'string' && row.synonyms_json.startsWith('[')) {
    try {
      const arr = JSON.parse(row.synonyms_json);
      if (Array.isArray(arr)) {
        for (const s of arr) if (typeof s === 'string') fields.push(s);
      }
    } catch {}
  }

  let best = 0;
  const qTokens = qSpaced.split(' ').filter(Boolean);
  for (const rawField of fields) {
    if (!rawField) continue;
    const fSpaced = normalizeSearchSpaced(rawField);
    const fCompact = fSpaced.replace(/\s+/g, '');
    if (!fCompact) continue;

    if (fCompact === qCompact) return 100;
    if (fCompact.startsWith(qCompact) || fSpaced.startsWith(qSpaced)) {
      best = Math.max(best, 85);
      continue;
    }
    if (fCompact.includes(qCompact) || fSpaced.includes(qSpaced)) {
      best = Math.max(best, 72);
      continue;
    }

    const fTokens = fSpaced.split(' ').filter(Boolean);
    if (qTokens.length > 0 && fTokens.length > 0) {
      let matched = 0;
      for (const qt of qTokens) {
        const allow = maxTypoForToken(qt);
        const hit = fTokens.some((ft) => {
          if (ft === qt || (qt.length >= 3 && ft.startsWith(qt))) return true;
          if (allow === 0) return false;
          return damerauLevenshtein(qt, ft, allow) <= allow;
        });
        if (hit) matched++;
      }
      if (matched === qTokens.length) {
        best = Math.max(best, 58);
      } else if (matched > 0 && matched / qTokens.length >= 0.6) {
        best = Math.max(best, 35);
      }
    }
  }
  return best;
}

catalogRoutes.get(
  '/search',
  edgeCache(300, 600, true, { allowedQueryParams: ['q'], normalizeTitleParam: true }),
  rateLimit(30, 60),
  async (c) => {
    const SEARCH_ALIASES: Record<string, string> = {
      tensura: 'tensei shitara slime',
      slime: 'tensei shitara slime',
      jjk: 'jujutsu kaisen',
      aot: 'shingeki no kyojin',
      snk: 'shingeki no kyojin',
      kny: 'kimetsu no yaiba',
      danmachi: 'dungeon ni deai',
      mha: 'boku no hero academia',
      bnha: 'boku no hero academia',
      sao: 'sword art online',
      cote: 'youkoso jitsuryoku',
      konosuba: 'kono subarashii',
      oregairu: 'yahari ore no seishun',
      frieren: 'sousou no frieren',
    };
    const rawQ = (c.req.query('q') || '').trim().slice(0, 80);
    const rawCompact = normalizeSearchSpaced(rawQ).replace(/\s+/g, '');
    const q = SEARCH_ALIASES[rawCompact] ?? rawQ;
    const qSpaced = normalizeSearchSpaced(q);
    const qCompact = qSpaced.replace(/\s+/g, '');
    const minLen = CJK_CHAR_RE.test(qCompact) ? 1 : 2;
    if (qCompact.length < minLen) {
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
             WHERE animes_fts MATCH ?1 AND ${CATALOG_SOURCE_FILTER_A}
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
         WHERE ${CATALOG_SOURCE_FILTER} AND (title LIKE ?1 ESCAPE '\\' OR title_alt LIKE ?1 ESCAPE '\\')
         ORDER BY popularity DESC, updated_at DESC
         LIMIT 24`
      )
        .bind(pattern)
        .all<Record<string, unknown>>();
    };

    const scrapeSourceSearch = async (
      sourceId: 'ylnime' | 'otakudesu',
      baseUrl: string
    ): Promise<Array<Record<string, unknown>>> => {
      const root = baseUrl.trim().replace(/\/+$/, '');
      if (!root.startsWith('https://')) return [];
      try {
        const searchPath = sourceId === 'ylnime' ? `/index.php?search=${encodeURIComponent(q)}` : `/?s=${encodeURIComponent(q)}&post_type=anime`;
        const resp = await fetch(`${root}${searchPath}`, {
          method: 'GET',
          headers: {
            Accept: 'text/html,application/xhtml+xml,*/*',
            'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8',
            Referer: `${root}/`,
            'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
          },
          signal: AbortSignal.timeout(2_200),
        });
        if (!resp.ok) return [];
        const html = await resp.text();
        const items: Array<Record<string, unknown>> = [];
        const seenUrls = new Set<string>();
        const cleanLiveTitle = (raw: string) =>
          raw
            .replace(/<[^>]+>/g, ' ')
            .replace(/&amp;/gi, '&')
            .replace(/&#8211;|&ndash;/gi, '-')
            .replace(/&#8217;|&#039;/gi, "'")
            .replace(/^\s*\((?:HD|Uncen|Uncensored|BD|Sub Indo)\)\s*/i, '')
            .replace(/\(Episode[^)]*\)/gi, '')
            .replace(/\b(?:Subtitle Indonesia|Sub Indo|BD)\b/gi, '')
            .replace(/\s+/g, ' ')
            .trim();
        const re =
          sourceId === 'otakudesu'
            ? /<h2[^>]*>\s*<a[^>]+href=(["'])(https?:\/\/[^"']+\/anime\/[^"']+)\1[^>]*>([\s\S]*?)<\/a>/gi
            : /<a\b[^>]*\bhref=(["'])([^"']+)\1[^>]*>([\s\S]*?)<\/a>/gi;
        let m: RegExpExecArray | null;
        while ((m = re.exec(html)) !== null && items.length < 12) {
          const rawHref = m[2].trim().replace(/&amp;/g, '&');
          if (sourceId === 'ylnime' && (!/[?&]series=[^&#]+/i.test(rawHref) || /[?&]episode=/i.test(rawHref))) {
            continue;
          }
          const url = rawHref.startsWith('http')
            ? rawHref
            : rawHref.startsWith('?')
              ? `${root}/${rawHref}`
              : `${root}/${rawHref.replace(/^\/+/, '')}`;
          let rawTitle = cleanLiveTitle(m[3]);
          let posterUrl = '';
          const beforeSlice = html.slice(Math.max(0, m.index - 450), m.index);
          const afterSlice = html.slice(m.index + m[0].length, m.index + m[0].length + 450);
          const isGenericTitle = (t: string) =>
            /^(?:cover|poster|thumbnail|thumb|image|img|logo|banner|watch|tonton|nonton|detail)$/i.test(t.trim());
          if (sourceId === 'ylnime') {
            if (rawTitle.length < 2 || isGenericTitle(rawTitle)) {
              // Kartu Bootstrap YLnime memakai <a href="index.php?series=..." class="stretched-link"></a> kosong
              const cardTitleMatch = afterSlice.match(
                /<(?:h[1-6]|div|span)[^>]*class=(["'])[^"']*card-title[^"']*\1[^>]*>([\s\S]*?)<\/(?:h[1-6]|div|span)>/i
              );
              const imgAlts = [...beforeSlice.matchAll(/<img\b[^>]*\balt=(["'])([^"']+)\1/gi)]
                .map((x) => cleanLiveTitle(x[2]))
                .filter((t) => t.length >= 2 && !isGenericTitle(t));
              const nearestAlt = imgAlts.length > 0 ? imgAlts[imgAlts.length - 1] : '';
              rawTitle = cleanLiveTitle(cardTitleMatch?.[2] || nearestAlt || '');
            }
            const imgSrcs = [...beforeSlice.matchAll(/<img\b[^>]*\bsrc=(["'])(https?:\/\/[^"']+)\1/gi)];
            const nearestSrc = imgSrcs.length > 0 ? imgSrcs[imgSrcs.length - 1][2] : '';
            if (nearestSrc.startsWith('https://')) posterUrl = nearestSrc;
          } else {
            const imgSrcs = [...beforeSlice.matchAll(/<img\b[^>]*\bsrc=(["'])(https?:\/\/[^"']+)\1/gi)];
            const nearestSrc = imgSrcs.length > 0 ? imgSrcs[imgSrcs.length - 1][2] : '';
            if (nearestSrc.startsWith('https://')) posterUrl = nearestSrc;
          }
          const dedupKey =
            sourceId === 'ylnime'
              ? (url.match(/[?&]series=([^&#]+)/i)?.[1]?.toLowerCase() || url)
              : url;
          if (
            !url ||
            rawTitle.length < 2 ||
            isGenericTitle(rawTitle) ||
            /^\[?info\]?/i.test(rawTitle) ||
            /^\(live\s*action\)/i.test(rawTitle) ||
            seenUrls.has(dedupKey)
          ) {
            continue;
          }
          seenUrls.add(dedupKey);
          let hash = 2166136261;
          const seed = `${sourceId}:${url.toLowerCase()}`;
          for (let i = 0; i < seed.length; i++) {
            hash ^= seed.charCodeAt(i);
            hash = Math.imul(hash, 16777619);
          }
          const stableId = 900_000_000 + ((hash >>> 0) % 999_999_999);
          items.push({
            id: stableId,
            mal_id: null,
            source: sourceId,
            source_url: url,
            source_urls: { [sourceId]: url },
            title: rawTitle,
            title_alt: rawTitle,
            score: 0,
            type: 'TV',
            status: 1,
            schedule_day: 0,
            duration: '24 min',
            studio: sourceId === 'ylnime' ? 'YLnime' : 'Otakudesu',
            release_season: '-',
            poster_url: posterUrl,
            popularity: 0,
            updated_at: Math.floor(Date.now() / 1000),
            genres: [],
            episodes_total: 12,
            next_airing_at: 0,
            next_airing_ep: 0,
          });
        }
        return items;
      } catch {
        return [];
      }
    };

    const rdb = readDb(c.env);
    const [d1Animes, sourceUrls] = await Promise.all([
      queryD1Search(),
      getSourceBaseUrls(rdb),
    ]);
    let d1Rows = d1Animes.results ?? [];
    if (d1Rows.length < 12) {
      const qTokensForPool = qSpaced.split(' ').filter((t) => t.length >= 3).slice(0, 4);
      let poolRows: Array<Record<string, unknown>> = [];
      if (qTokensForPool.length > 0) {
        const tokenConds = qTokensForPool.map((_, idx) => `(title LIKE ?${idx + 1} ESCAPE '\\' OR title_alt LIKE ?${idx + 1} ESCAPE '\\')`).join(' OR ');
        const tokenBinds = qTokensForPool.map((t) => `%${escapeSqlLike(t)}%`);
        const { results: tokPool } = await rdb
          .prepare(
            `SELECT ${LIST_COLUMNS}, title_native, synonyms_json
             FROM animes
             WHERE ${CATALOG_SOURCE_FILTER} AND (${tokenConds})
             ORDER BY popularity DESC, updated_at DESC
             LIMIT 300`
          )
          .bind(...tokenBinds)
          .all<Record<string, unknown>>();
        poolRows = tokPool ?? [];
      }
      const { results: topPool } = await rdb
        .prepare(
          `SELECT ${LIST_COLUMNS}, title_native, synonyms_json
           FROM animes
           WHERE ${CATALOG_SOURCE_FILTER}
           ORDER BY popularity DESC, updated_at DESC
           LIMIT 1000`
        )
        .all<Record<string, unknown>>();
      const fullPool = [...poolRows, ...(topPool ?? [])];
      const seenIds = new Set(d1Rows.map((r) => r.id));
      const scored = (fullPool ?? [])
        .filter((r) => !seenIds.has(r.id))
        .map((r) => ({ row: r, score: scoreMultilingualRow(qSpaced, qCompact, r) }))
        .filter((x) => x.score > 0)
        .sort((a, b) => b.score - a.score || Number(b.row.popularity || 0) - Number(a.row.popularity || 0))
        .slice(0, 24 - d1Rows.length)
        .map((x) => x.row);
      d1Rows = [...d1Rows, ...scored];
    }

    // D1-first (<50ms): jika D1 sudah mengembalikan >= 6 hasil relevan, lewati live-scrape eksternal
    // agar hemat kuota Worker subrequest dan menghindari rate-limit IP situs sumber.
    let liveRows: Array<Record<string, unknown>> = [];
    if (d1Rows.length < 6) {
      const [otakuLive, sameLive] = await Promise.allSettled([
        scrapeSourceSearch('otakudesu', sourceUrls.otakudesu),
        scrapeSourceSearch('ylnime', sourceUrls.ylnime),
      ]);
      liveRows = [
        ...(otakuLive.status === 'fulfilled' ? otakuLive.value : []),
        ...(sameLive.status === 'fulfilled' ? sameLive.value : []),
      ];
    }

    const shapedD1 = shapeRows(d1Rows);
    const merged: Array<Record<string, unknown>> = [];
    const seenCompactKeys = new Set<string>();
    const canonicalSearchKey = (raw: unknown): string => {
      const str = String(raw || '').replace(/\+\s*(?:ova|oad|special)\b/gi, ' ');
      const norm = normalizeSearchSpaced(str);
      if (!norm) return '';
      const sm = norm.match(/\bseason\s*(\d+)\b/) || norm.match(/\bs(\d+)\b/) || norm.match(/\b(\d+)(?:st|nd|rd|th)\s+season\b/);
      const roman = str.match(/\b(II|III|IV|V)\b/);
      const season = sm ? Number(sm[1]) : roman ? ({ II: 2, III: 3, IV: 4, V: 5 }[roman[1]] ?? 1) : 1;
      const pm = norm.match(/\b(?:part|cour)\s*(\d+)\b/);
      const part = pm ? Number(pm[1]) : 0;
      const base = norm
        .replace(/\bseason\s*\d+\b/g, ' ')
        .replace(/\bs\d+\b/g, ' ')
        .replace(/\b\d+(?:st|nd|rd|th)\s+season\b/g, ' ')
        .replace(/\b(?:ii|iii|iv|v)\b/g, ' ')
        .replace(/\b(?:part|cour)\s*\d+\b/g, ' ')
        .replace(/\s+/g, '');
      return base ? `${base}|s${season}|p${part}` : '';
    };
    for (const row of [...shapedD1, ...liveRows]) {
      const k = canonicalSearchKey(row.title);
      const ka = canonicalSearchKey(row.title_alt);
      if ((k && seenCompactKeys.has(k)) || (ka && seenCompactKeys.has(ka))) continue;
      if (k) seenCompactKeys.add(k);
      if (ka) seenCompactKeys.add(ka);
      merged.push(row);
      if (merged.length >= 30) break;
    }

    return c.json({
      status: 'ok',
      query: q,
      search_source: liveRows.length > 0 ? 'd1_and_live_sources' : 'd1_primary',
      resolution_mode: 'CLIENT_CASCADE_MKV',
      data: merged,
      metadata: [],
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
    resolutions: [...TARGET_RESOLUTIONS],
    source_cascade: [...SOURCE_CASCADE_ORDER],
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
      ylnime: merge('ylnime', DEFAULT_ENGINE_RULES.ylnime),
      otakudesu: merge('otakudesu', DEFAULT_ENGINE_RULES.otakudesu),
    },
  });
});
