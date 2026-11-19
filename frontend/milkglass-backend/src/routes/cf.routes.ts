import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { edgeCache } from '../middleware/edgeCache';
import { rateLimit } from '../middleware/rateLimit';
import { AppBindings } from '../index';

export const cfRoutes = new Hono<{ Bindings: AppBindings }>();

function isSafePublicHttpsUrl(rawUrl: string): boolean {
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'https:') return false;
    const host = u.hostname.toLowerCase();
    if (
      !host ||
      host === 'localhost' ||
      host.endsWith('.local') ||
      host.includes(':') ||
      /^(127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/.test(host)
    ) {
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

cfRoutes.get('/status', (c) => {
  return c.json({
    status: 'ok',
    data: {
      configured: Boolean(c.env.CF_HANDLER_URL && c.env.CF_HANDLER_URL.trim().startsWith('http')),
    },
  });
});

const cookiesQuerySchema = z.object({
  url: z.string().url().max(2048),
});

cfRoutes.get(
  '/cookies',
  rateLimit(15, 60),
  edgeCache(900, 120, true),
  zValidator('query', cookiesQuerySchema),
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
    if (!isSafePublicHttpsUrl(url)) {
      c.header('X-Milkglass-NoCache', '1');
      return c.json(
        { status: 'error', message: 'URL target wajib HTTPS publik.' },
        400
      );
    }

    const targetOrigin = new URL(url).origin + '/';
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (c.env.CF_HANDLER_TOKEN && c.env.CF_HANDLER_TOKEN.trim().length > 0) {
      headers['x-auth-token'] = c.env.CF_HANDLER_TOKEN.trim();
    }

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
