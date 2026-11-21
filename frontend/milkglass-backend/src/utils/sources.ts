import { z } from 'zod';

export type SourceId =
  | 'samehadaku'
  | 'oploverz'
  | 'nontonanimeid'
  | 'otakudesu'
  | 'animasu'
  | 'kusonime';

export interface SourceBaseUrls {
  samehadaku: string;
  oploverz: string;
  nontonanimeid: string;
  otakudesu: string;
  animasu: string;
  kusonime: string;
}

/**
 * Prioritas kanonik embed-first lintas Backend, Indexer, dan Android Engine:
 * penyedia yang menyediakan embed streaming langsung (Samehadaku, Oploverz,
 * NontonAnimeID, Otakudesu) didahulukan sebelum Animasu (sering diblokir) dan
 * Kusonime (batch/hoster).
 */
export const SOURCE_CASCADE_ORDER: readonly SourceId[] = [
  'samehadaku',
  'oploverz',
  'nontonanimeid',
  'otakudesu',
  'animasu',
  'kusonime',
] as const;

export const DEFAULT_SOURCE_URLS: Readonly<SourceBaseUrls> = {
  samehadaku: 'https://v2.samehadaku.how',
  oploverz: 'https://oploverz.ch',
  nontonanimeid: 'https://nontonanimeid.stream',
  otakudesu: 'https://otakudesu.blog',
  animasu: 'https://animasu.love',
  kusonime: 'https://kusonime.com',
};

export const SOURCE_WATERMARK_TIER: Readonly<Record<SourceId, number>> = {
  samehadaku: 0,
  oploverz: 1,
  nontonanimeid: 0,
  otakudesu: 2,
  animasu: 0,
  kusonime: 2,
};

export const SOURCE_ID_SCHEMA = z.enum([
  'samehadaku',
  'oploverz',
  'nontonanimeid',
  'otakudesu',
  'animasu',
  'kusonime',
]);

export const QUALITY_SCHEMA = z.enum(['1080p', '720p', '480p', '360p']);
export const TARGET_RESOLUTIONS = ['1080p', '720p', '480p'] as const;

export function buildSourceCatalogList(urls: SourceBaseUrls) {
  return SOURCE_CASCADE_ORDER.map((id, idx) => ({
    rank: idx + 1,
    id,
    base_url: urls[id],
    watermark_tier: SOURCE_WATERMARK_TIER[id],
  }));
}
