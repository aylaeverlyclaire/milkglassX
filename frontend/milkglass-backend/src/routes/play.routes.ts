import { parseSourceCascadeOrder, QUALITY_SCHEMA, SOURCE_ID_SCHEMA } from '../utils/sources';
import { buildCfHandlerHeaders, getCfHandlerBaseUrl } from '../utils/cfHandler';
import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache, purgePlayResolveCache } from '../middleware/edgeCache';
import { checkBoundedQuota, rateLimit, RateBucket } from '../middleware/rateLimit';
import { isSafePublicHttpsUrl } from '../utils/urlSecurity';
import { getSourceBaseUrls } from '../services/anilist.service';
import { deriveSessionProofKey, hmacSha256Hex, verifyAccessTokenClaims, verifyRequestProof, VerifiedAccessClaims } from './auth.routes';
import { AppBindings } from '../index';
import { D1ReadClient, readDb } from '../utils/db';

export const playRoutes = new Hono<{ Bindings: AppBindings }>();

const userResolveBuckets = new Map<string, RateBucket>();
const userReportBuckets = new Map<string, RateBucket>();
const userWriteBuckets = new Map<string, RateBucket>();
const userTelemetryBuckets = new Map<string, RateBucket>();

function checkUserQuota(buckets: Map<string, RateBucket>, userId: string, maxPerMin: number) {
  return checkBoundedQuota(buckets, userId, maxPerMin, 60, 2000);
}


// ---------------------------------------------------------------------------------------------
// Hint bersama (D1): tautan hoster mentah yang terbukti bisa diekstrak oleh klien lain.
// Hanya PETUNJUK: klien selalu mengekstrak + memverifikasi sendiri. Karena itu server membatasi ketat
// apa yang boleh disimpan (host hoster dikenal, https, bukan IP/localhost) dan siapa yang boleh menulis.
// ---------------------------------------------------------------------------------------------
const HINT_MAX_AGE_SEC = 12 * 3600;
const HINT_REFRESH_AFTER_SEC = 20 * 60;
const HINT_MAX_CANDIDATES = 12;
const IP_AGNOSTIC_HOSTER_DOMAINS = [
  'pixeldrain.com', 'pixeldrain.net', 'pixeldrain.lu', 'pdrain.com',
  'filedon.co', 'odfiles.com', 'r2.dev', 'blogger.googleusercontent.com', 'video-downloads.googleusercontent.com', 'odcloud.net', 'buzzheavier.com', 'qiwi.gg', 'spyderrock.com',
  'mediafire.com', 'krakenfiles.com', 'kfiles.io', 'wibufile.com', 'uqni.net',
  'drive.google.com', 'drive.usercontent.google.com',
];
const IP_LOCKED_HOSTER_DOMAINS = [
  'gofile.io', 'desustream.info', 'desustream.com', 'ondesu.com', 'mp4upload.com',
  'blogger.com', 'yourupload.com', 'vidhidepre.com', 'vidhide.com', 'berkasdrive.com', 'mitedrive.com', 'short.ink',
  'icefile.web.id',
];
const ALL_HOSTER_DOMAINS = [...IP_AGNOSTIC_HOSTER_DOMAINS, ...IP_LOCKED_HOSTER_DOMAINS];

function matchesDomainList(host: string, domains: readonly string[]): boolean {
  const h = host.toLowerCase();
  return domains.some((d) => h === d || h.endsWith('.' + d));
}

function isPublicHttpsUrl(raw: string): URL | null {
  return isSafePublicHttpsUrl(raw);
}

export function isKnownHosterUrl(raw: string): boolean {
  const u = isSafePublicHttpsUrl(raw);
  if (!u) return false;
  return matchesDomainList(u.hostname, ALL_HOSTER_DOMAINS);
}



export function normalizeTitleKey(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ').slice(0, 120);
}

export function hintKey(title: string, ep: number): string {
  return `${normalizeTitleKey(title)}#${ep}`;
}

const hintBodySchema = z.object({
  title: z.string().trim().min(2).max(160),
  ep: z.number().int().min(1).max(2000),
  candidates: z
    .array(
      z.object({
        source: SOURCE_ID_SCHEMA,
        quality: QUALITY_SCHEMA,
        hoster: z.string().trim().min(1).max(40),
        raw_url: z.string().max(2048),
        referer: z.string().max(2048).optional(),
        container: z.enum(['MKV', 'MP4', 'HLS', 'UNKNOWN']).optional(),
      })
    )
    .min(1)
    .max(HINT_MAX_CANDIDATES),
});

type StoredHint = { source: string; quality: string; hoster: string; raw_url: string; referer?: string; container?: string };

async function requireVerifiedUser(c: any, buckets: Map<string, RateBucket> = userWriteBuckets, maxPerMin = 24): Promise<{ claims: VerifiedAccessClaims | null; deny: Response | null }> {
  if (c.env.CF_REQUIRE_AUTH === 'false' && c.env.ENVIRONMENT !== 'production') {
    return { claims: null, deny: null };
  }
  if (!c.env.JWT_SECRET || c.env.JWT_SECRET.trim().length < 32) {
    c.header('Cache-Control', 'no-store');
    return { claims: null, deny: c.json({ status: 'error', message: 'Konfigurasi autentikasi server belum siap.' }, 503) as Response };
  }
  const auth = c.req.header('Authorization') || '';
  const deny = (status: 401 | 429, message: string) => {
    c.header('Cache-Control', 'no-store');
    return c.json({ status: 'error', message }, status) as Response;
  };
  if (!auth.startsWith('Bearer ')) return { claims: null, deny: deny(401, 'Autentikasi diperlukan.') };
  const claims = await verifyAccessTokenClaims(auth.slice(7).trim(), c.env.JWT_SECRET);
  if (!claims) return { claims: null, deny: deny(401, 'Token sesi tidak valid.') };
  const proofOk = await verifyRequestProof(
    c.req.method,
    c.req.url,
    c.req.header('X-Milkglass-Ts'),
    c.req.header('X-Milkglass-Sig'),
    claims,
    c.env.JWT_SECRET
  );
  if (!proofOk) return { claims: null, deny: deny(401, 'Tanda tangan permintaan tidak valid.') };
  const quota = checkUserQuota(buckets, claims.userId, maxPerMin);
  if (!quota.allowed) {
    c.header('Retry-After', String(quota.retryAfter));
    return { claims: null, deny: deny(429, 'Batas permintaan pemutaran tercapai.') };
  }
  return { claims, deny: null };
}

const requireVerifiedUserMiddleware = (buckets: Map<string, RateBucket>, maxPerMin: number) =>
  async (c: any, next: () => Promise<void>) => {
    const { deny } = await requireVerifiedUser(c, buckets, maxPerMin);
    if (deny) return deny;
    await next();
  };

playRoutes.post('/hint', zValidator('json', hintBodySchema), async (c) => {
  const { title, ep, candidates } = c.req.valid('json');
  const clean: StoredHint[] = [];
  const seen = new Set<string>();
  for (const cand of candidates) {
    if (!isKnownHosterUrl(cand.raw_url) || seen.has(cand.raw_url)) continue;
    const ref = cand.referer && isPublicHttpsUrl(cand.referer) ? cand.referer : '';
    seen.add(cand.raw_url);
    clean.push({
      source: cand.source,
      quality: cand.quality,
      hoster: cand.hoster,
      raw_url: cand.raw_url,
      referer: ref,
      ...(cand.container ? { container: cand.container } : {}),
    });
  }
  if (clean.length === 0) return c.json({ status: 'ok', stored: false });

  const key = hintKey(title, ep);
  const now = Math.floor(Date.now() / 1000);
  try {
    const existing = await c.env.DB.prepare('SELECT payload, updated_at FROM play_hints WHERE key = ?1')
      .bind(key)
      .first<{ payload: string; updated_at: number }>();
    let merged: StoredHint[] = clean;
    if (existing && now - existing.updated_at < HINT_MAX_AGE_SEC) {
      let prev: StoredHint[] = [];
      try {
        const parsed = JSON.parse(existing.payload) as StoredHint[];
        if (Array.isArray(parsed)) prev = parsed;
      } catch {}
      const prevQualities = new Set(prev.map((h) => h.quality));
      const prevSources = new Set(prev.map((h) => h.source));
      const prevUrls = new Set(prev.map((h) => h.raw_url));
      const addsCoverage = clean.some(
        (h) => !prevQualities.has(h.quality) || !prevSources.has(h.source) || !prevUrls.has(h.raw_url)
      );
      if (!addsCoverage && now - existing.updated_at < HINT_REFRESH_AFTER_SEC) {
        return c.json({ status: 'ok', stored: false, fresh: true });
      }
      // Additive merge lintas source & resolusi (maks 3 kandidat per resolusi, maks 12 total)
      const perQuality = new Map<string, StoredHint[]>();
      for (const item of [...clean, ...prev]) {
        if (!item || !isKnownHosterUrl(item.raw_url)) continue;
        const bucket = perQuality.get(item.quality) ?? [];
        if (bucket.length < 3 && !bucket.some((x) => x.raw_url === item.raw_url)) {
          bucket.push(item);
          perQuality.set(item.quality, bucket);
        }
      }
      merged = Array.from(perQuality.values()).flat().slice(0, 12);
    }
    await c.env.DB.prepare(
      `INSERT INTO play_hints (key, payload, updated_at, report_count) VALUES (?1, ?2, ?3, 1)
       ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at, report_count = report_count + 1`
    ).bind(key, JSON.stringify(merged), now).run();
    c.executionCtx?.waitUntil?.(purgePlayResolveCache(c.req.url, title, ep));
    return c.json({ status: 'ok', stored: true, merged_count: merged.length });
  } catch {
    return c.json({ status: 'ok', stored: false, degraded: true });
  }
});

async function lookupEpisodeHosters(db: D1ReadClient, title: string, ep: number, animeId?: number): Promise<StoredHint[] | null> {
  const tKey = normalizeTitleKey(title);
  if (!tKey && !animeId) return null;
  try {
    const query = animeId
      ? db
          .prepare(
            `SELECT url, quality, hoster, provider, container, referer
             FROM episode_hosters
             WHERE episode_number = ?2 AND is_live = 1 AND (anime_id = ?1 OR title_key = ?3 OR title_alt_key = ?3)
             ORDER BY (CASE WHEN anime_id = ?1 THEN 0 ELSE 1 END) ASC, last_verified_at DESC
             LIMIT 16`
          )
          .bind(animeId, ep, tKey)
      : db
          .prepare(
            `SELECT url, quality, hoster, provider, container, referer
             FROM episode_hosters
             WHERE episode_number = ?2 AND is_live = 1 AND (title_key = ?1 OR title_alt_key = ?1)
             ORDER BY last_verified_at DESC
             LIMIT 16`
          )
          .bind(tKey, ep);
    const { results } = await query
      .all<{
        url: string;
        quality: string;
        hoster: string;
        provider: string;
        container: string;
        referer: string;
      }>();
    if (!results || results.length === 0) return null;
    return results.map((r) => ({
      raw_url: r.url,
      quality: r.quality || '720p',
      hoster: r.hoster || 'Hoster',
      source: r.provider || 'otakudesu',
      container: r.container || 'mp4',
      referer: r.referer || '',
    }));
  } catch {
    return null;
  }
}

async function lookupHint(db: D1ReadClient, title: string, ep: number): Promise<StoredHint[] | null> {
  try {
    const row = await db
      .prepare('SELECT payload, updated_at FROM play_hints WHERE key = ?1')
      .bind(hintKey(title, ep))
      .first<{ payload: string; updated_at: number }>();
    if (!row) return null;
    if (Math.floor(Date.now() / 1000) - row.updated_at > HINT_MAX_AGE_SEC) return null;
    const arr = JSON.parse(row.payload) as StoredHint[];
    const ok = (Array.isArray(arr) ? arr : []).filter((h) => h && isKnownHosterUrl(h.raw_url));
    return ok.length ? ok : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// URL direct terverifikasi (D1 play_direct). Klien melaporkan otomatis setelah ekstraksi + verifikasi berhasil.
// Umur pendek (30 mnt) karena URL hoster bisa kedaluwarsa/terikat IP; klien penerima SELALU memverifikasi ulang
// (probe Range) sebelum memakainya dan jatuh ke scraping lokal bila gagal.
// ---------------------------------------------------------------------------------------------
const DIRECT_MAX_AGE_SEC = 30 * 60;
const DIRECT_REFRESH_AFTER_SEC = 5 * 60;
const DIRECT_MAX_CANDIDATES = 4;

const directBodySchema = z.object({
  title: z.string().trim().min(2).max(160),
  ep: z.number().int().min(1).max(2000),
  candidates: z
    .array(
      z.object({
        source: SOURCE_ID_SCHEMA,
        quality: QUALITY_SCHEMA,
        hoster: z.string().trim().min(1).max(40),
        direct_url: z.string().max(2048),
        referer: z.string().max(2048).optional(),
        supports_range: z.boolean().default(true),
        container: z.enum(['MKV', 'MP4', 'HLS', 'UNKNOWN']).default('UNKNOWN'),
        is_hls: z.boolean().default(false),
      })
    )
    .min(1)
    .max(DIRECT_MAX_CANDIDATES),
});

type StoredDirect = {
  source: string;
  quality: string;
  hoster: string;
  direct_url: string;
  referer?: string;
  supports_range: boolean;
  container: string;
  is_hls: boolean;
};

export function isAcceptableDirect(c: { hoster: string; direct_url: string }): boolean {
  const u = isSafePublicHttpsUrl(c.direct_url);
  if (!u) return false;
  return matchesDomainList(u.hostname, IP_AGNOSTIC_HOSTER_DOMAINS);
}

playRoutes.post('/direct', zValidator('json', directBodySchema), async (c) => {
  const { title, ep, candidates } = c.req.valid('json');
  const clean: StoredDirect[] = [];
  const seen = new Set<string>();
  for (const cand of candidates) {
    if (!isAcceptableDirect(cand) || seen.has(cand.direct_url)) continue;
    const ref = cand.referer && isPublicHttpsUrl(cand.referer) ? cand.referer : '';
    seen.add(cand.direct_url);
    clean.push({
      source: cand.source,
      quality: cand.quality,
      hoster: cand.hoster,
      direct_url: cand.direct_url,
      referer: ref,
      supports_range: cand.supports_range,
      container: cand.container,
      is_hls: cand.is_hls,
    });
  }
  if (clean.length === 0) return c.json({ status: 'ok', stored: false });

  const key = hintKey(title, ep);
  const now = Math.floor(Date.now() / 1000);
  try {
    const existing = await c.env.DB.prepare('SELECT payload, updated_at FROM play_direct WHERE key = ?1').bind(key).first<{ payload: string; updated_at: number }>();
    let merged: StoredDirect[] = clean;
    if (existing && now - existing.updated_at < DIRECT_MAX_AGE_SEC) {
      let prev: StoredDirect[] = [];
      try {
        const parsed = JSON.parse(existing.payload) as StoredDirect[];
        if (Array.isArray(parsed)) prev = parsed;
      } catch {}
      const prevQualities = new Set(prev.map((d) => d.quality));
      const prevSources = new Set(prev.map((d) => d.source));
      const prevContainers = new Set(prev.map((d) => `${d.quality}:${d.container}`));
      const addsCoverage = clean.some(
        (d) =>
          !prevQualities.has(d.quality) ||
          !prevSources.has(d.source) ||
          !prevContainers.has(`${d.quality}:${d.container}`)
      );
      if (!addsCoverage && now - existing.updated_at < DIRECT_REFRESH_AFTER_SEC) {
        return c.json({ status: 'ok', stored: false, fresh: true });
      }
      const perQuality = new Map<string, StoredDirect[]>();
      for (const item of [...clean, ...prev]) {
        if (!item || !isAcceptableDirect({ hoster: item.hoster || '', direct_url: item.direct_url })) continue;
        const bucket = perQuality.get(item.quality) ?? [];
        if (bucket.length < 3 && !bucket.some((x) => x.direct_url === item.direct_url)) {
          bucket.push(item);
          perQuality.set(item.quality, bucket);
        }
      }
      merged = Array.from(perQuality.values()).flat().slice(0, 12);
    }
    await c.env.DB.prepare(
      `INSERT INTO play_direct (key, payload, updated_at, report_count) VALUES (?1, ?2, ?3, 1)
       ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at, report_count = report_count + 1`
    ).bind(key, JSON.stringify(merged), now).run();
    c.executionCtx?.waitUntil?.(purgePlayResolveCache(c.req.url, title, ep));
    return c.json({ status: 'ok', stored: true });
  } catch {
    return c.json({ status: 'ok', stored: false, degraded: true });
  }
});

async function lookupDirect(db: D1ReadClient, title: string, ep: number): Promise<StoredDirect[] | null> {
  try {
    const row = await db
      .prepare('SELECT payload, updated_at FROM play_direct WHERE key = ?1')
      .bind(hintKey(title, ep))
      .first<{ payload: string; updated_at: number }>();
    if (!row) return null;
    if (Math.floor(Date.now() / 1000) - row.updated_at > DIRECT_MAX_AGE_SEC) return null;
    const arr = JSON.parse(row.payload) as StoredDirect[];
    const ok = (Array.isArray(arr) ? arr : []).filter((d) => d && isAcceptableDirect({ hoster: d.hoster || '', direct_url: d.direct_url }));
    return ok.length ? ok : null;
  } catch {
    return null;
  }
}

const resolveQuerySchema = z.object({
  anime_id: z.coerce.number().int().positive().optional(),
  title: z.string().trim().min(1).max(160),
  ep: z.coerce.number().int().min(1).max(2000),
  q: QUALITY_SCHEMA.default('1080p'),
});

playRoutes.get(
  '/resolve',
  async (c, next) => {
    let verifiedClaims: VerifiedAccessClaims | null = null;
    const auth = c.req.header('Authorization') || '';
    if (auth.startsWith('Bearer ') && c.env.JWT_SECRET && c.env.JWT_SECRET.trim().length >= 32) {
      const claims = await verifyAccessTokenClaims(auth.slice(7).trim(), c.env.JWT_SECRET);
      if (claims) {
        const proofOk = await verifyRequestProof(
          c.req.method,
          c.req.url,
          c.req.header('X-Milkglass-Ts'),
          c.req.header('X-Milkglass-Sig'),
          claims,
          c.env.JWT_SECRET
        );
        if (proofOk) {
          verifiedClaims = claims;
        }
      }
    }
    await next();
    if (verifiedClaims && verifiedClaims.sessionId && c.res.status === 200) {
      const bodyText = await c.res.text();
      const proofKey = await deriveSessionProofKey(
        verifiedClaims.userId,
        verifiedClaims.sessionId,
        c.env.JWT_SECRET
      );
      const serverSig = await hmacSha256Hex(proofKey, bodyText);
      const signedHeaders = new Headers(c.res.headers);
      signedHeaders.set('X-Milkglass-Server-Sig', serverSig);
      c.res = new Response(bodyText, { status: c.res.status, headers: signedHeaders });
    }
  },
  zValidator('query', resolveQuerySchema),
  edgeCache(900, 120, true, { allowedQueryParams: ['anime_id', 'title', 'ep'], privateClientCache: true, normalizeTitleParam: true }),
  async (c) => {
    const { anime_id, title, ep, q } = c.req.valid('query');

    // 1) Jalur cepat dari D1 (tanpa scrape, tanpa Cloudflare-challenge): URL direct terverifikasi + multi-hoster katalog + hint.
    const rdb = readDb(c.env);
    const [direct, indexedHosters, hinted, globalStats] = await Promise.all([
      lookupDirect(rdb, title, ep),
      lookupEpisodeHosters(rdb, title, ep, anime_id),
      lookupHint(rdb, title, ep),
      loadAsnStats(rdb, 0),
    ]);
    const mergedHints: StoredHint[] = [];
    const seenUrls = new Set<string>();
    for (const item of [...(indexedHosters ?? []), ...(hinted ?? [])]) {
      if (!seenUrls.has(item.raw_url)) {
        seenUrls.add(item.raw_url);
        mergedHints.push(item);
      }
    }
    if (direct || mergedHints.length > 0) {
      const hosterScores: Record<string, number> = {};
      for (const [hoster, st] of globalStats.entries()) {
        hosterScores[hoster] = Math.round(scoreAsnStat(st) * 1000) / 1000;
      }
      return c.json({
        status: 'ok',
        data: {
          direct_candidates: rankByAsn(direct ?? [], q, globalStats),
          client_extract_candidates: rankByAsn(mergedHints, q, globalStats),
          hoster_scores: hosterScores,
          source: direct ? 'direct' : indexedHosters ? 'catalog_hosters' : 'hint',
        },
      });
    }

    const handlerBase = getCfHandlerBaseUrl(c.env);
    if (!handlerBase) {
      // Miss biasa (resolver server memang tidak dipasang): 200 kosong, bukan 503, supaya klien tidak
      // menganggap server mati dan tetap bertanya lagi untuk episode lain. Tidak di-cache.
      c.header('X-Milkglass-ShortCache', '20');
      return c.json({ status: 'ok', data: { direct_candidates: [], client_extract_candidates: [] } });
    }

    const [sourceMap, orderRow] = await Promise.all([
      getSourceBaseUrls(rdb),
      rdb
        .prepare("SELECT value FROM app_config WHERE key = 'source_cascade_order' LIMIT 1")
        .first<{ value?: string }>()
        .catch(() => null),
    ]);
    const cascadeOrder = parseSourceCascadeOrder(orderRow?.value);
    const params = new URLSearchParams({
      title,
      ep: String(ep),
      quality: q,
      order: cascadeOrder.join(','),
    });
    if (sourceMap.samehadaku) params.set('samehadaku', sourceMap.samehadaku);
    if (sourceMap.oploverz) params.set('oploverz', sourceMap.oploverz);
    if (sourceMap.nontonanimeid) params.set('nontonanimeid', sourceMap.nontonanimeid);
    if (sourceMap.otakudesu) params.set('otakudesu', sourceMap.otakudesu);
    if (sourceMap.animasu) params.set('animasu', sourceMap.animasu);

    const headers = buildCfHandlerHeaders(c.env);

    try {
      const upstream = await fetch(`${handlerBase}/resolve?${params.toString()}`, {
        method: 'GET',
        headers,
        // Klien menyerah di ~3,5 dtk dan kini balapan dengan cascade lokal; menahan Worker 25 dtk hanya buang kuota.
        signal: AbortSignal.timeout(3_200),
      });
      if (!upstream.ok) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json(
          { status: 'error', message: `Server resolver gagal (HTTP ${upstream.status}).` },
          502
        );
      }
      const data = (await upstream.json()) as {
        direct_candidates?: unknown[];
        client_extract_candidates?: unknown[];
      };
      const hasDirect = Array.isArray(data?.direct_candidates) && data.direct_candidates.length > 0;
      const hasClient =
        Array.isArray(data?.client_extract_candidates) && data.client_extract_candidates.length > 0;
      if (!hasDirect && !hasClient) {
        c.header('X-Milkglass-NoCache', '1');
      }
      return c.json({ status: 'ok', data });
    } catch {
      c.header('X-Milkglass-NoCache', '1');
      return c.json({ status: 'error', message: 'Server resolver tidak dapat dijangkau.' }, 504);
    }
  }
);

const reportDeadSchema = z.object({
  title: z.string().trim().min(2).max(160),
  ep: z.number().int().min(1).max(2000),
  kind: z.enum(['direct', 'hint']),
  url: z.string().trim().pipe(z.string().url().max(2048)),
  http_status: z.enum(['403', '404', '410', 'expired', 'timeout']),
});

playRoutes.post(
  '/report',
  zValidator('json', reportDeadSchema),
  async (c) => {
    const { title, ep, kind, url } = c.req.valid('json');
    const key = hintKey(title, ep);
    const table = kind === 'direct' ? 'play_direct' : 'play_hints';
    try {
      const row = await c.env.DB.prepare(`SELECT payload FROM ${table} WHERE key = ?1`)
        .bind(key)
        .first<{ payload: string }>();
      let evicted = false;
      let remaining = 0;
      if (row) {
        const arr = JSON.parse(row.payload) as Array<Record<string, unknown>>;
        const filtered = arr.filter((item) => (kind === 'direct' ? item.direct_url : item.raw_url) !== url);
        remaining = filtered.length;
        if (filtered.length === 0) {
          await c.env.DB.prepare(`DELETE FROM ${table} WHERE key = ?1`).bind(key).run();
          evicted = true;
        } else if (filtered.length !== arr.length) {
          await c.env.DB.prepare(`UPDATE ${table} SET payload = ?2 WHERE key = ?1`)
            .bind(key, JSON.stringify(filtered))
            .run();
          evicted = true;
        }
      }
      if (kind === 'hint') {
        const affected = await c.env.DB.prepare(
          `SELECT DISTINCT anime_id FROM episode_hosters WHERE url = ?1 AND is_live = 1`
        ).bind(url).all<{ anime_id: number }>();
        const animeIds = (affected.results ?? []).map((r) => r.anime_id);
        if (animeIds.length > 0) {
          await c.env.DB.prepare(
            `UPDATE episode_hosters SET is_live = 0, fail_count = fail_count + 1 WHERE url = ?1`
          ).bind(url).run();
          for (const aid of animeIds) {
            await c.env.DB.prepare(
              `UPDATE animes
               SET has_live_source = CASE WHEN EXISTS (
                 SELECT 1 FROM episode_hosters WHERE anime_id = ?1 AND is_live = 1
               ) THEN 1 ELSE 0 END
               WHERE id = ?1`
            ).bind(aid).run();
          }
          evicted = true;
        }
      }
      if (evicted) {
        c.executionCtx?.waitUntil?.(purgePlayResolveCache(c.req.url, title, ep));
      }
      return c.json({ status: 'ok', evicted, remaining });
    } catch {
      return c.json({ status: 'ok', evicted: false });
    }
  }
);

// Wave 2: Per-ASN Hoster Telemetry (hemat D1 Free Plan)
const TELEMETRY_WRITE_COOLDOWN_SEC = 45;
const TELEMETRY_MIN_TTFF_MS = 50;
const TELEMETRY_MAX_TTFF_MS = 15_000;
const telemetryWriteGate = new Map<string, number>();
const ASN_STATS_CACHE_TTL_MS = 5 * 60 * 1000;
const ASN_STATS_MAX_AGE_SEC = 14 * 86400;
type AsnHosterStat = { ema_ttff_ms: number; ok_count: number; err_count: number };
const asnStatsCache = new Map<number, { ts: number; map: Map<string, AsnHosterStat> }>();

function scoreAsnStat(st: AsnHosterStat | undefined): number {
  if (!st) return 0.65;
  const total = st.ok_count + st.err_count;
  const sr = (st.ok_count + 1.6) / (total + 2.0);
  const clampedTtff = Math.min(TELEMETRY_MAX_TTFF_MS, Math.max(TELEMETRY_MIN_TTFF_MS, st.ema_ttff_ms));
  const speed = 1 - (clampedTtff - TELEMETRY_MIN_TTFF_MS) / (TELEMETRY_MAX_TTFF_MS - TELEMETRY_MIN_TTFF_MS);
  return sr * 0.7 + speed * 0.3;
}

async function loadAsnStats(db: D1ReadClient, asn: number): Promise<Map<string, AsnHosterStat>> {
  const nowMs = Date.now();
  const cached = asnStatsCache.get(asn);
  if (cached && nowMs - cached.ts < ASN_STATS_CACHE_TTL_MS) return cached.map;
  const merged = new Map<string, AsnHosterStat>();
  try {
    const cutoff = Math.floor(nowMs / 1000) - ASN_STATS_MAX_AGE_SEC;
    const { results } = await db
      .prepare(`SELECT asn, hoster, ema_ttff_ms, ok_count, err_count FROM hoster_asn_stats WHERE asn IN (?1, 0) AND updated_at >= ?2`)
      .bind(asn, cutoff)
      .all<{ asn: number; hoster: string; ema_ttff_ms: number; ok_count: number; err_count: number }>();
    const fallback = new Map<string, AsnHosterStat>();
    for (const r of results ?? []) {
      const st = { ema_ttff_ms: r.ema_ttff_ms, ok_count: r.ok_count, err_count: r.err_count };
      if (r.asn === asn && asn !== 0 && r.ok_count + r.err_count >= 2) merged.set(r.hoster, st);
      else if (r.asn === 0) fallback.set(r.hoster, st);
    }
    for (const [k, v] of fallback) if (!merged.has(k)) merged.set(k, v);
  } catch {}
  if (asnStatsCache.size >= 128) asnStatsCache.clear();
  asnStatsCache.set(asn, { ts: nowMs, map: merged });
  return merged;
}

function rankByAsn<T extends { hoster?: string; quality?: string }>(
  list: T[],
  preferredQuality: string,
  stats: Map<string, AsnHosterStat>
): T[] {
  if (list.length <= 1) return list;
  return [...list].sort((a, b) => {
    const qa = a.quality === preferredQuality ? 0 : 1;
    const qb = b.quality === preferredQuality ? 0 : 1;
    if (qa !== qb) return qa - qb;
    const sa = scoreAsnStat(stats.get(normalizeHosterKey(a.hoster || '')));
    const sb = scoreAsnStat(stats.get(normalizeHosterKey(b.hoster || '')));
    return sb - sa;
  });
}

function getClientAsn(c: { req: { raw: Request } }): number {
  const raw = Number((c.req.raw as unknown as { cf?: { asn?: number } })?.cf?.asn ?? 0);
  return Number.isInteger(raw) && raw > 0 && raw <= 4_294_967_295 ? raw : 0;
}

function normalizeHosterKey(raw: string): string {
  const s = raw.toLowerCase().trim();
  if (s.includes('pixeldrain') || s.includes('pdrain')) return 'pixeldrain';
  if (s.includes('filedon')) return 'filedon';
  if (s.includes('odfiles')) return 'odfiles';
  if (s.includes('buzzheavier')) return 'buzzheavier';
  if (s.includes('qiwi')) return 'qiwi';
  if (s.includes('mediafire')) return 'mediafire';
  if (s.includes('kraken') || s.includes('kfiles')) return 'krakenfiles';
  if (s.includes('gofile')) return 'gofile';
  if (s.includes('acefile')) return 'acefile';
  if (s.includes('desustream') || s.includes('ondesu')) return 'desustream';
  if (s.includes('icefile')) return 'icefile';
  return s.replace(/[^a-z0-9]+/g, '').slice(0, 32) || 'unknown';
}

const telemetryBodySchema = z.object({
  hoster: z.string().trim().min(2).max(40),
  ok: z.boolean(),
  ttff_ms: z.number().finite().optional(),
});

playRoutes.post(
  '/telemetry',
  zValidator('json', telemetryBodySchema),
  async (c) => {

    const { hoster, ok, ttff_ms } = c.req.valid('json');
    const asn = getClientAsn(c);
    const normHoster = normalizeHosterKey(hoster);
    if (normHoster === 'unknown') return c.json({ status: 'ok', stored: false });

    const nowSec = Math.floor(Date.now() / 1000);
    const gateKey = `${asn}:${normHoster}`;
    const lastWrite = telemetryWriteGate.get(gateKey) ?? 0;
    if (nowSec - lastWrite < TELEMETRY_WRITE_COOLDOWN_SEC) {
      return c.json({ status: 'ok', stored: false, throttled: true });
    }
    if (telemetryWriteGate.size >= 512) telemetryWriteGate.clear();
    telemetryWriteGate.set(gateKey, nowSec);

    const clampedTtff = Math.min(
      TELEMETRY_MAX_TTFF_MS,
      Math.max(TELEMETRY_MIN_TTFF_MS, Math.round(ttff_ms ?? (ok ? 2500 : TELEMETRY_MAX_TTFF_MS)))
    );

    const upsertSql = `INSERT INTO hoster_asn_stats (asn, hoster, ema_ttff_ms, ok_count, err_count, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(asn, hoster) DO UPDATE SET
           ema_ttff_ms = CASE
             WHEN excluded.ok_count = 1 THEN (hoster_asn_stats.ema_ttff_ms * 0.8) + (excluded.ema_ttff_ms * 0.2)
             ELSE hoster_asn_stats.ema_ttff_ms
           END,
           ok_count = MIN(hoster_asn_stats.ok_count + excluded.ok_count, 1000000),
           err_count = MIN(hoster_asn_stats.err_count + excluded.err_count, 1000000),
           updated_at = excluded.updated_at`;
    try {
      const stmts = [
        c.env.DB.prepare(upsertSql).bind(asn, normHoster, clampedTtff, ok ? 1 : 0, ok ? 0 : 1, nowSec),
      ];
      if (asn !== 0) {
        const globalGateKey = `0:${normHoster}`;
        const lastGlobal = telemetryWriteGate.get(globalGateKey) ?? 0;
        if (nowSec - lastGlobal >= TELEMETRY_WRITE_COOLDOWN_SEC) {
          telemetryWriteGate.set(globalGateKey, nowSec);
          stmts.push(
            c.env.DB.prepare(upsertSql).bind(0, normHoster, clampedTtff, ok ? 1 : 0, ok ? 0 : 1, nowSec)
          );
        }
      }
      if (stmts.length === 1) await stmts[0].run();
      else await c.env.DB.batch(stmts);
      asnStatsCache.delete(asn);
      if (stmts.length > 1) asnStatsCache.delete(0);
      return c.json({ status: 'ok', stored: true, asn, hoster: normHoster });
    } catch {
      return c.json({ status: 'ok', stored: false, degraded: true });
    }
  }
);

// Wave 3: Episode-level Playback Failure & TTFF Telemetry + Automated GitHub Issue Alerting (Option B)
const SLOW_ALERT_THRESHOLD_MS = 5000;
const GITHUB_ISSUE_DEDUP_SEC = 24 * 3600;
const DEFAULT_TELEMETRY_REPO = 'aylaeverlyclaire/milkglassX';

const playbackReportSchema = z.object({
  title: z.string().trim().min(1).max(160),
  ep: z.number().int().min(1).max(2000),
  anime_id: z.number().int().positive().optional(),
  status: z.enum(['ok', 'slow', 'failed']),
  resolve_ms: z.number().int().min(0).max(120_000).default(0),
  ttff_ms: z.number().int().min(0).max(120_000).default(0),
  cache_hit: z.boolean().default(false),
  source: z.string().trim().max(40).default(''),
  hoster: z.string().trim().max(60).default(''),
  quality: z.string().trim().max(16).default(''),
  error_code: z.string().trim().max(64).default(''),
  error_message: z.string().trim().max(400).default(''),
  provider_diagnostics: z.string().trim().max(1200).default(''),
  app_version: z.string().trim().max(32).default(''),
});

async function dispatchGitHubTelemetryIssue(
  env: AppBindings,
  report: z.infer<typeof playbackReportSchema>,
  asn: number,
  reportId: number
): Promise<string> {
  const token = env.GITHUB_TELEMETRY_TOKEN?.trim();
  if (!token) return '';
  const repo = (env.GITHUB_TELEMETRY_REPO?.trim() || DEFAULT_TELEMETRY_REPO).replace(/^\/+|\/+$/g, '');
  const tag = report.status === 'failed' ? 'FAILED' : `SLOW (${report.ttff_ms}ms)`;
  const issueTitle = `[MilkglassTelemetry] ${tag}: ${report.title} Ep ${report.ep}`.slice(0, 220);
  const issueBody = [
    `### Automated Milkglass Playback Telemetry`,
    `- **Status**: \`${report.status}\``,
    `- **Anime**: ${report.title}${report.anime_id ? ` (AniList #${report.anime_id})` : ''}`,
    `- **Episode**: ${report.ep}`,
    `- **Resolve Duration**: ${report.resolve_ms} ms`,
    `- **Time To First Frame (TTFF)**: ${report.ttff_ms} ms`,
    `- **Cache Hit**: ${report.cache_hit ? 'yes' : 'no'}`,
    `- **Source / Hoster / Quality**: ${report.source || '-'} / ${report.hoster || '-'} / ${report.quality || '-'}`,
    `- **Error Code**: \`${report.error_code || 'NONE'}\``,
    `- **Error Message**: ${report.error_message || '-'}`,
    `- **App Version / ASN**: ${report.app_version || '-'} / AS${asn}`,
    '',
    '#### Provider Diagnostics',
    '```text',
    report.provider_diagnostics || '(none)',
    '```',
  ].join('\n');

  try {
    const resp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'User-Agent': 'milkglass-backend-telemetry/1.0',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      body: JSON.stringify({
        title: issueTitle,
        body: issueBody,
      }),
      signal: AbortSignal.timeout(6_000),
    });
    if (!resp.ok) return '';
    const data = (await resp.json()) as { html_url?: string };
    const url = typeof data?.html_url === 'string' ? data.html_url : '';
    if (url && reportId > 0) {
      await env.DB.prepare('UPDATE playback_reports SET github_issue_url = ?1 WHERE id = ?2')
        .bind(url, reportId)
        .run()
        .catch(() => {});
    }
    return url;
  } catch {
    return '';
  }
}

playRoutes.post(
  '/playback-report',
  zValidator('json', playbackReportSchema),
  async (c) => {
    const report = c.req.valid('json');
    const asn = getClientAsn(c);
    const nowSec = Math.floor(Date.now() / 1000);
    const tKey = normalizeTitleKey(report.title);

    // 1) Update hoster_asn_stats in the same request when hoster is known
    const normHoster = report.hoster ? normalizeHosterKey(report.hoster) : 'unknown';
    if (normHoster !== 'unknown') {
      const ok = report.status !== 'failed';
      const clampedTtff = Math.min(
        TELEMETRY_MAX_TTFF_MS,
        Math.max(TELEMETRY_MIN_TTFF_MS, Math.round(report.ttff_ms || (ok ? 2500 : TELEMETRY_MAX_TTFF_MS)))
      );
      const upsertSql = `INSERT INTO hoster_asn_stats (asn, hoster, ema_ttff_ms, ok_count, err_count, updated_at)
           VALUES (?1, ?2, ?3, ?4, ?5, ?6)
           ON CONFLICT(asn, hoster) DO UPDATE SET
             ema_ttff_ms = CASE
               WHEN excluded.ok_count = 1 THEN (hoster_asn_stats.ema_ttff_ms * 0.8) + (excluded.ema_ttff_ms * 0.2)
               ELSE hoster_asn_stats.ema_ttff_ms
             END,
             ok_count = MIN(hoster_asn_stats.ok_count + excluded.ok_count, 1000000),
             err_count = MIN(hoster_asn_stats.err_count + excluded.err_count, 1000000),
             updated_at = excluded.updated_at`;
      try {
        const stmts = [
          c.env.DB.prepare(upsertSql).bind(asn, normHoster, clampedTtff, ok ? 1 : 0, ok ? 0 : 1, nowSec),
        ];
        if (asn !== 0) {
          stmts.push(
            c.env.DB.prepare(upsertSql).bind(0, normHoster, clampedTtff, ok ? 1 : 0, ok ? 0 : 1, nowSec)
          );
        }
        await c.env.DB.batch(stmts);
        asnStatsCache.delete(asn);
        asnStatsCache.delete(0);
      } catch {}
    }

    // 2) Persist structured episode playback report to D1
    let reportId = 0;
    try {
      const res = await c.env.DB.prepare(
        `INSERT INTO playback_reports (
           title, title_key, ep, anime_id, status, resolve_ms, ttff_ms, cache_hit,
           source, hoster, quality, error_code, error_message, provider_diagnostics,
           app_version, asn, github_issue_url, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, '', ?17)`
      )
        .bind(
          report.title,
          tKey,
          report.ep,
          report.anime_id ?? null,
          report.status,
          report.resolve_ms,
          report.ttff_ms,
          report.cache_hit ? 1 : 0,
          report.source,
          report.hoster,
          report.quality,
          report.error_code,
          report.error_message,
          report.provider_diagnostics,
          report.app_version,
          asn,
          nowSec
        )
        .run();
      reportId = Number(res.meta?.last_row_id ?? 0);
    } catch {
      return c.json({ status: 'ok', stored: false, degraded: true });
    }

    // 3) Option B: Auto-open GitHub Issue on failure or severe slow playback (>5s) with 24h per-episode deduplication
    const shouldAlert = report.status === 'failed' || report.ttff_ms > SLOW_ALERT_THRESHOLD_MS;
    let issueScheduled = false;
    if (shouldAlert && c.env.GITHUB_TELEMETRY_TOKEN?.trim()) {
      try {
        const recentIssue = await c.env.DB.prepare(
          `SELECT id FROM playback_reports
           WHERE title_key = ?1 AND ep = ?2 AND status = ?3
             AND github_issue_url != '' AND created_at >= ?4
           LIMIT 1`
        )
          .bind(tKey, report.ep, report.status, nowSec - GITHUB_ISSUE_DEDUP_SEC)
          .first<{ id: number }>();
        if (!recentIssue) {
          issueScheduled = true;
          c.executionCtx?.waitUntil?.(dispatchGitHubTelemetryIssue(c.env, report, asn, reportId));
        }
      } catch {}
    }

    return c.json({ status: 'ok', stored: true, report_id: reportId, issue_scheduled: issueScheduled });
  }
);

playRoutes.get('/playback-report', rateLimit(30, 60), async (c) => {
  const adminSecret = c.req.header('X-Admin-Secret') || '';
  if (!c.env.ADMIN_SECRET || adminSecret !== c.env.ADMIN_SECRET) {
    return c.json({ status: 'error', message: 'Akses ditolak.' }, 401);
  }
  const statusFilter = c.req.query('status');
  const limit = Math.min(100, Math.max(1, Number(c.req.query('limit') || '30') || 30));
  const rdb = readDb(c.env);
  try {
    const stmt = statusFilter && ['ok', 'slow', 'failed'].includes(statusFilter)
      ? rdb
          .prepare(
            `SELECT id, title, ep, anime_id, status, resolve_ms, ttff_ms, cache_hit,
                    source, hoster, quality, error_code, error_message, provider_diagnostics,
                    app_version, asn, github_issue_url, created_at
             FROM playback_reports WHERE status = ?1 ORDER BY created_at DESC LIMIT ?2`
          )
          .bind(statusFilter, limit)
      : rdb
          .prepare(
            `SELECT id, title, ep, anime_id, status, resolve_ms, ttff_ms, cache_hit,
                    source, hoster, quality, error_code, error_message, provider_diagnostics,
                    app_version, asn, github_issue_url, created_at
             FROM playback_reports ORDER BY created_at DESC LIMIT ?1`
          )
          .bind(limit);
    const { results } = await stmt.all();
    return c.json({ status: 'ok', data: results ?? [] });
  } catch {
    return c.json({ status: 'error', message: 'Gagal membaca laporan telemetri.' }, 500);
  }
});
