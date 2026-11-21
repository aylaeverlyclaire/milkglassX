import { sha256Hex } from '../utils/crypto';
import { Context, Next } from 'hono';

export interface RateBucket {
  count: number;
  resetAt: number;
}

const DEFAULT_MAX_BUCKETS_PER_ROUTE = 1000;

function evictBucketsIfNeeded(
  buckets: Map<string, RateBucket>,
  nowSec: number,
  maxBuckets: number
): void {
  if (buckets.size <= maxBuckets) return;
  let scanned = 0;
  for (const [k, v] of buckets.entries()) {
    if (v.resetAt <= nowSec) buckets.delete(k);
    if (++scanned >= 32) break;
  }
  while (buckets.size > maxBuckets) {
    const oldestKey = buckets.keys().next().value;
    if (!oldestKey) break;
    buckets.delete(oldestKey);
  }
}

export function checkBoundedQuota(
  buckets: Map<string, RateBucket>,
  key: string,
  maxRequests: number,
  windowSeconds: number,
  maxBuckets = 2000
): { allowed: boolean; retryAfter: number } {
  const now = Math.floor(Date.now() / 1000);
  const existing = buckets.get(key);

  if (!existing || existing.resetAt <= now) {
    if (existing) buckets.delete(key);
    buckets.set(key, { count: 1, resetAt: now + windowSeconds });
    evictBucketsIfNeeded(buckets, now, maxBuckets);
    return { allowed: true, retryAfter: 0 };
  }

  existing.count += 1;
  // Refresh LRU order on access
  buckets.delete(key);
  buckets.set(key, existing);

  if (existing.count > maxRequests) {
    return { allowed: false, retryAfter: Math.max(1, existing.resetAt - now) };
  }
  return { allowed: true, retryAfter: 0 };
}

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

export function rateLimit(maxRequests: number, windowSeconds: number, maxBuckets = DEFAULT_MAX_BUCKETS_PER_ROUTE) {
  const routeBuckets = new Map<string, RateBucket>();

  return async (c: Context, next: Next) => {
    const rawIp = c.req.header('CF-Connecting-IP') || '0.0.0.0';
    const clientIp = normalizeClientIp(rawIp);
    const normalizedRoute = c.req.routePath || c.req.path;
    const routeKey = `${c.req.method}:${normalizedRoute}:${clientIp}`;

    const quota = checkBoundedQuota(routeBuckets, routeKey, maxRequests, windowSeconds, maxBuckets);
    if (!quota.allowed) {
      c.header('Retry-After', String(quota.retryAfter));
      return c.json({ status: 'error', message: 'Server lagi sibuk.' }, 429);
    }

    await next();
  };
}

export async function hashClientIp(rawIp: string): Promise<string> {
  const normalizedIp = normalizeClientIp(rawIp);
  const daySalt = Math.floor(Date.now() / 86400000).toString();
  return sha256Hex(`${normalizedIp}:${daySalt}`, 8);
}
