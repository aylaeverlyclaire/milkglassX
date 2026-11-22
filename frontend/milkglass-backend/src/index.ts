import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { catalogRoutes } from './routes/catalog.routes';
import { systemRoutes } from './routes/system.routes';
import { ingestRoutes } from './routes/ingest.routes';
import { authRoutes } from './routes/auth.routes';
import { cfRoutes } from './routes/cf.routes';
import { playRoutes } from './routes/play.routes';
import { aggregateSourceHealthCron } from './services/health.service';
import { backfillIncompleteAnime, syncAniListCatalog } from './services/anilist.service';

export interface AppBindings {
  DB: D1Database;
  ADMIN_SECRET: string;
  JWT_SECRET: string;
  GOOGLE_CLIENT_ID: string;
  MASTER_APP_SECRET?: string;
  CF_HANDLER_URL?: string;
  CF_HANDLER_TOKEN?: string;
  CF_REQUIRE_AUTH?: string;
  CATALOG_R2?: R2Bucket;
  ENVIRONMENT?: string;
  GITHUB_TELEMETRY_TOKEN?: string;
  GITHUB_TELEMETRY_REPO?: string;
}

const MAX_PUBLIC_BODY_BYTES = 64 * 1024;

function isAllowedOrigin(origin: string): boolean {
  try {
    const u = new URL(origin);
    if (u.protocol !== 'https:') return false;
    const h = u.hostname.toLowerCase();
    return h === 'milkglass.my.id' || h.endsWith('.milkglass.my.id');
  } catch {
    return false;
  }
}

const app = new Hono<{ Bindings: AppBindings }>();

app.use(
  '*',
  cors({
    origin: (origin) => (origin && isAllowedOrigin(origin) ? origin : ''),
    allowMethods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowHeaders: ['Authorization', 'Content-Type', 'If-None-Match', 'X-Admin-Secret', 'X-Milkglass-Ts', 'X-Milkglass-Sig'],
    exposeHeaders: ['X-Milkglass-Server-Sig', 'X-Milkglass-Server-Time', 'ETag', 'Retry-After', 'X-Milkglass-Cache', 'X-Milkglass-Source'],
    maxAge: 600,
  })
);

app.use('*', async (c, next) => {
  if (!c.req.path.startsWith('/v1/internal/')) {
    const contentLength = Number(c.req.header('Content-Length') || '0');
    if (Number.isFinite(contentLength) && contentLength > MAX_PUBLIC_BODY_BYTES) {
      return c.json({ status: 'error', message: 'Payload terlalu besar.' }, 413);
    }
  }
  await next();
  if (c.res.status === 401 && !c.res.headers.has('X-Milkglass-Server-Time')) {
    c.header('X-Milkglass-Server-Time', String(Math.floor(Date.now() / 1000)));
  }
  c.header('X-Content-Type-Options', 'nosniff');
  c.header('X-Frame-Options', 'DENY');
  c.header('Referrer-Policy', 'no-referrer');
});

app.onError((err, c) => {
  console.error('[MilkglassWorkerError]', err.message);
  return c.json(
    {
      status: 'error',
      message: 'Server lagi sibuk.',
    },
    500
  );
});

app.notFound((c) => {
  return c.json({ status: 'error', message: 'Endpoint tidak ditemukan.' }, 404);
});

app.route('/v1/catalog', catalogRoutes);
app.route('/v1/cf', cfRoutes);
app.route('/v1/play', playRoutes);
app.route('/v1', systemRoutes);
app.route('/v1/internal', ingestRoutes);
app.route('/v1', authRoutes);

export default {
  fetch: app.fetch,

  async scheduled(event: ScheduledEvent, env: AppBindings, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      (async () => {
        await aggregateSourceHealthCron(env.DB);

        const minutes = new Date(event.scheduledTime).getUTCMinutes();
        if (minutes === 0) {
          await syncAniListCatalog(env.DB, 1, 50, 3);
        }
        try {
          await backfillIncompleteAnime(env.DB, 5);
        } catch (err: unknown) {
          console.error('[cron] backfillIncompleteAnime failed', err instanceof Error ? err.message : String(err));
        }
      })()
    );
  },
};
