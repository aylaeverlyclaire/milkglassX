import { Context, Next } from 'hono';

interface RateBucket {
  count: number;
  resetAt: number;
}

const isolateBuckets = new Map<string, RateBucket>();
const MAX_BUCKETS = 5000;

export function normalizeClientIp(rawIp: string | undefined): string {
  if (!rawIp) return '0.0.0.0';
  const trimmed = rawIp.trim().toLowerCase();

  if (!trimmed.includes(':')) {
    return trimmed;
  }

  if (trimmed.startsWith('::ffff:') && trimmed.includes('.')) {
    return trimmed.slice(7);
  }

  const parts = trimmed.split('::');
  if (parts.length > 2) return '::/64';

  const left = parts[0] ? parts[0].split(':') : [];
  const right = parts.length === 2 && parts[1] ? parts[1].split(':') : [];
  const missing = Math.max(0, 8 - (left.length + right.length));
  const middle = Array(missing).fill('0000');

  const fullGroups = [...left, ...middle, ...right]
    .slice(0, 8)
    .map((g) => g.padStart(4, '0'));

  return `${fullGroups.slice(0, 4).join(':')}::/64`;
}

export function rateLimit(maxRequests: number, windowSeconds: number) {
  return async (c: Context, next: Next) => {
    const now = Math.floor(Date.now() / 1000);
    const rawIp =
      c.req.header('CF-Connecting-IP') ||
      '0.0.0.0';
    const clientIp = normalizeClientIp(rawIp);
    const normalizedRoute = c.req.routePath || c.req.path;
    const routeKey = `${c.req.method}:${normalizedRoute}:${clientIp}`;

    const existing = isolateBuckets.get(routeKey);
    if (!existing || existing.resetAt <= now) {
      isolateBuckets.set(routeKey, { count: 1, resetAt: now + windowSeconds });
    } else {
      existing.count += 1;
      if (existing.count > maxRequests) {
        const retryAfter = Math.max(1, existing.resetAt - now);
        c.header('Retry-After', String(retryAfter));
        return c.json(
          { status: 'error', message: 'Server lagi sibuk.' },
          429
        );
      }
    }

    if (isolateBuckets.size > MAX_BUCKETS) {
      for (const [k, v] of isolateBuckets.entries()) {
        if (v.resetAt <= now) isolateBuckets.delete(k);
      }
      while (isolateBuckets.size > MAX_BUCKETS) {
        const oldestKey = isolateBuckets.keys().next().value;
        if (!oldestKey) break;
        isolateBuckets.delete(oldestKey);
      }
    }

    await next();
  };
}

export async function hashClientIp(rawIp: string): Promise<string> {
  const normalizedIp = normalizeClientIp(rawIp);
  const daySalt = Math.floor(Date.now() / 86400000).toString();
  const data = new TextEncoder().encode(`${normalizedIp}:${daySalt}`);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest).slice(0, 8))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}
