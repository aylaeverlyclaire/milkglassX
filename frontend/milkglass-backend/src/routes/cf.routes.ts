import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { getSourceBaseUrls } from '../services/anilist.service';
import { deriveSessionProofKey, hmacSha256Hex, verifyAccessTokenClaims, verifyRequestProof, VerifiedAccessClaims } from './auth.routes';
import { AppBindings } from '../index';

export const cfRoutes = new Hono<{ Bindings: AppBindings }>();

const userCfBuckets = new Map<string, { count: number; resetAt: number }>();

function checkUserCfQuota(userId: string, maxPerMin: number): boolean {
  const now = Math.floor(Date.now() / 1000);
  const b = userCfBuckets.get(userId);
  if (!b || b.resetAt <= now) {
    if (userCfBuckets.size > 2000) userCfBuckets.clear();
    userCfBuckets.set(userId, { count: 1, resetAt: now + 60 });
    return true;
  }
  b.count += 1;
  return b.count <= maxPerMin;
}

async function enforceCfAuth(
  c: { req: { method: string; url: string; header: (name: string) => string | undefined }; env: AppBindings; header: (k: string, v: string) => void; json: (body: unknown, status?: number) => Response },
  maxPerMin: number
): Promise<{ blocked: Response | null; claims: VerifiedAccessClaims | null }> {
  if (c.env.CF_REQUIRE_AUTH === 'false') return { blocked: null, claims: null };
  if (!c.env.JWT_SECRET || c.env.JWT_SECRET.trim().length < 32) return { blocked: null, claims: null };
  const auth = c.req.header('Authorization') || '';
  if (!auth.startsWith('Bearer ')) {
    c.header('X-Milkglass-NoCache', '1');
    return { blocked: c.json({ status: 'error', message: 'Autentikasi diperlukan.' }, 401), claims: null };
  }
  const claims = await verifyAccessTokenClaims(auth.slice(7).trim(), c.env.JWT_SECRET);
  if (!claims) {
    c.header('X-Milkglass-NoCache', '1');
    return { blocked: c.json({ status: 'error', message: 'Token sesi tidak valid.' }, 401), claims: null };
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
    return { blocked: c.json({ status: 'error', message: 'Tanda tangan permintaan tidak valid.' }, 401), claims: null };
  }
  if (!checkUserCfQuota(claims.userId, maxPerMin)) {
    c.header('X-Milkglass-NoCache', '1');
    c.header('Retry-After', '60');
    return { blocked: c.json({ status: 'error', message: 'Batas permintaan akun tercapai.' }, 429), claims: null };
  }
  return { blocked: null, claims };
}

async function attachServerSignature(
  res: Response,
  claims: VerifiedAccessClaims | null,
  jwtSecret: string | undefined
): Promise<Response> {
  if (!claims || !claims.sessionId || res.status !== 200) return res;
  const bodyText = await res.clone().text();
  const proofKey = await deriveSessionProofKey(claims.userId, claims.sessionId, jwtSecret);
  const sig = await hmacSha256Hex(proofKey, bodyText);
  const headers = new Headers(res.headers);
  headers.set('X-Milkglass-Server-Sig', sig);
  return new Response(bodyText, { status: res.status, headers });
}

const MAX_HTML_CHARS = 2_000_000;

const ALLOWED_HOST_SUFFIXES = [
  'otakudesu.blog',
  'otakudesu.cloud',
  'otakudesu.best',
  'samehadaku.how',
  'samehadaku.email',
  'samehadaku.mba',
  'oploverz.ch',
  'oploverz.ac',
  'oploverz.org',
  'kusonime.com',
  'pixeldrain.com',
  'krakenfiles.com',
  'buzzheavier.com',
  'filedon.co',
  'gofile.io',
  'qiwi.gg',
  'mediafire.com',
  'acefile.co',
  'desustream.info',
  'desustream.me',
  'desudrive.com',
  'ondesu.com',
  'odfiles.com',
  'mp4upload.com',
  'filemoon.sx',
  'vidhide.com',
  'streamwish.to',
];

function isSafePublicHttpsUrl(rawUrl: string): URL | null {
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'https:') return null;
    if (u.username || u.password) return null;
    const host = u.hostname.toLowerCase();
    if (
      !host ||
      host === 'localhost' ||
      host.endsWith('.local') ||
      host.includes(':') ||
      /^(127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/.test(host)
    ) {
      return null;
    }
    return u;
  } catch {
    return null;
  }
}

async function isAllowedCfHost(db: D1Database, host: string): Promise<boolean> {
  const h = host.toLowerCase();
  if (ALLOWED_HOST_SUFFIXES.some((s) => h === s || h.endsWith(`.${s}`))) {
    return true;
  }
  try {
    const dynamicSources = await getSourceBaseUrls(db);
    for (const baseUrl of Object.values(dynamicSources)) {
      try {
        const srcHost = new URL(baseUrl).hostname.toLowerCase();
        if (srcHost && (h === srcHost || h.endsWith(`.${srcHost}`))) {
          return true;
        }
      } catch {
      }
    }
  } catch {
  }
  return false;
}

function buildHandlerHeaders(env: AppBindings, accept: string): Record<string, string> {
  const headers: Record<string, string> = { Accept: accept };
  if (env.CF_HANDLER_TOKEN && env.CF_HANDLER_TOKEN.trim().length > 0) {
    headers['x-auth-token'] = env.CF_HANDLER_TOKEN.trim();
  }
  return headers;
}

cfRoutes.get('/status', (c) => {
  return c.json({
    status: 'ok',
    data: {
      configured: Boolean(c.env.CF_HANDLER_URL && c.env.CF_HANDLER_URL.trim().startsWith('http')),
    },
  });
});

const targetUrlQuerySchema = z.object({
  url: z.string().url().max(2048),
});

cfRoutes.get(
  '/cookies',
  rateLimit(15, 60),
  async (c, next) => {
    const { blocked, claims } = await enforceCfAuth(c, 12);
    if (blocked) return blocked;
    await next();
    c.res = await attachServerSignature(c.res, claims, c.env.JWT_SECRET);
  },
  edgeCache(900, 120, true),
  zValidator('query', targetUrlQuerySchema),
  async (c) => {
    const handlerBase = (c.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
    if (!handlerBase.startsWith('http://') && !handlerBase.startsWith('https://')) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', available: false, message: 'CF handler belum dikonfigurasi.' },
        503
      );
    }

    const { url } = c.req.valid('query');
    const parsed = isSafePublicHttpsUrl(url);
    if (!parsed) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'URL target wajib HTTPS publik.' },
        400
      );
    }

    if (!(await isAllowedCfHost(c.env.DB, parsed.hostname))) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'Domain target tidak termasuk sumber yang diizinkan.' },
        403
      );
    }

    const targetOrigin = parsed.origin + '/';
    const headers = buildHandlerHeaders(c.env, 'application/json');

    try {
      const upstream = await fetch(
        `${handlerBase}/cookies?url=${encodeURIComponent(targetOrigin)}`,
        {
          method: 'GET',
          headers,
          signal: AbortSignal.timeout(30_000),
        }
      );

      if (!upstream.ok) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json(
          { status: 'error', message: `CF handler gagal (HTTP ${upstream.status}).` },
          502
        );
      }

      const payload = (await upstream.json()) as {
        cookies?: Record<string, string>;
        user_agent?: string;
      };

      if (!payload || !payload.cookies || !payload.user_agent) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json(
          { status: 'error', message: 'Respons CF handler tidak lengkap.' },
          502
        );
      }

      return c.json({
        status: 'ok',
        data: {
          origin: targetOrigin,
          cookies: payload.cookies,
          user_agent: payload.user_agent,
        },
      });
    } catch {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'CF handler tidak dapat dijangkau.' },
        504
      );
    }
  }
);

cfRoutes.get(
  '/html',
  rateLimit(10, 60),
  async (c, next) => {
    const { blocked, claims } = await enforceCfAuth(c, 8);
    if (blocked) return blocked;
    await next();
    c.res = await attachServerSignature(c.res, claims, c.env.JWT_SECRET);
  },
  edgeCache(300, 60, true),
  zValidator('query', targetUrlQuerySchema),
  async (c) => {
    const handlerBase = (c.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
    if (!handlerBase.startsWith('http://') && !handlerBase.startsWith('https://')) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', available: false, message: 'CF handler belum dikonfigurasi.' },
        503
      );
    }

    const { url } = c.req.valid('query');
    const parsed = isSafePublicHttpsUrl(url);
    if (!parsed) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'URL target wajib HTTPS publik.' },
        400
      );
    }

    if (!(await isAllowedCfHost(c.env.DB, parsed.hostname))) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'Domain target tidak termasuk sumber yang diizinkan.' },
        403
      );
    }

    const headers = buildHandlerHeaders(c.env, 'text/html,application/xhtml+xml,*/*');

    try {
      const upstream = await fetch(
        `${handlerBase}/html?url=${encodeURIComponent(parsed.toString())}`,
        {
          method: 'GET',
          headers,
          signal: AbortSignal.timeout(35_000),
        }
      );

      if (!upstream.ok) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json(
          { status: 'error', message: `CF HTML handler gagal (HTTP ${upstream.status}).` },
          502
        );
      }

      const html = await upstream.text();
      if (!html || html.length < 64 || html.length > MAX_HTML_CHARS) {
        c.header('X-Milkglass-NoCache', '1');
        return c.json(
          { status: 'error', message: 'Respons HTML dari CF handler tidak valid.' },
          502
        );
      }

      return c.json({
        status: 'ok',
        data: {
          url: parsed.toString(),
          html,
        },
      });
    } catch {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'CF HTML handler tidak dapat dijangkau.' },
        504
      );
    }
  }
);
