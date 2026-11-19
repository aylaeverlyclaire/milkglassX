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
    const handlerBase = (c.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
    if (!handlerBase.startsWith('http://') && !handlerBase.startsWith('https://')) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', available: false, message: 'Server resolver belum dikonfigurasi.' },
        503
      );
    }

    const { title, ep, q } = c.req.valid('query');
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
        signal: AbortSignal.timeout(25_000),
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
