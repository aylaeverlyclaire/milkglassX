import { buildCfHandlerHeaders, getCfHandlerBaseUrl, isCfHandlerConfigured } from '../utils/cfHandler';
import { Hono } from 'hono';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { checkBoundedQuota, rateLimit, RateBucket } from '../middleware/rateLimit';
import { zValidatorQuery } from '../middleware/validator';
import { getSourceBaseUrls } from '../services/anilist.service';
import { isSafePublicHttpsUrl } from '../utils/urlSecurity';
import {
  deriveSessionProofKey,
  hmacSha256Hex,
  verifyAccessTokenClaims,
  verifyRequestProof,
  VerifiedAccessClaims,
} from './auth.routes';
import { AppBindings } from '../index';

export const cfRoutes = new Hono<{ Bindings: AppBindings }>();

const userCfBuckets = new Map<string, RateBucket>();

function checkUserCfQuota(userId: string, maxPerMin: number): { allowed: boolean; retryAfter: number } {
  return checkBoundedQuota(userCfBuckets, userId, maxPerMin, 60, 2000);
}

async function enforceCfAuth(
  c: {
    req: { method: string; url: string; header: (name: string) => string | undefined };
    env: AppBindings;
    header: (k: string, v: string) => void;
    json: (body: unknown, status?: ContentfulStatusCode) => Response;
  },
  maxPerMin: number
): Promise<{ blocked: Response | null; claims: VerifiedAccessClaims | null }> {
  if (c.env.CF_REQUIRE_AUTH === 'false' && (c.env as any).ENVIRONMENT !== 'production') return { blocked: null, claims: null };
  if (!c.env.JWT_SECRET || c.env.JWT_SECRET.trim().length < 32) {
    c.header('X-Milkglass-NoCache', '1');
    return {
      blocked: c.json({ status: 'error', message: 'Konfigurasi autentikasi server belum siap.' }, 503),
      claims: null,
    };
  }
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
    return {
      blocked: c.json({ status: 'error', message: 'Tanda tangan permintaan tidak valid.' }, 401),
      claims: null,
    };
  }
  const quota = checkUserCfQuota(claims.userId, maxPerMin);
  if (!quota.allowed) {
    c.header('X-Milkglass-NoCache', '1');
    c.header('Retry-After', String(quota.retryAfter));
    return {
      blocked: c.json({ status: 'error', message: 'Batas permintaan akun tercapai.' }, 429),
      claims: null,
    };
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
  'animasu.love',
  'animasu.me',
  'yourupload.com',
  'vidhidepre.com',
  'berkasdrive.com',
  'mitedrive.com',
  'short.ink',
  'otakudesu.blog',
  'otakudesu.cloud',
  'otakudesu.best',
  'samehadaku.how',
  'samehadaku.email',
  'samehadaku.mba',
  'nontonanimeid.stream',
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

async function isAllowedCfHost(db: D1Database, host: string): Promise<boolean> {
  const h = host.toLowerCase();
  if (ALLOWED_HOST_SUFFIXES.some((s) => h === s || h.endsWith(`.${s}`))) {
    return true;
  }
  try {
    const dynamicSources = await getSourceBaseUrls(db);
    for (const baseUrl of Object.values(dynamicSources)) {
      const safe = isSafePublicHttpsUrl(baseUrl);
      if (!safe) continue;
      const srcHost = safe.hostname.toLowerCase();
      if (srcHost && (h === srcHost || h.endsWith(`.${srcHost}`))) {
        return true;
      }
    }
  } catch {
  }
  return false;
}


cfRoutes.get('/status', (c) => {
  return c.json({
    status: 'ok',
    data: {
      configured: isCfHandlerConfigured(c.env),
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
  edgeCache(900, 120, true, { allowedQueryParams: ['url'], privateClientCache: true }),
  zValidatorQuery(targetUrlQuerySchema),
  async (c) => {
    const handlerBase = getCfHandlerBaseUrl(c.env);
    if (!handlerBase) {
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
    const headers = buildCfHandlerHeaders(c.env);

    try {
      const upstream = await fetch(
        `${handlerBase}/cookies?url=${encodeURIComponent(targetOrigin)}`,
        {
          method: 'GET',
          headers,
          redirect: 'error',
          signal: AbortSignal.timeout(12_000),
        }
      );

      if (!upstream.ok) {
        await upstream.body?.cancel().catch(() => {});
        c.header('X-Milkglass-NoCache', '1');
        const statusOut = [429, 502, 503, 504].includes(upstream.status) ? 503 : 502;
        return c.json(
          { status: 'error', message: `CF handler gagal (HTTP ${upstream.status}).` },
          statusOut
        );
      }

      const payload = (await upstream.json()) as {
        cookies?: Record<string, string>;
        user_agent?: string;
      };

      if (!payload || !payload.cookies || Object.keys(payload.cookies).length === 0 || !payload.user_agent) {
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
  edgeCache(300, 60, true, { allowedQueryParams: ['url'], privateClientCache: true }),
  zValidatorQuery(targetUrlQuerySchema),
  async (c) => {
    const handlerBase = getCfHandlerBaseUrl(c.env);
    if (!handlerBase) {
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

    const headers = buildCfHandlerHeaders(c.env, { Accept: 'text/html,application/xhtml+xml,*/*' });

    try {
      const upstream = await fetch(
        `${handlerBase}/html?url=${encodeURIComponent(parsed.toString())}`,
        {
          method: 'GET',
          headers,
          redirect: 'error',
          signal: AbortSignal.timeout(15_000),
        }
      );

      if (!upstream.ok) {
        await upstream.body?.cancel().catch(() => {});
        c.header('X-Milkglass-NoCache', '1');
        const statusOut = [429, 502, 503, 504].includes(upstream.status) ? 503 : 502;
        return c.json(
          { status: 'error', message: `CF HTML handler gagal (HTTP ${upstream.status}).` },
          statusOut
        );
      }

      const finalUrlHeader = upstream.headers.get('x-cf-handler-final-url');
      if (finalUrlHeader) {
        const finalParsed = isSafePublicHttpsUrl(finalUrlHeader);
        if (!finalParsed || !(await isAllowedCfHost(c.env.DB, finalParsed.hostname))) {
          await upstream.body?.cancel().catch(() => {});
          c.header('X-Milkglass-NoCache', '1');
          return c.json({ status: 'error', message: 'Redirect akhir CF handler tidak diizinkan.' }, 502);
        }
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
