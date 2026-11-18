import { Hono } from 'hono';
import { zValidator } from '@hono/zod-validator';
import { z } from 'zod';
import { rateLimit, hashClientIp } from '../middleware/rateLimit';
import { AppBindings } from '../index';

export const systemRoutes = new Hono<{ Bindings: AppBindings }>();

const telemetryBodySchema = z.object({
  source_id: z.number().int().positive(),
  reason: z.enum(['http_403', 'expired', 'not_found', 'timeout', 'codec', 'dns']),
});

systemRoutes.post(
  '/telemetry/source',
  rateLimit(10, 60),
  zValidator('json', telemetryBodySchema),
  async (c) => {
    const { source_id, reason } = c.req.valid('json');

    if (reason === 'timeout' || reason === 'dns' || reason === 'codec') {
      return c.json({ status: 'ok', recorded: false });
    }

    const clientIp = c.req.header('CF-Connecting-IP') || '0.0.0.0';
    const ipHash = await hashClientIp(clientIp);
    const nowSec = Math.floor(Date.now() / 1000);

    const result = await c.env.DB.prepare(
      `INSERT OR IGNORE INTO source_telemetry_events (source_id, reason, ip_hash, created_at)
       SELECT ?1, ?2, ?3, ?4
       WHERE EXISTS (
         SELECT 1 FROM stream_sources WHERE id = ?1 AND is_active = 1
       )
       AND (
         SELECT COUNT(*) FROM source_telemetry_events WHERE source_id = ?1
       ) < 10`
    )
      .bind(source_id, reason, ipHash, nowSec)
      .run();

    const recorded = (result.meta?.changes ?? 0) > 0;
    return c.json({ status: 'ok', recorded });
  }
);
