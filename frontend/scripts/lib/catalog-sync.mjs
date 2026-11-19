export const FIELDS = `id idMal title{romaji english native} description(asHtml:false) averageScore format status duration
  episodes season seasonYear popularity genres coverImage{extraLarge large} studios(isMain:true){nodes{name}}
  nextAiringEpisode{airingAt episode}`;

export const QUERY = `query($page:Int,$perPage:Int,$status:MediaStatus){Page(page:$page,perPage:$perPage){
  media(type:ANIME,status:$status,sort:[POPULARITY_DESC],isAdult:false){${FIELDS}}}}`;

export const PER_PAGE = 50;
export const IMPORT_CHUNK = 100;

export function buildPlans(scope) {
  const plans = [1, 2, 3].map((page) => ({ page, status: 'RELEASING' }));
  if (scope === 'full') for (let page = 1; page <= 8; page++) plans.push({ page, status: undefined });
  return plans;
}

export function minUnique(scope) {
  return scope === 'full' ? 300 : 80;
}

export function dedupeById(items) {
  const seen = new Set();
  const out = [];
  for (const m of items) {
    if (!m || !Number.isInteger(m.id) || seen.has(m.id)) continue;
    seen.add(m.id);
    out.push(m);
  }
  return out;
}

export function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

export const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

export function retryAfterMs(header) {
  const n = Number(header);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.min(65, Math.max(5, n)) * 1000;
}

export async function fetchWithRetry(url, init, { attempts = 4, baseDelayMs = 2000, fetchImpl = fetch, sleepImpl = sleep } = {}) {
  let last = null;
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await fetchImpl(url, init);
      if (r.ok) return { ok: true, response: r, attempts: i };
      last = { status: r.status, body: (await r.text()).slice(0, 160).replace(/\s+/g, ' ') };
      if (r.status === 429) {
        if (i < attempts) await sleepImpl(retryAfterMs(r.headers.get('retry-after')) ?? baseDelayMs * i);
        continue;
      }
      if (r.status < 500) break;
      if (i < attempts) await sleepImpl(baseDelayMs * i);
    } catch (e) {
      last = { status: 0, body: String(e?.message ?? e).slice(0, 160) };
      if (i < attempts) await sleepImpl(baseDelayMs * i);
    }
  }
  return { ok: false, last };
}
