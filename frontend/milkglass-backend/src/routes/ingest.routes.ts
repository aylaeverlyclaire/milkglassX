import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { fetchAniSkipTimes } from '../services/aniskip.service';
import {
  enrichSingleAnimeFromAniListAsync,
  importAniListMedia,
  ensureAnimeExistsFastInD1,
  syncAniListCatalogDetailed,
} from '../services/anilist.service';
import {
  purgeEdgeCacheForIngest,
  purgeHomeCache,
  purgeSourcesCache,
} from '../middleware/edgeCache';
import { AppBindings } from '../index';
import { SOURCE_ID_SCHEMA } from '../utils/sources';

export const ingestRoutes = new Hono<{ Bindings: AppBindings }>();

const FORBIDDEN_DEFAULT_SECRETS = new Set([
  'dev-secret-key-min-16-chars',
  'changeme',
  'your_admin_secret_here',
]);

ingestRoutes.use('*', async (c, next) => {
  const providedSecret = c.req.header('X-Admin-Secret') || '';
  const expectedSecret = c.env.ADMIN_SECRET || '';

  if (
    !expectedSecret ||
    expectedSecret.length < 16 ||
    FORBIDDEN_DEFAULT_SECRETS.has(expectedSecret.trim())
  ) {
    return c.json({ status: 'error', message: 'Forbidden' }, 403);
  }

  const enc = new TextEncoder();
  const [hashA, hashB] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(providedSecret)),
    crypto.subtle.digest('SHA-256', enc.encode(expectedSecret)),
  ]);

  const a = new Uint8Array(hashA);
  const b = new Uint8Array(hashB);
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  if (diff !== 0 || providedSecret.length === 0) {
    return c.json({ status: 'error', message: 'Forbidden' }, 403);
  }

  await next();
});

export function isSafeHttpsUrlForType(
  rawUrl: string,
  type: 'pdrain' | 'kfiles' | 'direct'
): boolean {
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'https:') return false;
    const host = u.hostname.toLowerCase();

    if (
      host === 'localhost' ||
      host.endsWith('.local') ||
      host.includes(':') ||
      /^(127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/.test(host)
    ) {
      return false;
    }

    if (type === 'pdrain') {
      return host === 'pixeldrain.com' || host.endsWith('.pixeldrain.com');
    }
    if (type === 'kfiles') {
      return host === 'krakenfiles.com' || host.endsWith('.krakenfiles.com');
    }
    return true;
  } catch {
    return false;
  }
}

const streamSourceInputSchema = z
  .object({
    type: z.enum(['pdrain', 'kfiles', 'direct']),
    quality: z.number().int().min(1).max(4),
    url_payload: z.string().url().max(2048),
    release_group: z.string().trim().min(1).max(64).default('default'),
    sub_kind: z.string().trim().min(1).max(32).default('hardsub-id'),
    duration_sec: z.number().int().nonnegative().max(86400).default(1440),
    file_size_bytes: z.number().int().nonnegative().default(0),
  })
  .refine((s) => isSafeHttpsUrlForType(s.url_payload, s.type), {
    message: 'url_payload wajib https:// publik yang sesuai dengan domain hoster.',
  });

const ingestEpisodeBodySchema = z
  .object({
    anime_id: z.number().int().positive(),
    anime_title: z.string().optional(),
    mal_id: z.number().int().positive().optional(),
    episode_number: z.number().int().positive(),
    name: z.string().min(1).max(255),
    op_start: z.number().int().nonnegative().nullable().optional(),
    op_end: z.number().int().nonnegative().nullable().optional(),
    ed_start: z.number().int().nonnegative().nullable().optional(),
    ed_end: z.number().int().nonnegative().nullable().optional(),
    sources: z.array(streamSourceInputSchema).min(1).max(30),
  })
  .refine(
    (d) =>
      (d.op_start == null && d.op_end == null) ||
      (d.op_start != null && d.op_end != null && d.op_end > d.op_start),
    { message: 'op_end harus lebih besar dari op_start.' }
  )
  .refine(
    (d) =>
      (d.ed_start == null && d.ed_end == null) ||
      (d.ed_start != null && d.ed_end != null && d.ed_end > d.ed_start),
    { message: 'ed_end harus lebih besar dari ed_start.' }
  );

ingestRoutes.post('/ingest', zValidator('json', ingestEpisodeBodySchema), async (c) => {
  const payload = c.req.valid('json');
  const db = c.env.DB;
  const nowSec = Math.floor(Date.now() / 1000);

  const ensured = await ensureAnimeExistsFastInD1(
    db,
    payload.anime_id,
    payload.mal_id,
    payload.anime_title || `Anime #${payload.anime_id}`
  );

  const epRow = await db
    .prepare(
      `INSERT INTO episodes (
         anime_id, episode_number, name, op_start, op_end, ed_start, ed_end, created_at, updated_at
       ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8)
       ON CONFLICT(anime_id, episode_number) DO UPDATE SET
         name = excluded.name,
         op_start = COALESCE(excluded.op_start, episodes.op_start),
         op_end = COALESCE(excluded.op_end, episodes.op_end),
         ed_start = COALESCE(excluded.ed_start, episodes.ed_start),
         ed_end = COALESCE(excluded.ed_end, episodes.ed_end),
         updated_at = excluded.updated_at
       RETURNING id, op_start, op_end, ed_start, ed_end`
    )
    .bind(
      payload.anime_id,
      payload.episode_number,
      payload.name,
      payload.op_start ?? null,
      payload.op_end ?? null,
      payload.ed_start ?? null,
      payload.ed_end ?? null,
      nowSec
    )
    .first<{
      id: number;
      op_start: number | null;
      op_end: number | null;
      ed_start: number | null;
      ed_end: number | null;
    }>();

  if (!epRow) {
    return c.json({ status: 'error', message: 'Gagal menyimpan episode.' }, 500);
  }

  const dedupedSourcesMap = new Map<string, z.infer<typeof streamSourceInputSchema>>();
  for (const src of payload.sources) {
    const key = `${src.type}:${src.quality}:${src.release_group}`;
    dedupedSourcesMap.set(key, src);
  }
  const uniqueSources = Array.from(dedupedSourcesMap.values());

  const statements: D1PreparedStatement[] = [
    db.prepare(`UPDATE stream_sources SET is_active = 0 WHERE episode_id = ?1`).bind(epRow.id),
  ];

  for (const src of uniqueSources) {
    statements.push(
      db
        .prepare(
          `INSERT INTO stream_sources (
             episode_id, type, quality, url_payload, release_group,
             sub_kind, duration_sec, file_size_bytes, is_active
           ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 1)
           ON CONFLICT(episode_id, type, quality, release_group) DO UPDATE SET
             fail_count = CASE WHEN stream_sources.url_payload != excluded.url_payload THEN 0 ELSE stream_sources.fail_count END,
             cooldown_until = CASE WHEN stream_sources.url_payload != excluded.url_payload THEN 0 ELSE stream_sources.cooldown_until END,
             url_payload = excluded.url_payload,
             sub_kind = excluded.sub_kind,
             duration_sec = excluded.duration_sec,
             file_size_bytes = excluded.file_size_bytes,
             is_active = 1`
        )
        .bind(
          epRow.id,
          src.type,
          src.quality,
          src.url_payload,
          src.release_group,
          src.sub_kind,
          src.duration_sec,
          src.file_size_bytes
        )
    );
  }

  await db.batch(statements);

  c.executionCtx.waitUntil(
    (async () => {
      if (ensured.wasCreatedPlaceholder) {
        await enrichSingleAnimeFromAniListAsync(db, payload.anime_id);
      }

      if (epRow.op_start === null && epRow.ed_start === null) {
        const malId = payload.mal_id ?? ensured.malId;
        if (malId) {
          const durationHint = uniqueSources[0]?.duration_sec ?? 0;
          const skip = await fetchAniSkipTimes(malId, payload.episode_number, durationHint);
          if (skip.opStart !== null || skip.edStart !== null) {
            await db
              .prepare(
                `UPDATE episodes
                 SET op_start = COALESCE(op_start, ?1),
                     op_end = COALESCE(op_end, ?2),
                     ed_start = COALESCE(ed_start, ?3),
                     ed_end = COALESCE(ed_end, ?4)
                 WHERE id = ?5`
              )
              .bind(skip.opStart, skip.opEnd, skip.edStart, skip.edEnd, epRow.id)
              .run();
          }
        }
      }

      await purgeEdgeCacheForIngest(c.req.url, payload.anime_id, epRow.id);
    })()
  );

  return c.json({
    status: 'ok',
    data: {
      episode_id: epRow.id,
      sources_count: uniqueSources.length,
      skip_times: {
        opStart: epRow.op_start,
        opEnd: epRow.op_end,
        edStart: epRow.ed_start,
        edEnd: epRow.ed_end,
      },
    },
  });
});

ingestRoutes.post('/sync-anilist', async (c) => {
  const { synced, log } = await syncAniListCatalogDetailed(c.env.DB, 1, 50, 3);
  return c.json({ status: 'ok', synced_count: synced, pages: log });
});

ingestRoutes.post('/catalog-import', async (c) => {
  const body = (await c.req.json().catch(() => null)) as {
    media?: unknown;
    scope?: unknown;
  } | null;
  if (!body || !Array.isArray(body.media) || body.media.length === 0 || body.media.length > 100) {
    return c.json({ status: 'error', message: 'media harus array 1-100 item.' }, 400);
  }
  if (body.scope !== undefined && body.scope !== 'ongoing' && body.scope !== 'full') {
    return c.json({ status: 'error', message: "scope harus 'ongoing' atau 'full'." }, 400);
  }
  const imported = await importAniListMedia(
    c.env.DB,
    body.media as Parameters<typeof importAniListMedia>[1],
    body.scope
  );
  await purgeHomeCache(c.req.url);
  return c.json({ status: 'ok', imported });
});

const sourceDomainSchema = z.object({
  source: SOURCE_ID_SCHEMA.default('animasu'),
  base_url: z.string().url().refine((u) => u.startsWith('https://'), {
    message: 'Harus HTTPS',
  }),
});

ingestRoutes.post('/source-domain', zValidator('json', sourceDomainSchema), async (c) => {
  const { source, base_url } = c.req.valid('json');
  const key = `${source}_base_url`;
  const cleanUrl = base_url.trim().replace(/\/+$/, '');
  const now = Math.floor(Date.now() / 1000);
  await c.env.DB.prepare(
    `INSERT INTO catalog_meta (key, value, updated_at)
     VALUES (?1, ?2, ?3)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  )
    .bind(key, cleanUrl, now)
    .run();
  await purgeSourcesCache(c.req.url);
  return c.json({ status: 'ok', source, key, base_url: cleanUrl });
});


function normalizeHosterTitleKey(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ').slice(0, 120);
}

ingestRoutes.get('/catalog-list', async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT id, title, title_alt, status, popularity, episodes_total, has_live_source
     FROM animes
     ORDER BY popularity DESC
     LIMIT 5000`
  ).all<Record<string, unknown>>();
  return c.json({ status: 'ok', count: (results ?? []).length, data: results ?? [] });
});

const syncHostersSchema = z.object({
  items: z
    .array(
      z.object({
        anime_id: z.number().int().positive(),
        title: z.string().trim().min(1).max(200),
        title_alt: z.string().trim().max(200).nullable().optional(),
        episodes: z
          .array(
            z.object({
              ep: z.number().int().min(1).max(2000),
              hosters: z
                .array(
                  z.object({
                    url: z.string().trim().url().max(2048),
                    quality: z.string().trim().min(2).max(16).default('720p'),
                    hoster: z.string().trim().min(1).max(60).default('Hoster'),
                    provider: z.string().trim().min(1).max(32).default('otakudesu'),
                    container: z.string().trim().min(2).max(12).default('mp4'),
                    referer: z.string().trim().max(1024).default(''),
                  })
                )
                .min(1)
                .max(20),
            })
          )
          .min(1)
          .max(200),
      })
    )
    .max(50)
    .default([]),
  reconcile: z.boolean().optional().default(false),
});

ingestRoutes.post('/sync-hosters', zValidator('json', syncHostersSchema), async (c) => {
  const { items, reconcile } = c.req.valid('json');
  const now = Math.floor(Date.now() / 1000);
  let upserted = 0;
  const touchedAnimeIds = new Set<number>();

  const stmts: D1PreparedStatement[] = [];
  for (const item of items) {
    const tKey = normalizeHosterTitleKey(item.title);
    const tAltKey = item.title_alt ? normalizeHosterTitleKey(item.title_alt) : null;
    for (const epEntry of item.episodes) {
      for (const h of epEntry.hosters) {
        if (!h.url.startsWith('https://') && !h.url.startsWith('http://')) continue;
        stmts.push(
          c.env.DB.prepare(
            `INSERT INTO episode_hosters
               (anime_id, episode_number, title_key, title_alt_key, url, quality, hoster, provider, container, referer, is_live, last_verified_at, fail_count)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 1, ?11, 0)
             ON CONFLICT(anime_id, episode_number, url) DO UPDATE SET
               title_key = excluded.title_key,
               title_alt_key = excluded.title_alt_key,
               quality = excluded.quality,
               hoster = excluded.hoster,
               provider = excluded.provider,
               container = excluded.container,
               referer = excluded.referer,
               is_live = 1,
               last_verified_at = excluded.last_verified_at,
               fail_count = 0`
          ).bind(
            item.anime_id,
            epEntry.ep,
            tKey,
            tAltKey,
            h.url,
            h.quality,
            h.hoster,
            h.provider,
            h.container,
            h.referer,
            now
          )
        );
        upserted++;
        touchedAnimeIds.add(item.anime_id);
      }
    }
  }

  for (let i = 0; i < stmts.length; i += 80) {
    await c.env.DB.batch(stmts.slice(i, i + 80));
  }

  if (touchedAnimeIds.size > 0) {
    const flagStmts = Array.from(touchedAnimeIds).map((aid) =>
      c.env.DB.prepare(`UPDATE animes SET has_live_source = 1 WHERE id = ?1`).bind(aid)
    );
    await c.env.DB.batch(flagStmts);
  }

  if (reconcile) {
    await c.env.DB.prepare(
      `UPDATE animes
       SET has_live_source = CASE WHEN EXISTS (
         SELECT 1 FROM episode_hosters eh WHERE eh.anime_id = animes.id AND eh.is_live = 1
       ) THEN 1 ELSE 0 END`
    ).run();
    await purgeHomeCache(c.req.url);
  }

  return c.json({
    status: 'ok',
    upserted_hosters: upserted,
    touched_animes: touchedAnimeIds.size,
    reconciled: reconcile,
  });
});
