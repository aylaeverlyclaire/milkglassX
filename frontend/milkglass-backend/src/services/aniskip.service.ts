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

function toValidInterval(interval: AniSkipResultItem['interval'] | undefined): { start: number; end: number } | null {
  if (!interval) return null;
  const start = Math.floor(interval.startTime);
  const end = Math.floor(interval.endTime);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start) {
    return null;
  }
  return { start, end };
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
    url.searchParams.append('types', 'mixed-op');
    url.searchParams.append('types', 'mixed-ed');
    url.searchParams.append('episodeLength', episodeLengthSec.toString());

    const response = await fetch(url.toString(), {
      headers: {
        Accept: 'application/json',
        'User-Agent': 'MilkglassSkipSync/2.0',
      },
      signal: AbortSignal.timeout(8000),
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
    let mixedOp: { start: number; end: number } | null = null;
    let mixedEd: { start: number; end: number } | null = null;

    for (const item of data.results) {
      const iv = toValidInterval(item.interval);
      if (!iv) continue;
      if (item.skipType === 'op' && out.opStart === null) {
        out.opStart = iv.start;
        out.opEnd = iv.end;
      } else if (item.skipType === 'ed' && out.edStart === null) {
        out.edStart = iv.start;
        out.edEnd = iv.end;
      } else if (item.skipType === 'mixed-op' && mixedOp === null) {
        mixedOp = iv;
      } else if (item.skipType === 'mixed-ed' && mixedEd === null) {
        mixedEd = iv;
      }
    }

    if (out.opStart === null && mixedOp !== null) {
      out.opStart = mixedOp.start;
      out.opEnd = mixedOp.end;
    }
    if (out.edStart === null && mixedEd !== null) {
      out.edStart = mixedEd.start;
      out.edEnd = mixedEd.end;
    }
    return out;
  } catch (err: unknown) {
    console.error('[AniSkip] fetch failed', {
      malId,
      episodeNumber,
      error: err instanceof Error ? err.message : String(err),
    });
    return empty;
  }
}
