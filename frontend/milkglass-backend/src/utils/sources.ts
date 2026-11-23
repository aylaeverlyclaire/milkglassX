import { z } from 'zod';

export type SourceId = 'ylnime' | 'otakudesu';

export interface SourceBaseUrls {
  ylnime: string;
  otakudesu: string;
}

/**
 * Prioritas kanonik lintas Backend, Indexer, dan Android Engine:
 * Rank 1 = Otakudesu, Rank 2 = YLnime (menggantikan samehadaku.li).
 */
export const SOURCE_CASCADE_ORDER: readonly SourceId[] = [
  'otakudesu',
  'ylnime',
] as const;

export const DEFAULT_SOURCE_URLS: Readonly<SourceBaseUrls> = {
  ylnime: 'https://ylnime.com',
  otakudesu: 'https://otakudesu.blog',
};

export const SOURCE_WATERMARK_TIER: Readonly<Record<SourceId, number>> = {
  ylnime: 0,
  otakudesu: 2,
};

export const SOURCE_ID_SCHEMA = z.enum(['ylnime', 'otakudesu']);

export const QUALITY_SCHEMA = z.enum(['1080p', '720p', '480p', '360p']);
export const TARGET_RESOLUTIONS = ['1080p', '720p', '480p'] as const;

export function parseSourceCascadeOrder(raw: string | null | undefined): SourceId[] {
  if (!raw || !raw.trim()) return [...SOURCE_CASCADE_ORDER];
  let tokens: string[] = [];
  const trimmed = raw.trim();
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed)) tokens = parsed.map((v) => String(v).trim().toLowerCase());
    } catch {}
  } else {
    tokens = trimmed.split(',').map((v) => v.trim().toLowerCase());
  }
  const validSet = new Set<string>(SOURCE_CASCADE_ORDER);
  const ordered: SourceId[] = [];
  for (const t of tokens) {
    if (validSet.has(t) && !ordered.includes(t as SourceId)) {
      ordered.push(t as SourceId);
    }
  }
  for (const fallback of SOURCE_CASCADE_ORDER) {
    if (!ordered.includes(fallback)) ordered.push(fallback);
  }
  return ordered;
}

export function buildSourceCatalogList(
  urls: SourceBaseUrls,
  order: readonly SourceId[] = SOURCE_CASCADE_ORDER
) {
  return order.map((id, idx) => ({
    rank: idx + 1,
    id,
    base_url: urls[id],
    watermark_tier: SOURCE_WATERMARK_TIER[id],
  }));
}
