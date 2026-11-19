import { Context, Next } from 'hono';

export interface EdgeCacheOptions {
  immutable?: boolean;
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
  const h = new Headers({ ETag: etag, 'X-Milkglass-Cache': 'REVALIDATED' });
  if (cacheControl) h.set('Cache-Control', cacheControl);
  return new Response(null, { status: 304, headers: h });
}

export function edgeCache(
  sMaxAgeSeconds: number,
  staleWhileRevalidateSeconds = 600,
  allowQueryParams = false,
  options: EdgeCacheOptions = {}
) {
  return async (c: Context, next: Next) => {
    if (c.req.method !== 'GET') {
      return next();
    }

    const cache = (caches as unknown as { default: Cache }).default;
    const normalizedUrl = new URL(c.req.url);
    if (allowQueryParams) {
      normalizedUrl.searchParams.sort();
    } else {
      normalizedUrl.search = '';
    }

    const cacheKey = new Request(normalizedUrl.toString(), {
      method: 'GET',
    });

    const cachedResponse = await cache.match(cacheKey);
    if (cachedResponse) {
      if (etagMatches(c.req.header('If-None-Match'), cachedResponse.headers.get('ETag'))) {
        return notModified(
          cachedResponse.headers.get('ETag') as string,
          cachedResponse.headers.get('Cache-Control')
        );
      }
      const hitHeaders = new Headers(cachedResponse.headers);
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
      const cacheControl = options.immutable
        ? `public, max-age=${sMaxAgeSeconds}, s-maxage=${sMaxAgeSeconds}, immutable`
        : `public, s-maxage=${sMaxAgeSeconds}, stale-while-revalidate=${staleWhileRevalidateSeconds}`;
      mutableHeaders.set('Cache-Control', cacheControl);
      mutableHeaders.set('ETag', etag);
      mutableHeaders.set('X-Milkglass-Cache', 'MISS');

      c.executionCtx.waitUntil(
        cache.put(cacheKey, new Response(bodyText, { status: 200, headers: mutableHeaders }))
      );
      c.res = etagMatches(c.req.header('If-None-Match'), etag)
        ? notModified(etag, cacheControl)
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
