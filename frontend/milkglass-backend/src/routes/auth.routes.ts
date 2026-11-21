import { sha256Hex, timingSafeEqualHex } from '../utils/crypto';
import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { createRemoteJWKSet, jwtVerify, SignJWT } from 'jose';
import { rateLimit } from '../middleware/rateLimit';
import { AppBindings } from '../index';

export const authRoutes = new Hono<{ Bindings: AppBindings; Variables: { userId: string } }>();

const GOOGLE_JWKS = createRemoteJWKSet(
  new URL('https://www.googleapis.com/oauth2/v3/certs')
);

const ACCESS_TOKEN_TTL_SEC = 900;
const REFRESH_TOKEN_TTL_SEC = 60 * 60 * 24 * 30;
const GRACE_WINDOW_SEC = 60;
const MAX_ACTIVE_SESSIONS = 2;
const JWT_ISSUER = 'milkglass-backend';
const JWT_AUDIENCE = 'milkglass-android';

export function getValidatedJwtSecret(secret: string | undefined): Uint8Array {
  if (!secret || typeof secret !== 'string' || secret.trim().length < 32) {
    throw new Error('CRITICAL: JWT_SECRET belum dikonfigurasi atau kurang dari 32 karakter.');
  }
  return new TextEncoder().encode(secret);
}


export async function deriveDeterministicRefreshToken(
  sessionId: string,
  generation: number,
  jwtSecret: string | undefined
): Promise<string> {
  const secretBytes = getValidatedJwtSecret(jwtSecret);
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    secretBytes,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign(
    'HMAC',
    key,
    enc.encode(`milkglass-rt:${sessionId}:${generation}`)
  );
  return Array.from(new Uint8Array(mac))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export interface VerifiedAccessClaims {
  userId: string;
  sessionId: string;
  entitlement: string;
  entitlementUntil: number;
}

const MAX_CLOCK_SKEW_SEC = 120;

export async function deriveSessionProofKey(
  userId: string,
  sessionId: string,
  jwtSecret: string | undefined
): Promise<string> {
  const secretBytes = getValidatedJwtSecret(jwtSecret);
  const key = await crypto.subtle.importKey(
    'raw',
    secretBytes,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign(
    'HMAC',
    key,
    new TextEncoder().encode(`milkglass-proof:${userId}:${sessionId}`)
  );
  return Array.from(new Uint8Array(mac))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export async function hmacSha256Hex(keyText: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(keyText),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(mac))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}


export async function verifyRequestProof(
  method: string,
  url: string,
  tsHeader: string | undefined,
  sigHeader: string | undefined,
  claims: VerifiedAccessClaims,
  jwtSecret: string | undefined
): Promise<boolean> {
  if (!claims.sessionId) return false;
  if (!tsHeader || !sigHeader) return false;
  const ts = Number(tsHeader);
  const nowSec = Math.floor(Date.now() / 1000);
  if (!Number.isInteger(ts) || Math.abs(nowSec - ts) > MAX_CLOCK_SKEW_SEC) return false;
  const parsed = new URL(url);
  const canonical = `${method.toUpperCase()}
${parsed.pathname}${parsed.search}
${ts}`;
  const proofKey = await deriveSessionProofKey(claims.userId, claims.sessionId, jwtSecret);
  const expected = await hmacSha256Hex(proofKey, canonical);
  return timingSafeEqualHex(sigHeader.trim().toLowerCase(), expected);
}

async function readUserEntitlement(
  db: D1Database,
  userId: string,
  nowSec: number
): Promise<{ entitlement: string; entitlementUntil: number }> {
  try {
    const row = await db
      .prepare(`SELECT entitlement, entitlement_until FROM users WHERE id = ?1 LIMIT 1`)
      .bind(userId)
      .first<{ entitlement?: string; entitlement_until?: number }>();
    const rawEnt = typeof row?.entitlement === 'string' ? row.entitlement : 'free';
    const rawUntil = row?.entitlement_until;
    if (rawEnt !== 'free' && (rawUntil === null || rawUntil === undefined || Number(rawUntil) > nowSec)) {
      return { entitlement: rawEnt, entitlementUntil: rawUntil == null ? 0 : Number(rawUntil) };
    }
  } catch {
  }
  return { entitlement: 'free', entitlementUntil: 0 };
}

export async function issueAccessToken(
  userId: string,
  jwtSecret: string | undefined,
  entitlement = 'free',
  entitlementUntil = 0,
  sessionId = ''
): Promise<string> {
  const secretKey = getValidatedJwtSecret(jwtSecret);
  return new SignJWT({ ent: entitlement, ent_exp: entitlementUntil, sid: sessionId })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(userId)
    .setIssuer(JWT_ISSUER)
    .setAudience(JWT_AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${ACCESS_TOKEN_TTL_SEC}s`)
    .sign(secretKey);
}

export async function verifyAccessTokenClaims(
  token: string,
  jwtSecret: string | undefined
): Promise<VerifiedAccessClaims | null> {
  try {
    const secretKey = getValidatedJwtSecret(jwtSecret);
    const { payload } = await jwtVerify(token, secretKey, {
      algorithms: ['HS256'],
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
    });
    if (typeof payload.sub !== 'string' || payload.sub.length === 0) return null;
    const ent = typeof payload.ent === 'string' ? payload.ent : 'free';
    const entExp = typeof payload.ent_exp === 'number' ? payload.ent_exp : 0;
    const sid = typeof payload.sid === 'string' ? payload.sid : '';
    return { userId: payload.sub, sessionId: sid, entitlement: ent, entitlementUntil: entExp };
  } catch {
    return null;
  }
}

export async function verifyAccessToken(
  token: string,
  jwtSecret: string | undefined
): Promise<string | null> {
  const claims = await verifyAccessTokenClaims(token, jwtSecret);
  return claims ? claims.userId : null;
}

const googleLoginSchema = z.object({
  id_token: z.string().min(20).max(4096),
});

authRoutes.post(
  '/auth/google',
  rateLimit(10, 60),
  zValidator('json', googleLoginSchema),
  async (c) => {
    const { id_token } = c.req.valid('json');
    const db = c.env.DB;
    const nowSec = Math.floor(Date.now() / 1000);

    let googlePayload;
    try {
      const verified = await jwtVerify(id_token, GOOGLE_JWKS, {
        issuer: ['https://accounts.google.com', 'accounts.google.com'],
        audience: c.env.GOOGLE_CLIENT_ID,
      });
      googlePayload = verified.payload;
    } catch {
      return c.json({ status: 'error', message: 'Token Google tidak valid.' }, 401);
    }

    const sub = googlePayload.sub;
    const email = String(googlePayload.email || '');
    const emailVerified = Boolean(googlePayload.email_verified);
    const name = String(googlePayload.name || 'User');
    const photoUrl = String(googlePayload.picture || '');

    if (!sub || !email || !emailVerified) {
      return c.json(
        { status: 'error', message: 'Akun Google harus memiliki email terverifikasi.' },
        400
      );
    }

    await db
      .prepare(
        `INSERT INTO users (id, email, name, photo_url, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?5)
         ON CONFLICT(id) DO UPDATE SET
           email = excluded.email,
           name = excluded.name,
           photo_url = excluded.photo_url,
           updated_at = excluded.updated_at`
      )
      .bind(sub, email, name, photoUrl, nowSec)
      .run();

    const { results: activeSessions } = await db
      .prepare(
        `SELECT id FROM user_sessions
         WHERE user_id = ?1 AND is_revoked = 0 AND expires_at > ?2
         ORDER BY rotated_at ASC`
      )
      .bind(sub, nowSec)
      .all<{ id: string }>();

    const statements: D1PreparedStatement[] = [];
    if (activeSessions && activeSessions.length >= MAX_ACTIVE_SESSIONS) {
      const toRevokeCount = activeSessions.length - MAX_ACTIVE_SESSIONS + 1;
      for (let i = 0; i < toRevokeCount; i++) {
        statements.push(
          db
            .prepare(`UPDATE user_sessions SET is_revoked = 1 WHERE id = ?1`)
            .bind(activeSessions[i].id)
        );
      }
    }

    const sessionId = crypto.randomUUID();
    const initialGeneration = 1;
    const refreshToken = await deriveDeterministicRefreshToken(
      sessionId,
      initialGeneration,
      c.env.JWT_SECRET
    );
    const refreshTokenHash = await sha256Hex(refreshToken);

    statements.push(
      db
        .prepare(
          `INSERT INTO user_sessions (
             id, user_id, generation, current_token_hash, previous_token_hash,
             rotated_at, expires_at, is_revoked, created_at
           ) VALUES (?1, ?2, ?3, ?4, NULL, ?5, ?6, 0, ?5)`
        )
        .bind(
          sessionId,
          sub,
          initialGeneration,
          refreshTokenHash,
          nowSec,
          nowSec + REFRESH_TOKEN_TTL_SEC
        )
    );

    await db.batch(statements);

    const ent = await readUserEntitlement(db, sub, nowSec);
    const accessToken = await issueAccessToken(
      sub,
      c.env.JWT_SECRET,
      ent.entitlement,
      ent.entitlementUntil,
      sessionId
    );
    const proofKey = await deriveSessionProofKey(sub, sessionId, c.env.JWT_SECRET);

    return c.json({
      status: 'ok',
      data: {
        access_token: accessToken,
        refresh_token: refreshToken,
        proof_key: proofKey,
        expires_in: ACCESS_TOKEN_TTL_SEC,
        entitlement: ent.entitlement,
        entitlement_until: ent.entitlementUntil,
        user: { id: sub, email, name, photo_url: photoUrl },
      },
    });
  }
);

const refreshSchema = z.object({
  refresh_token: z.string().min(32).max(256),
});

authRoutes.post(
  '/auth/refresh',
  rateLimit(20, 60),
  zValidator('json', refreshSchema),
  async (c) => {
    const { refresh_token } = c.req.valid('json');
    const db = c.env.DB;
    const nowSec = Math.floor(Date.now() / 1000);
    const incomingHash = await sha256Hex(refresh_token);

    const session = await db
      .prepare(
        `SELECT id, user_id, generation, current_token_hash, previous_token_hash,
                rotated_at, expires_at, is_revoked
         FROM user_sessions
         WHERE current_token_hash = ?1 OR previous_token_hash = ?1
         LIMIT 1`
      )
      .bind(incomingHash)
      .first<{
        id: string;
        user_id: string;
        generation: number;
        current_token_hash: string;
        previous_token_hash: string | null;
        rotated_at: number;
        expires_at: number;
        is_revoked: number;
      }>();

    if (!session || session.is_revoked === 1 || session.expires_at <= nowSec) {
      return c.json({ status: 'error', message: 'Sesi telah berakhir. Silakan login kembali.' }, 401);
    }

    if (session.current_token_hash === incomingHash) {
      const nextGeneration = session.generation + 1;
      let effectiveRefreshToken = await deriveDeterministicRefreshToken(
        session.id,
        nextGeneration,
        c.env.JWT_SECRET
      );
      const nextRefreshTokenHash = await sha256Hex(effectiveRefreshToken);

      const updateRes = await db
        .prepare(
          `UPDATE user_sessions
           SET generation = ?1,
               previous_token_hash = current_token_hash,
               current_token_hash = ?2,
               rotated_at = ?3,
               expires_at = ?4
           WHERE id = ?5 AND generation = ?6 AND is_revoked = 0`
        )
        .bind(
          nextGeneration,
          nextRefreshTokenHash,
          nowSec,
          nowSec + REFRESH_TOKEN_TTL_SEC,
          session.id,
          session.generation
        )
        .run();

      if ((updateRes.meta?.changes ?? 0) === 0) {
        const recheck = await db
          .prepare(`SELECT is_revoked, generation FROM user_sessions WHERE id = ?1`)
          .bind(session.id)
          .first<{ is_revoked: number; generation: number }>();

        if (!recheck || recheck.is_revoked === 1) {
          return c.json({ status: 'error', message: 'Sesi telah berakhir.' }, 401);
        }
        effectiveRefreshToken = await deriveDeterministicRefreshToken(
          session.id,
          recheck.generation,
          c.env.JWT_SECRET
        );
      }

      const ent = await readUserEntitlement(db, session.user_id, nowSec);
      const accessToken = await issueAccessToken(
        session.user_id,
        c.env.JWT_SECRET,
        ent.entitlement,
        ent.entitlementUntil,
        session.id
      );
      const proofKey = await deriveSessionProofKey(session.user_id, session.id, c.env.JWT_SECRET);
      return c.json({
        status: 'ok',
        data: {
          access_token: accessToken,
          refresh_token: effectiveRefreshToken,
          proof_key: proofKey,
          expires_in: ACCESS_TOKEN_TTL_SEC,
          entitlement: ent.entitlement,
          entitlement_until: ent.entitlementUntil,
        },
      });
    }

    const elapsedSinceRotation = nowSec - session.rotated_at;
    if (elapsedSinceRotation <= GRACE_WINDOW_SEC) {
      const currentRefreshToken = await deriveDeterministicRefreshToken(
        session.id,
        session.generation,
        c.env.JWT_SECRET
      );
      const ent = await readUserEntitlement(db, session.user_id, nowSec);
      const accessToken = await issueAccessToken(
        session.user_id,
        c.env.JWT_SECRET,
        ent.entitlement,
        ent.entitlementUntil,
        session.id
      );
      const proofKey = await deriveSessionProofKey(session.user_id, session.id, c.env.JWT_SECRET);

      return c.json({
        status: 'ok',
        data: {
          access_token: accessToken,
          refresh_token: currentRefreshToken,
          proof_key: proofKey,
          expires_in: ACCESS_TOKEN_TTL_SEC,
          entitlement: ent.entitlement,
          entitlement_until: ent.entitlementUntil,
        },
      });
    }

    await db
      .prepare(`UPDATE user_sessions SET is_revoked = 1 WHERE id = ?1`)
      .bind(session.id)
      .run();

    return c.json(
      { status: 'error', message: 'Sesi terdeteksi digunakan di perangkat lain dan telah dicabut.' },
      401
    );
  }
);

authRoutes.post(
  '/auth/logout',
  rateLimit(10, 60),
  zValidator('json', refreshSchema),
  async (c) => {
    const { refresh_token } = c.req.valid('json');
    const nowSec = Math.floor(Date.now() / 1000);
    const incomingHash = await sha256Hex(refresh_token);

    await c.env.DB.prepare(
      `UPDATE user_sessions
       SET is_revoked = 1
       WHERE current_token_hash = ?1
          OR (previous_token_hash = ?1 AND (?2 - rotated_at) <= ?3)`
    )
      .bind(incomingHash, nowSec, GRACE_WINDOW_SEC)
      .run();

    return c.json({ status: 'ok' });
  }
);

authRoutes.use('/user/*', rateLimit(30, 60), async (c, next) => {
  const authHeader = c.req.header('Authorization') || '';
  if (!authHeader.startsWith('Bearer ')) {
    return c.json({ status: 'error', message: 'Unauthorized' }, 401);
  }

  const token = authHeader.slice(7).trim();
  const claims = await verifyAccessTokenClaims(token, c.env.JWT_SECRET);
  if (!claims) {
    return c.json({ status: 'error', message: 'Token tidak valid atau kedaluwarsa.' }, 401);
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
    return c.json({ status: 'error', message: 'Tanda tangan permintaan tidak valid.' }, 401);
  }

  c.set('userId', claims.userId);
  await next();
});

authRoutes.get('/user/state', async (c) => {
  const userId = c.get('userId');
  const db = c.env.DB;

  const nowSec = Math.floor(Date.now() / 1000);
  const [historiesRes, bookmarksRes, userRes] = await db.batch([
    db
      .prepare(
        `SELECT anime_id, episode_id, progress_sec, max_progress_sec, updated_at
         FROM user_histories
         WHERE user_id = ?1
         ORDER BY updated_at DESC
         LIMIT 50`
      )
      .bind(userId),
    db
      .prepare(
        `SELECT anime_id, pinned, updated_at
         FROM user_bookmarks
         WHERE user_id = ?1
         ORDER BY pinned DESC, updated_at DESC
         LIMIT 500`
      )
      .bind(userId),
    db
      .prepare(`SELECT entitlement, entitlement_until FROM users WHERE id = ?1`)
      .bind(userId),
  ]);

  const uRow = userRes.results?.[0] as { entitlement?: string; entitlement_until?: number | null } | undefined;
  const rawEnt = uRow?.entitlement === 'premium' ? 'premium' : 'free';
  const rawUntil = uRow?.entitlement_until ?? null;
  const entActive = rawEnt === 'premium' && (rawUntil === null || rawUntil > nowSec);
  const ent = {
    entitlement: entActive ? 'premium' : 'free',
    entitlementUntil: entActive ? rawUntil : null,
  };
  return c.json({
    status: 'ok',
    data: {
      entitlement: ent.entitlement,
      entitlement_until: ent.entitlementUntil,
      histories: historiesRes.results ?? [],
      bookmarks: bookmarksRes.results ?? [],
    },
  });
});

const putHistorySchema = z
  .object({
    anime_id: z.number().int().positive(),
    episode_id: z.number().int().positive(),
    progress_sec: z.number().int().nonnegative().max(86400),
    max_progress_sec: z.number().int().positive().max(86400),
  })
  .refine((d) => d.progress_sec <= d.max_progress_sec, {
    message: 'progress_sec tidak boleh melebihi max_progress_sec.',
  });

authRoutes.put('/user/history', zValidator('json', putHistorySchema), async (c) => {
  const userId = c.get('userId');
  const { anime_id, episode_id, progress_sec, max_progress_sec } = c.req.valid('json');
  const nowSec = Math.floor(Date.now() / 1000);

  const res = await c.env.DB.prepare(
    `INSERT INTO user_histories (user_id, anime_id, episode_id, progress_sec, max_progress_sec, updated_at)
     SELECT ?1, ?2, ?3, ?4, ?5, ?6
     WHERE EXISTS (SELECT 1 FROM episodes WHERE id = ?3 AND anime_id = ?2)
     ON CONFLICT(user_id, episode_id) DO UPDATE SET
       progress_sec = excluded.progress_sec,
       max_progress_sec = excluded.max_progress_sec,
       updated_at = excluded.updated_at`
  )
    .bind(userId, anime_id, episode_id, progress_sec, max_progress_sec, nowSec)
    .run();

  if ((res.meta?.changes ?? 0) === 0) {
    return c.json({ status: 'error', message: 'Episode tidak ditemukan.' }, 404);
  }

  return c.json({ status: 'ok' });
});

const putBookmarkSchema = z.object({
  anime_id: z.number().int().positive(),
  pinned: z.boolean().default(false),
});

authRoutes.put('/user/bookmark', zValidator('json', putBookmarkSchema), async (c) => {
  const userId = c.get('userId');
  const { anime_id, pinned } = c.req.valid('json');
  const nowSec = Math.floor(Date.now() / 1000);

  const res = await c.env.DB.prepare(
    `INSERT INTO user_bookmarks (user_id, anime_id, pinned, updated_at)
     SELECT ?1, ?2, ?3, ?4
     WHERE EXISTS (SELECT 1 FROM animes WHERE id = ?2)
       AND (
         EXISTS (SELECT 1 FROM user_bookmarks WHERE user_id = ?1 AND anime_id = ?2)
         OR (SELECT COUNT(*) FROM user_bookmarks WHERE user_id = ?1) < 500
       )
     ON CONFLICT(user_id, anime_id) DO UPDATE SET
       pinned = excluded.pinned,
       updated_at = excluded.updated_at`
  )
    .bind(userId, anime_id, pinned ? 1 : 0, nowSec)
    .run();

  if ((res.meta?.changes ?? 0) === 0) {
    return c.json({ status: 'error', message: 'Anime tidak ditemukan.' }, 404);
  }

  return c.json({ status: 'ok' });
});

authRoutes.delete('/user/bookmark/:animeId', async (c) => {
  const userId = c.get('userId');
  const animeId = Number(c.req.param('animeId'));
  if (!Number.isInteger(animeId) || animeId <= 0) {
    return c.json({ status: 'error', message: 'ID anime tidak valid.' }, 400);
  }

  await c.env.DB.prepare(
    `DELETE FROM user_bookmarks WHERE user_id = ?1 AND anime_id = ?2`
  )
    .bind(userId, animeId)
    .run();

  return c.json({ status: 'ok' });
});
