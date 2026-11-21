import { sha1Hex } from '../utils/crypto';
import { Context, Next } from 'hono';

export interface EdgeCacheOptions {
  immutable?: boolean;
  allowedQueryParams?: string[];
  privateClientCache?: boolean;
  varyByAsn?: boolean;
  normalizeTitleParam?: boolean;
}

const CORS_RESPONSE_HEADERS = [
  'Access-Control-Allow-Origin',
  'Access-Control-Allow-Credentials',
  'Access-Control-Allow-Headers',
  'Access-Control-Allow-Methods',
  'Access-Control-Expose-Headers',
  'Access-Control-Max-Age',
];

function stripCorsHeaders(headers: Headers): void {
  for (const name of CORS_RESPONSE_HEADERS) {
    headers.delete(name);
  }
}

function appendVaryOrigin(headers: Headers): void {
  const existing = headers.get('Vary');
  if (!existing) {
    headers.set('Vary', 'Origin');
    return;
  }
  const parts = existing
    .split(',')
    .map((p) => p.trim())
    .filter(Boolean);
  const seen = new Set(parts.map((p) => p.toLowerCase()));
  if (!seen.has('origin')) {
    parts.push('Origin');
  }
  headers.set('Vary', parts.join(', '));
}

function stripVaryOrigin(headers: Headers): void {
  const existing = headers.get('Vary');
  if (!existing) return;
  const remaining = existing
    .split(',')
    .map((p) => p.trim())
    .filter((p) => p.length > 0 && p.toLowerCase() !== 'origin');
  if (remaining.length === 0) {
    headers.delete('Vary');
  } else {
    headers.set('Vary', remaining.join(', '));
  }
}

export function normalizeCacheTitle(raw: string): string {
  return raw.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ').slice(0, 120);
}

async function weakEtag(body: string): Promise<string> {
  return `W/"${await sha1Hex(body, 10)}"`;
}

function etagMatches(ifNoneMatch: string | undefined, etag: string | null): boolean {
  if (!ifNoneMatch || !etag) return false;
  if (ifNoneMatch.trim() === '*') return true;
  const strip = (v: string) => v.trim().replace(/^W\//, '');
  const want = strip(etag);
  return ifNoneMatch.split(',').some((t) => strip(t) === want);
}

function notModified(etag: string, cacheControl: string | null, varyHeader: string | null = null): Response {
  const h = new Headers({
    ETag: etag,
    'X-Milkglass-Cache': 'REVALIDATED',
  });
  if (varyHeader) h.set('Vary', varyHeader);
  // Global cors() middleware appends Vary: Origin; strip Origin here to avoid "Origin, Origin".
  stripVaryOrigin(h);
  if (cacheControl) h.set('Cache-Control', cacheControl);
  return new Response(null, { status: 304, headers: h });
}

export function edgeCache(
  sMaxAgeSeconds: number,
  staleWhileRevalidateSeconds = 600,
  allowQueryParams = false,
  options: EdgeCacheOptions = {}
) {
  const allowedSet =
    options.allowedQueryParams && options.allowedQueryParams.length > 0
      ? new Set(options.allowedQueryParams)
      : null;

  return async (c: Context, next: Next) => {
    if (c.req.method !== 'GET') {
      return next();
    }

    const cache = (caches as unknown as { default: Cache }).default;
    const normalizedUrl = new URL(c.req.url);
    if (allowedSet) {
      const filtered = new URLSearchParams();
      for (const [key, rawValue] of normalizedUrl.searchParams.entries()) {
        if (allowedSet.has(key)) {
          const value =
            options.normalizeTitleParam && key === 'title'
              ? normalizeCacheTitle(rawValue)
              : rawValue.trim();
          if (value.length > 0) {
            filtered.set(key, value);
          }
        }
      }
      filtered.sort();
      const qs = filtered.toString();
      normalizedUrl.search = qs ? `?${qs}` : '';
    } else if (allowQueryParams) {
      normalizedUrl.searchParams.sort();
    } else {
      normalizedUrl.search = '';
    }

    if (options.varyByAsn) {
      const rawAsn = Number((c.req.raw as unknown as { cf?: { asn?: number } })?.cf?.asn ?? 0);
      const asn = Number.isInteger(rawAsn) && rawAsn > 0 ? rawAsn : 0;
      normalizedUrl.searchParams.set('__asn', String(asn));
    }
    const cacheKey = new Request(normalizedUrl.toString(), {
      method: 'GET',
    });

    const cachedResponse = await cache.match(cacheKey);
    if (cachedResponse) {
      const hitCacheControl = options.privateClientCache
        ? 'private, no-store'
        : cachedResponse.headers.get('Cache-Control');
      if (etagMatches(c.req.header('If-None-Match'), cachedResponse.headers.get('ETag'))) {
        return notModified(
          cachedResponse.headers.get('ETag') as string,
          hitCacheControl,
          cachedResponse.headers.get('Vary')
        );
      }
      const hitHeaders = new Headers(cachedResponse.headers);
      stripCorsHeaders(hitHeaders);
      // Global cors() middleware appends Vary: Origin on exit; avoid duplicating "Origin, Origin".
      stripVaryOrigin(hitHeaders);
      if (options.privateClientCache) {
        hitHeaders.set('Cache-Control', 'private, no-store');
      }
      hitHeaders.set('X-Milkglass-Cache', 'HIT');
      return new Response(cachedResponse.body, {
        status: cachedResponse.status,
        headers: hitHeaders,
      });
    }

    await next();

    const skipCache = c.res.headers.get('X-Milkglass-NoCache') === '1';
    if (skipCache) {
      const h = new Headers(c.res.headers);
      h.delete('X-Milkglass-NoCache');
      h.delete('X-Milkglass-ShortCache');
      h.set('Cache-Control', 'no-store');
      c.res = new Response(c.res.body, { status: c.res.status, headers: h });
      return;
    }

    if (c.res.status === 200) {
      const shortCacheRaw = c.res.headers.get('X-Milkglass-ShortCache');
      const shortCacheSec = shortCacheRaw !== null ? Number(shortCacheRaw) : NaN;
      const hasShortCache = Number.isFinite(shortCacheSec) && shortCacheSec > 0;
      const effectiveSMaxAge = hasShortCache
        ? Math.min(sMaxAgeSeconds, Math.floor(shortCacheSec))
        : sMaxAgeSeconds;
      const effectiveSwr = hasShortCache ? 0 : staleWhileRevalidateSeconds;

      const bodyText = await c.res.text();
      const etag = await weakEtag(bodyText);
      const mutableHeaders = new Headers(c.res.headers);
      mutableHeaders.delete('X-Milkglass-ShortCache');
      mutableHeaders.delete('X-Milkglass-NoCache');

      const internalCacheControl =
        options.immutable && !hasShortCache
          ? `public, max-age=${effectiveSMaxAge}, s-maxage=${effectiveSMaxAge}, immutable`
          : `public, s-maxage=${effectiveSMaxAge}, stale-while-revalidate=${effectiveSwr}`;
      const clientCacheControl = options.privateClientCache
        ? 'private, no-store'
        : internalCacheControl;

      mutableHeaders.set('ETag', etag);
      mutableHeaders.set('X-Milkglass-Cache', 'MISS');

      const cacheableHeaders = new Headers(mutableHeaders);
      appendVaryOrigin(cacheableHeaders);
      cacheableHeaders.set('Cache-Control', internalCacheControl);
      stripCorsHeaders(cacheableHeaders);

      // Global cors() middleware appends Vary: Origin on exit; avoid duplicating "Origin, Origin".
      stripVaryOrigin(mutableHeaders);
      mutableHeaders.set('Cache-Control', clientCacheControl);

      c.executionCtx.waitUntil(
        cache.put(cacheKey, new Response(bodyText, { status: 200, headers: cacheableHeaders }))
      );
      c.res = etagMatches(c.req.header('If-None-Match'), etag)
        ? notModified(etag, clientCacheControl, mutableHeaders.get('Vary'))
        : new Response(bodyText, { status: 200, headers: mutableHeaders });
    }
  };
}

export async function purgeEdgeCacheForIngest(
  requestUrl: string,
  animeId: number,
  episodeId: number
): Promise<void> {
  try {
    const origin = new URL(requestUrl).origin;
    const cache = (caches as unknown as { default: Cache }).default;
    const urlsToPurge = [
      `${origin}/v1/catalog/home`,
      `${origin}/v1/catalog/anime/${animeId}`,
      `${origin}/v1/catalog/episode/${episodeId}`,
    ];
    await Promise.all(
      urlsToPurge.map((u) => cache.delete(new Request(u, { method: 'GET' })))
    );
  } catch {
  }
}

export async function purgeHomeCache(requestUrl: string): Promise<void> {
  try {
    const origin = new URL(requestUrl).origin;
    const cache = (caches as unknown as { default: Cache }).default;
    await Promise.all(
      ['home', 'status'].map((name) =>
        cache.delete(new Request(`${origin}/v1/catalog/${name}`, { method: 'GET' }))
      )
    );
  } catch {
  }
}

export async function purgeSourcesCache(requestUrl: string): Promise<void> {
  try {
    const origin = new URL(requestUrl).origin;
    const cache = (caches as unknown as { default: Cache }).default;
    await Promise.all(
      ['sources', 'rules'].map((name) =>
        cache.delete(new Request(`${origin}/v1/catalog/${name}`, { method: 'GET' }))
      )
    );
  } catch {
  }
}

export async function purgePlayResolveCache(
  requestUrl: string,
  title: string,
  ep: number
): Promise<void> {
  try {
    const origin = new URL(requestUrl).origin;
    const cache = (caches as unknown as { default: Cache }).default;
    const normTitle = normalizeCacheTitle(title);
    const u = new URL(`${origin}/v1/play/resolve`);
    u.searchParams.set('ep', String(ep));
    u.searchParams.set('title', normTitle);
    u.searchParams.sort();
    await cache.delete(new Request(u.toString(), { method: 'GET' }));
  } catch {
  }
}
