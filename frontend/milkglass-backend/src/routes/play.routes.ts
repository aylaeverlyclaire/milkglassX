import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { getSourceBaseUrls } from '../services/anilist.service';
import { deriveSessionProofKey, hmacSha256Hex, verifyAccessTokenClaims, verifyRequestProof, VerifiedAccessClaims } from './auth.routes';
import { AppBindings } from '../index';

export const playRoutes = new Hono<{ Bindings: AppBindings }>();

const userPlayBuckets = new Map<string, { count: number; resetAt: number }>();

function checkUserPlayQuota(userId: string, maxPerMin: number): boolean {
  const now = Math.floor(Date.now() / 1000);
  const b = userPlayBuckets.get(userId);
  if (!b || b.resetAt <= now) {
    if (userPlayBuckets.size > 2000) userPlayBuckets.clear();
    userPlayBuckets.set(userId, { count: 1, resetAt: now + 60 });
    return true;
  }
  b.count += 1;
  return b.count <= maxPerMin;
}


// ---------------------------------------------------------------------------------------------
// Hint bersama (D1): tautan hoster mentah yang terbukti bisa diekstrak oleh klien lain.
// Hanya PETUNJUK: klien selalu mengekstrak + memverifikasi sendiri. Karena itu server membatasi ketat
// apa yang boleh disimpan (host hoster dikenal, https, bukan IP/localhost) dan siapa yang boleh menulis.
// ---------------------------------------------------------------------------------------------
const HINT_MAX_AGE_SEC = 12 * 3600;
const HINT_REFRESH_AFTER_SEC = 20 * 60;
const HINT_MAX_CANDIDATES = 8;
const HOSTER_TOKENS = [
  'pixeldrain', 'pdrain', 'filedon', 'odfiles', 'buzzheavier', 'qiwi', 'mediafire', 'gofile',
  'krakenfiles', 'kfiles', 'acefile', 'desustream', 'ondesu', 'wibufile', 'uqni', 'mp4upload',
];

function isPublicHttpsUrl(raw: string): URL | null {
  try {
    const u = new URL(raw);
    if (u.protocol !== 'https:' || u.username || u.password) return null;
    if (u.port && u.port !== '443') return null;
    const h = u.hostname.toLowerCase();
    if (!h.includes('.') || h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal')) return null;
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h) || h.includes(':')) return null;
    return u;
  } catch {
    return null;
  }
}

function isKnownHosterUrl(raw: string): boolean {
  const u = isPublicHttpsUrl(raw);
  if (!u) return false;
  const h = u.hostname.toLowerCase();
  return HOSTER_TOKENS.some((t) => h.includes(t));
}

export function hintKey(title: string, ep: number): string {
  const norm = title.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ');
  return `${norm.slice(0, 120)}#${ep}`;
}

const hintBodySchema = z.object({
  title: z.string().trim().min(2).max(160),
  ep: z.number().int().min(1).max(2000),
  candidates: z
    .array(
      z.object({
        source: z.enum(['otakudesu', 'samehadaku', 'oploverz', 'kusonime']),
        quality: z.enum(['1080p', '720p', '480p', '360p']),
        hoster: z.string().trim().min(1).max(40),
        raw_url: z.string().max(2048),
        referer: z.string().max(2048).optional(),
      })
    )
    .min(1)
    .max(HINT_MAX_CANDIDATES),
});

type StoredHint = { source: string; quality: string; hoster: string; raw_url: string; referer?: string };

async function requireVerifiedUser(c: any): Promise<{ claims: VerifiedAccessClaims | null; deny: Response | null }> {
  if (c.env.CF_REQUIRE_AUTH === 'false' || !c.env.JWT_SECRET || c.env.JWT_SECRET.trim().length < 32) {
    return { claims: null, deny: null };
  }
  const auth = c.req.header('Authorization') || '';
  const deny = (status: 401 | 429, message: string) => {
    c.header('X-Milkglass-NoCache', '1');
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
  if (!checkUserPlayQuota(claims.userId, 24)) {
    c.header('Retry-After', '60');
    return { claims: null, deny: deny(429, 'Batas permintaan pemutaran tercapai.') };
  }
  return { claims, deny: null };
}

playRoutes.post('/hint', rateLimit(20, 60), zValidator('json', hintBodySchema), async (c) => {
  const { deny } = await requireVerifiedUser(c);
  if (deny) return deny;
  const { title, ep, candidates } = c.req.valid('json');
  const clean: StoredHint[] = [];
  const seen = new Set<string>();
  for (const cand of candidates) {
    if (!isKnownHosterUrl(cand.raw_url) || seen.has(cand.raw_url)) continue;
    const ref = cand.referer && isPublicHttpsUrl(cand.referer) ? cand.referer : '';
    seen.add(cand.raw_url);
    clean.push({ source: cand.source, quality: cand.quality, hoster: cand.hoster, raw_url: cand.raw_url, referer: ref });
  }
  if (clean.length === 0) return c.json({ status: 'ok', stored: false });

  const key = hintKey(title, ep);
  const now = Math.floor(Date.now() / 1000);
  const existing = await c.env.DB.prepare('SELECT updated_at FROM play_hints WHERE key = ?1').bind(key).first<{ updated_at: number }>();
  if (existing && now - existing.updated_at < HINT_REFRESH_AFTER_SEC) {
    // Sudah segar: lewati tulis (hemat kuota tulis D1).
    return c.json({ status: 'ok', stored: false, fresh: true });
  }
  await c.env.DB.prepare(
    `INSERT INTO play_hints (key, payload, updated_at, report_count) VALUES (?1, ?2, ?3, 1)
     ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at, report_count = report_count + 1`
  ).bind(key, JSON.stringify(clean), now).run();
  return c.json({ status: 'ok', stored: true });
});

async function lookupHint(db: D1Database, title: string, ep: number): Promise<StoredHint[] | null> {
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
        source: z.enum(['otakudesu', 'samehadaku', 'oploverz', 'kusonime']),
        quality: z.enum(['1080p', '720p', '480p', '360p']),
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

function isAcceptableDirect(c: { hoster: string; direct_url: string }): boolean {
  const u = isPublicHttpsUrl(c.direct_url);
  if (!u) return false;
  // Hanya hoster dikenal (lewat label atau host), supaya klien tak bisa menyuntik URL sembarang ke pengguna lain.
  const label = c.hoster.toLowerCase();
  const host = u.hostname.toLowerCase();
  return HOSTER_TOKENS.some((t) => label.includes(t) || host.includes(t));
}

playRoutes.post('/direct', rateLimit(20, 60), zValidator('json', directBodySchema), async (c) => {
  const { deny } = await requireVerifiedUser(c);
  if (deny) return deny;
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
  const existing = await c.env.DB.prepare('SELECT updated_at FROM play_direct WHERE key = ?1').bind(key).first<{ updated_at: number }>();
  if (existing && now - existing.updated_at < DIRECT_REFRESH_AFTER_SEC) {
    return c.json({ status: 'ok', stored: false, fresh: true });
  }
  await c.env.DB.prepare(
    `INSERT INTO play_direct (key, payload, updated_at, report_count) VALUES (?1, ?2, ?3, 1)
     ON CONFLICT(key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at, report_count = report_count + 1`
  ).bind(key, JSON.stringify(clean), now).run();
  return c.json({ status: 'ok', stored: true });
});

async function lookupDirect(db: D1Database, title: string, ep: number): Promise<StoredDirect[] | null> {
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
  title: z.string().trim().min(2).max(160),
  ep: z.coerce.number().int().min(1).max(2000),
  q: z.enum(['1080p', '720p', '480p', '360p']).default('1080p'),
});

playRoutes.get(
  '/resolve',
  rateLimit(30, 60),
  async (c, next) => {
    let verifiedClaims: VerifiedAccessClaims | null = null;
    if (c.env.CF_REQUIRE_AUTH !== 'false' && c.env.JWT_SECRET && c.env.JWT_SECRET.trim().length >= 32) {
      const auth = c.req.header('Authorization') || '';
      if (!auth.startsWith('Bearer ')) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json({ status: 'error', message: 'Autentikasi diperlukan.' }, 401);
      }
      const claims = await verifyAccessTokenClaims(auth.slice(7).trim(), c.env.JWT_SECRET);
      if (!claims) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json({ status: 'error', message: 'Token sesi tidak valid.' }, 401);
      }
      const proofOk = await verifyRequestProof(
        c.req.method,
        c.req.url,
        c.req.header('X-Milkglass-Ts'),
        c.req.header('X-Milkglass-Sig'),
        claims,
        c.env.JWT_SECRET
      );
      if (!proofOk) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json({ status: 'error', message: 'Tanda tangan permintaan tidak valid.' }, 401);
      }
      if (!checkUserPlayQuota(claims.userId, 24)) {
        c.header('X-Milkglass-NoCache', '1');
        c.header('Retry-After', '60');
        return c.json({ status: 'error', message: 'Batas permintaan pemutaran tercapai.' }, 429);
      }
      verifiedClaims = claims;
    }
    await next();
    if (verifiedClaims && verifiedClaims.sessionId && c.res.status === 200) {
      const bodyText = await c.res.clone().text();
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
  edgeCache(900, 120, true),
  zValidator('query', resolveQuerySchema),
  async (c) => {
    const { title, ep, q } = c.req.valid('query');

    // 1) Jalur cepat dari D1 (tanpa scrape, tanpa Cloudflare-challenge): URL direct terverifikasi + hint hoster mentah.
    const [direct, hinted] = await Promise.all([lookupDirect(c.env.DB, title, ep), lookupHint(c.env.DB, title, ep)]);
    if (direct || hinted) {
      return c.json({
        status: 'ok',
        data: {
          direct_candidates: direct ?? [],
          client_extract_candidates: hinted ?? [],
          source: direct ? 'direct' : 'hint',
        },
      });
    }

    const handlerBase = (c.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
    if (!handlerBase.startsWith('http://') && !handlerBase.startsWith('https://')) {
      // Miss biasa (resolver server memang tidak dipasang): 200 kosong, bukan 503, supaya klien tidak
      // menganggap server mati dan tetap bertanya lagi untuk episode lain. Tidak di-cache.
      c.header('X-Milkglass-NoCache', '1');
      return c.json({ status: 'ok', data: { direct_candidates: [], client_extract_candidates: [] } });
    }

    const sourceMap = await getSourceBaseUrls(c.env.DB);
    const params = new URLSearchParams({
      title,
      ep: String(ep),
      quality: q,
    });
    if (sourceMap.otakudesu) params.set('otakudesu', sourceMap.otakudesu);
    if (sourceMap.samehadaku) params.set('samehadaku', sourceMap.samehadaku);
    if (sourceMap.oploverz) params.set('oploverz', sourceMap.oploverz);

    const headers: Record<string, string> = { Accept: 'application/json' };
    if (c.env.CF_HANDLER_TOKEN && c.env.CF_HANDLER_TOKEN.trim().length > 0) {
      headers['x-auth-token'] = c.env.CF_HANDLER_TOKEN.trim();
    }

    try {
      const upstream = await fetch(`${handlerBase}/resolve?${params.toString()}`, {
        method: 'GET',
        headers,
        // Klien menyerah di ~3,5 dtk dan kini balapan dengan cascade lokal; menahan Worker 25 dtk hanya buang kuota.
        signal: AbortSignal.timeout(3_000),
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
