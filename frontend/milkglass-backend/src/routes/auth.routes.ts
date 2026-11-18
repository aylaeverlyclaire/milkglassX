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

async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
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

export async function issueAccessToken(
  userId: string,
  jwtSecret: string | undefined
): Promise<string> {
  const secretKey = getValidatedJwtSecret(jwtSecret);
  return new SignJWT({})
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(userId)
    .setIssuer(JWT_ISSUER)
    .setAudience(JWT_AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${ACCESS_TOKEN_TTL_SEC}s`)
    .sign(secretKey);
}

export async function verifyAccessToken(
  token: string,
  jwtSecret: string | undefined
): Promise<string | null> {
  try {
    const secretKey = getValidatedJwtSecret(jwtSecret);
    const { payload } = await jwtVerify(token, secretKey, {
      algorithms: ['HS256'],
      issuer: JWT_ISSUER,
      audience: JWT_AUDIENCE,
    });
    return typeof payload.sub === 'string' && payload.sub.length > 0 ? payload.sub : null;
  } catch {
    return null;
  }
}

const googleLoginSchema = z.object({
  id_token: z.string().min(20),
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

    const accessToken = await issueAccessToken(sub, c.env.JWT_SECRET);

    return c.json({
      status: 'ok',
      data: {
        access_token: accessToken,
        refresh_token: refreshToken,
        expires_in: ACCESS_TOKEN_TTL_SEC,
        user: { id: sub, email, name, photo_url: photoUrl },
      },
    });
  }
);

const refreshSchema = z.object({
  refresh_token: z.string().min(32),
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

      const accessToken = await issueAccessToken(session.user_id, c.env.JWT_SECRET);
      return c.json({
        status: 'ok',
        data: {
          access_token: accessToken,
          refresh_token: effectiveRefreshToken,
          expires_in: ACCESS_TOKEN_TTL_SEC,
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
      const accessToken = await issueAccessToken(session.user_id, c.env.JWT_SECRET);

      return c.json({
        status: 'ok',
        data: {
          access_token: accessToken,
          refresh_token: currentRefreshToken,
          expires_in: ACCESS_TOKEN_TTL_SEC,
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
  const userId = await verifyAccessToken(token, c.env.JWT_SECRET);
  if (!userId) {
    return c.json({ status: 'error', message: 'Token tidak valid atau kedaluwarsa.' }, 401);
  }

  c.set('userId', userId);
  await next();
});

authRoutes.get('/user/state', async (c) => {
  const userId = c.get('userId');
  const db = c.env.DB;

  const [historiesRes, bookmarksRes] = await db.batch([
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
         ORDER BY pinned DESC, updated_at DESC`
      )
      .bind(userId),
  ]);

  return c.json({
    status: 'ok',
    data: {
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
