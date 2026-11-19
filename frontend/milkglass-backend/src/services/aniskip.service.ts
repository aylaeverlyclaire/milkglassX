export interface SkipTimes {
  opStart: number | null;
  opEnd: number | null;
  edStart: number | null;
  edEnd: number | null;
}

interface AniSkipResultItem {
  skipType: 'op' | 'ed' | 'mixed-op' | 'mixed-ed' | 'recap';
  interval: {
    startTime: number;
    endTime: number;
  };
}

export async function fetchAniSkipTimes(
  malId: number | null | undefined,
  episodeNumber: number,
  episodeLengthSec = 0
): Promise<SkipTimes> {
  const empty: SkipTimes = { opStart: null, opEnd: null, edStart: null, edEnd: null };
  if (!malId || malId <= 0 || episodeNumber <= 0) {
    return empty;
  }

  try {
    const url = new URL(`https://api.aniskip.com/v2/skip-times/${malId}/${episodeNumber}`);
    url.searchParams.append('types', 'op');
    url.searchParams.append('types', 'ed');
    url.searchParams.append('episodeLength', episodeLengthSec.toString());

    const response = await fetch(url.toString(), {
      headers: {
        Accept: 'application/json',
        'User-Agent': 'MilkglassSkipSync/2.0',
      },
    });

    if (!response.ok) {
      return empty;
    }

    const data = (await response.json()) as {
      found?: boolean;
      results?: AniSkipResultItem[];
    };

    if (!data.found || !Array.isArray(data.results)) {
      return empty;
    }

    const out: SkipTimes = { ...empty };
    for (const item of data.results) {
      if (item.skipType === 'op' && out.opStart === null) {
        out.opStart = Math.floor(item.interval.startTime);
        out.opEnd = Math.floor(item.interval.endTime);
      } else if (item.skipType === 'ed' && out.edStart === null) {
        out.edStart = Math.floor(item.interval.startTime);
        out.edEnd = Math.floor(item.interval.endTime);
      }
    }
    return out;
  } catch {
    return empty;
  }
}
