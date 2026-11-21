export interface AnimasuSearchItem {
  slug: string;
  url: string;
  title: string;
  type: string;
  episodes_label: string;
  episodes_total: number;
  status_label: string;
  status: number;
  poster_url: string;
  source: 'animasu';
}

import { buildCfHandlerHeaders, getCfHandlerBaseUrl } from '../utils/cfHandler';
import { DEFAULT_SOURCE_URLS } from '../utils/sources';

export const DEFAULT_ANIMASU_BASE_URL = DEFAULT_SOURCE_URLS.animasu;

function decodeHtmlEntities(raw: string): string {
  return raw
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#039;|&#39;|&apos;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&#(\d+);/g, (_, num) => {
      const code = Number(num);
      return Number.isFinite(code) ? String.fromCharCode(code) : '';
    });
}

function stripTags(raw: string): string {
  return decodeHtmlEntities(raw.replace(/<[^>]+>/g, ' '))
    .replace(/\s+/g, ' ')
    .trim();
}

function parseEpisodeCount(epLabel: string): number {
  const m = /(\d+)/.exec(epLabel || '');
  return m ? Number(m[1]) : 0;
}

function parseStatusCode(statusLabel: string): number {
  const low = (statusLabel || '').toLowerCase();
  if (low.includes('tayang') || low.includes('ongoing') || low.includes('releasing')) return 1;
  if (low.includes('segera') || low.includes('upcoming') || low.includes('belum')) return 3;
  return 2;
}

function extractSlugFromUrl(url: string): string {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean);
    if (parts.length >= 2 && (parts[0] === 'anime' || parts[0] === 'nonton')) {
      return parts[1];
    }
    return parts[parts.length - 1] || '';
  } catch {
    return '';
  }
}

function resolveUrl(baseUrl: string, href: string): string {
  try {
    return new URL(href, baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`).toString();
  } catch {
    return href;
  }
}

/**
 * Deterministic positive integer ID in a dedicated namespace (900_000_000..1_899_999_999)
 * so Animasu-only search hits never collide with AniList IDs in D1/Android state.
 */
export function animasuSlugToStableId(slug: string): number {
  const clean = slug.trim().toLowerCase();
  let hash = 2166136261;
  for (let i = 0; i < clean.length; i++) {
    hash ^= clean.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  const positive = hash >>> 0;
  return 900_000_000 + (positive % 999_999_999);
}

/**
 * Lightweight regex/HTML parser for Animasu search results (`https://animasu.love/?s=<query>`).
 * Targets `.listupd > .bs > .bsx > a` cards (WordPress animestream theme).
 */
export function parseAnimasuSearchHtml(
  html: string,
  baseUrl: string = DEFAULT_ANIMASU_BASE_URL
): AnimasuSearchItem[] {
  if (!html || html.length < 100) return [];
  const items: AnimasuSearchItem[] = [];
  const seen = new Set<string>();

  // Restrict to .listupd container when present to avoid sidebar/nav links
  const listStart = html.indexOf('class="listupd"');
  const scope = listStart >= 0 ? html.slice(listStart, listStart + 350_000) : html;

  const anchorRe = /<a\b[^>]*\bhref="([^"]*\/anime\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m: RegExpExecArray | null;
  while ((m = anchorRe.exec(scope)) !== null) {
    const rawHref = decodeHtmlEntities(m[1]).trim();
    const inner = m[2];
    if (!inner.includes('class="tt"') && !inner.includes('class="limit"')) continue;

    const absUrl = resolveUrl(baseUrl, rawHref);
    if (!absUrl.startsWith('http') || seen.has(absUrl)) continue;
    seen.add(absUrl);

    const ttMatch = /<div\b[^>]*class="[^"]*\btt\b[^"]*"[^>]*>([\s\S]*?)<\/div>/i.exec(inner);
    let title = ttMatch ? stripTags(ttMatch[1]) : stripTags(inner);
    title = title
      .replace(/^Nonton\s+Anime\s+/i, '')
      .replace(/\s+Sub(?:title)?\s+Indo(?:nesia)?$/i, '')
      .trim();
    if (!title) continue;

    const typeMatch = /<div\b[^>]*class="[^"]*\btypez\b[^"]*"[^>]*>([\s\S]*?)<\/div>/i.exec(inner);
    const epMatch = /<span\b[^>]*class="[^"]*\bepx\b[^"]*"[^>]*>([\s\S]*?)<\/span>/i.exec(inner);
    const sbMatch = /<span\b[^>]*class="[^"]*\bsb\b[^"]*"[^>]*>([\s\S]*?)<\/span>/i.exec(inner);
    const imgMatch =
      /<img\b[^>]*\b(?:data-lazy-src|data-src|src)="([^"]+)"/i.exec(inner) ||
      /<img\b[^>]*\bsrc='([^']+)'/i.exec(inner);

    const animeType = typeMatch ? stripTags(typeMatch[1]) || 'TV' : 'TV';
    const epLabel = epMatch ? stripTags(epMatch[1]) : '';
    const statusLabel = sbMatch ? stripTags(sbMatch[1]) : '';
    const posterRaw = imgMatch ? decodeHtmlEntities(imgMatch[1]).trim() : '';
    const posterUrl = posterRaw ? resolveUrl(baseUrl, posterRaw) : '';
    const slug = extractSlugFromUrl(absUrl);
    if (!slug) continue;

    items.push({
      slug,
      url: absUrl,
      title,
      type: animeType,
      episodes_label: epLabel,
      episodes_total: parseEpisodeCount(epLabel),
      status_label: statusLabel,
      status: parseStatusCode(statusLabel),
      poster_url: posterUrl,
      source: 'animasu',
    });
    if (items.length >= 30) break;
  }

  return items;
}

export async function searchAnimasu(
  query: string,
  baseUrl: string = DEFAULT_ANIMASU_BASE_URL,
  cfHandlerUrl?: string,
  cfHandlerToken?: string
): Promise<AnimasuSearchItem[]> {
  const cleanQ = query.trim();
  if (cleanQ.length < 2) return [];
  const root = (baseUrl || DEFAULT_ANIMASU_BASE_URL).trim().replace(/\/+$/, '');
  const targetUrl = `${root}/?s=${encodeURIComponent(cleanQ)}`;

  try {
    const resp = await fetch(targetUrl, {
      method: 'GET',
      headers: {
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
        Referer: `${root}/`,
        'User-Agent':
          'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36',
      },
      signal: AbortSignal.timeout(4_000),
    });
    if (resp.ok) {
      const html = await resp.text();
      if (html.length > 200 && !html.toLowerCase().includes('cf-browser-verification')) {
        const parsed = parseAnimasuSearchHtml(html, root);
        if (parsed.length > 0) return parsed;
      }
    }
  } catch {
    // Fall through to cf-handler fallback if configured
  }

  const cfEnv = { CF_HANDLER_URL: cfHandlerUrl, CF_HANDLER_TOKEN: cfHandlerToken };
  const handlerBase = getCfHandlerBaseUrl(cfEnv);
  if (handlerBase) {
    try {
      const headers = buildCfHandlerHeaders(cfEnv);
      const params = new URLSearchParams({ q: cleanQ, base_url: root });
      const fallback = await fetch(`${handlerBase}/search/animasu?${params.toString()}`, {
        method: 'GET',
        headers,
        signal: AbortSignal.timeout(4_500),
      });
      if (fallback.ok) {
        const payload = (await fallback.json()) as { items?: AnimasuSearchItem[] };
        if (Array.isArray(payload.items)) {
          return payload.items;
        }
      }
    } catch {
      // Ignore fallback error
    }
  }

  return [];
}

export function mapAnimasuItemToCatalogRow(item: AnimasuSearchItem): Record<string, unknown> {
  return {
    id: animasuSlugToStableId(item.slug),
    mal_id: null,
    slug: item.slug,
    source: 'animasu',
    source_url: item.url,
    title: item.title,
    title_alt: item.title,
    score: 0,
    type: item.type || 'TV',
    status: item.status,
    schedule_day: 0,
    duration: '24 min',
    studio: 'Animasu',
    release_season: '-',
    poster_url: item.poster_url,
    popularity: 0,
    updated_at: Math.floor(Date.now() / 1000),
    genres: [],
    episodes_total: item.episodes_total > 0 ? item.episodes_total : 12,
    next_airing_at: 0,
    next_airing_ep: 0,
  };
}
