import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { catalogRoutes } from './routes/catalog.routes';
import { systemRoutes } from './routes/system.routes';
import { ingestRoutes } from './routes/ingest.routes';
import { authRoutes } from './routes/auth.routes';
import { aggregateSourceHealthCron } from './services/health.service';
import { syncAniListCatalog } from './services/anilist.service';

export interface AppBindings {
  DB: D1Database;
  ADMIN_SECRET: string;
  JWT_SECRET: string;
  GOOGLE_CLIENT_ID: string;
  MASTER_APP_SECRET?: string;
}

const app = new Hono<{ Bindings: AppBindings }>();

app.use('*', cors());

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
      })()
    );
  },
};
