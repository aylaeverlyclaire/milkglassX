import curatedHosters from '../../data/curated_hosters_patch.json';

export interface CuratedHosterItem {
  q: string;
  c?: string;
  h: string;
  u: string;
  p?: string;
  r?: string;
}

export interface CuratedHosterAnimeEntry {
  id: number;
  title: string;
  title_key?: string;
  episodes: Record<string, CuratedHosterItem[]>;
}

export interface CuratedStoredHint {
  source: string;
  quality: string;
  hoster: string;
  raw_url: string;
  referer: string;
  container?: string;
}

function normalizeTitleKey(raw: string): string {
  return String(raw || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
}

const rawEntries = (curatedHosters.entries ?? []) as unknown as CuratedHosterAnimeEntry[];

export const CURATED_HOSTERS_VERSION: number = Number(curatedHosters?.version ?? 0) || 0;

const BY_ID = new Map<number, CuratedHosterAnimeEntry>();
const BY_TITLE_KEY = new Map<string, CuratedHosterAnimeEntry>();

for (const entry of rawEntries) {
  if (!entry || !entry.episodes || typeof entry.episodes !== 'object') continue;
  if (Number.isInteger(entry.id) && entry.id > 0) {
    BY_ID.set(entry.id, entry);
  }
  const tk = entry.title_key ? normalizeTitleKey(entry.title_key) : normalizeTitleKey(entry.title);
  if (tk.length >= 2 && !BY_TITLE_KEY.has(tk)) {
    BY_TITLE_KEY.set(tk, entry);
  }
}

export function lookupCuratedEpisodeHosters(
  title: string,
  ep: number,
  animeId?: number
): CuratedStoredHint[] | null {
  if (BY_ID.size === 0 && BY_TITLE_KEY.size === 0) return null;
  const entry =
    (animeId && BY_ID.get(animeId)) ||
    BY_TITLE_KEY.get(normalizeTitleKey(title));
  if (!entry) return null;

  const list = entry.episodes[String(ep)];
  if (!Array.isArray(list) || list.length === 0) return null;

  const out: CuratedStoredHint[] = [];
  for (const item of list) {
    if (!item || typeof item.u !== 'string' || !item.u.startsWith('https://')) continue;
    out.push({
      source: item.p || 'otakudesu',
      quality: item.q || '720p',
      hoster: item.h || 'Hoster',
      raw_url: item.u,
      referer: item.r || 'https://otakudesu.blog/',
      container: item.c || 'mp4',
    });
  }
  return out.length > 0 ? out : null;
}
