import { Context, Next } from 'hono';

export interface EdgeCacheOptions {
  immutable?: boolean;
  allowedQueryParams?: string[];
  privateClientCache?: boolean;
  varyByAsn?: boolean;
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
    .map((p) => p.trim().toLowerCase())
    .filter(Boolean);
  if (!parts.includes('origin')) {
    headers.set('Vary', `${existing}, Origin`);
  }
}

async function weakEtag(body: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(body));
  const hex = Array.from(new Uint8Array(digest).slice(0, 10))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  return `W/"${hex}"`;
}

function etagMatches(ifNoneMatch: string | undefined, etag: string | null): boolean {
  if (!ifNoneMatch || !etag) return false;
  if (ifNoneMatch.trim() === '*') return true;
  const strip = (v: string) => v.trim().replace(/^W\//, '');
  const want = strip(etag);
  return ifNoneMatch.split(',').some((t) => strip(t) === want);
}

function notModified(etag: string, cacheControl: string | null): Response {
  const h = new Headers({
    ETag: etag,
    'X-Milkglass-Cache': 'REVALIDATED',
    Vary: 'Origin',
  });
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
      for (const [key, value] of normalizedUrl.searchParams.entries()) {
        if (allowedSet.has(key)) {
          filtered.append(key, value);
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
          hitCacheControl
        );
      }
      const hitHeaders = new Headers(cachedResponse.headers);
      stripCorsHeaders(hitHeaders);
      appendVaryOrigin(hitHeaders);
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
      h.set('Cache-Control', 'no-store');
      c.res = new Response(c.res.body, { status: c.res.status, headers: h });
      return;
    }

    if (c.res.status === 200) {
      const bodyText = await c.res.text();
      const etag = await weakEtag(bodyText);
      const mutableHeaders = new Headers(c.res.headers);
      const shortCacheSec = Number(c.res.headers.get('X-Milkglass-ShortCache') || '0');
      mutableHeaders.delete('X-Milkglass-ShortCache');
      const edgeControl = shortCacheSec > 0
        ? `public, s-maxage=${shortCacheSec}, stale-while-revalidate=${shortCacheSec}`
        : options.immutable
          ? `public, max-age=${sMaxAgeSeconds}, s-maxage=${sMaxAgeSeconds}, immutable`
          : `public, s-maxage=${sMaxAgeSeconds}, stale-while-revalidate=${staleWhileRevalidateSeconds}`;
      const clientCacheControl = options.privateClientCache
        ? 'private, no-store'
        : edgeControl;

      mutableHeaders.set('ETag', etag);
      appendVaryOrigin(mutableHeaders);
      mutableHeaders.set('X-Milkglass-Cache', 'MISS');

      const cacheableHeaders = new Headers(mutableHeaders);
      cacheableHeaders.set('Cache-Control', edgeControl);
      stripCorsHeaders(cacheableHeaders);

      mutableHeaders.set('Cache-Control', clientCacheControl);

      c.executionCtx.waitUntil(
        cache.put(cacheKey, new Response(bodyText, { status: 200, headers: cacheableHeaders }))
      );
      c.res = etagMatches(c.req.header('If-None-Match'), etag)
        ? notModified(etag, clientCacheControl)
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
    await cache.delete(new Request(`${origin}/v1/catalog/sources`, { method: 'GET' }));
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
    const u = new URL(`${origin}/v1/play/resolve`);
    u.searchParams.set('ep', String(ep));
    u.searchParams.set('title', title);
    u.searchParams.sort();
    const cache = (caches as unknown as { default: Cache }).default;
    await cache.delete(new Request(u.toString(), { method: 'GET' }));
  } catch {
  }
}
