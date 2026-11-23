import {
  ENRICH_SEARCH_QUERY,
  SYNC_CHUNK,
  applyAniListEnrichment,
  chunk,
  fetchWithRetry,
  isStrictIdentityMatch,
  mergeProviderCatalogs,
  minUnique,
  canonicalCatalogKey,
  normalizeCatalogKey,
  parseOtakudesuCatalog,
  parseOtakudesuSchedule,
  parseYlnimeSchedule,
  parseYlnimeCatalog,
  sleep,
} from './lib/catalog-sync.mjs';

const WORKER = process.env.WORKER_URL?.replace(/\/+$/, '');
const SECRET = process.env.ADMIN_SECRET;
const SCOPE = process.env.SCOPE === 'full' ? 'full' : 'ongoing';
const ANILIST = process.env.ANILIST_URL || 'https://graphql.anilist.co';
const CF_HANDLER = (process.env.CF_HANDLER_URL || '').trim().replace(/\/+$/, '');
const CF_TOKEN = (process.env.CF_HANDLER_TOKEN || '').trim();
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const PAGE_DELAY_MS = Number(process.env.PAGE_DELAY_MS ?? 500);
const RETRY_BASE_MS = Number(process.env.RETRY_BASE_MS ?? 2000);
const MAX_ENRICH = Number(process.env.MAX_ENRICH ?? 40);
const MIN_UNIQUE = Number(process.env.MIN_UNIQUE ?? minUnique(SCOPE));

if (!WORKER || !SECRET) {
  console.error('WORKER_URL dan ADMIN_SECRET wajib diisi');
  process.exit(1);
}

async function fetchHtml(url, referer) {
  const headers = {
    'User-Agent': UA,
    Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
    'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
    Referer: referer,
  };
  const direct = await fetchWithRetry(
    url,
    { method: 'GET', headers, redirect: 'follow', signal: AbortSignal.timeout(20000) },
    { attempts: 2, baseDelayMs: RETRY_BASE_MS }
  );
  if (direct.ok) return direct.response.text();

  if (CF_HANDLER) {
    const cfHeaders = { 'User-Agent': UA, Accept: 'text/html,*/*' };
    if (CF_TOKEN) cfHeaders['x-auth-token'] = CF_TOKEN;
    const proxied = await fetchWithRetry(
      `${CF_HANDLER}/fetch?url=${encodeURIComponent(url)}`,
      { method: 'GET', headers: cfHeaders, signal: AbortSignal.timeout(30000) },
      { attempts: 2, baseDelayMs: RETRY_BASE_MS }
    );
    if (proxied.ok) return proxied.response.text();
    throw new Error(`CF_HANDLER HTTP ${proxied.last?.status} ${proxied.last?.body ?? ''}`);
  }
  throw new Error(`HTTP ${direct.last?.status} ${direct.last?.body ?? ''}`);
}

async function resolveProviderBaseUrls() {
  let ylBase = (process.env.YLNIME_BASE_URL || '').trim().replace(/\/+$/, '');
  let otakuBase = (process.env.OTAKUDESU_BASE_URL || '').trim().replace(/\/+$/, '');
  if (!ylBase || !otakuBase) {
    try {
      const res = await fetch(`${WORKER}/v1/catalog/sources`, {
        headers: { 'User-Agent': UA, Accept: 'application/json' },
        signal: AbortSignal.timeout(10000),
      });
      if (res.ok) {
        const json = await res.json();
        const map = new Map((json?.sources || []).map((s) => [s.id, s.base_url]));
        if (!ylBase) ylBase = String(map.get('ylnime') || '').replace(/\/+$/, '');
        if (!otakuBase) otakuBase = String(map.get('otakudesu') || '').replace(/\/+$/, '');
      }
    } catch {}
  }
  return {
    ylnime: ylBase || 'https://ylnime.com',
    otakudesu: otakuBase || 'https://otakudesu.blog',
  };
}

async function fetchExistingEnrichedMap() {
  const existing = new Map();
  try {
    const res = await fetch(`${WORKER}/v1/internal/catalog-list`, {
      headers: { 'X-Admin-Secret': SECRET, 'User-Agent': UA, Accept: 'application/json' },
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) return existing;
    const json = await res.json();
    for (const row of json?.data || []) {
      const key = canonicalCatalogKey(row.title);
      if (key) existing.set(key, row);
    }
  } catch {}
  return existing;
}

async function enrichFromAniList(title) {
  const r = await fetchWithRetry(
    ANILIST,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': 'MilkglassCatalogSync/3.0' },
      body: JSON.stringify({ query: ENRICH_SEARCH_QUERY, variables: { search: title } }),
      signal: AbortSignal.timeout(8000),
    },
    { attempts: 2, baseDelayMs: RETRY_BASE_MS }
  );
  if (!r.ok) return null;
  const body = await r.response.json().catch(() => null);
  const candidates = body?.data?.Page?.media ?? [];
  return (
    candidates.find((m) =>
      [m?.title?.romaji, m?.title?.english]
        .filter((t) => typeof t === 'string' && t.length > 0)
        .some((t) => isStrictIdentityMatch(title, t))
    ) ?? null
  );
}


const YLNIME_LETTERS = ['', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')];

let ylDiagSummary = '';

function emitNotice(title, msg) {
  const safe = String(msg || '')
    .replace(/%/g, '%25')
    .replace(/\r/g, '%0D')
    .replace(/\n/g, '%0A')
    .slice(0, 3200);
  console.log(`::notice title=${title}::${safe}`);
}

async function fetchYlnimeCatalogAllLetters(ylBase) {
  const all = [];
  const seen = new Set();
  let firstErr = null;
  let firstEmptySample = null;
  for (let i = 0; i < YLNIME_LETTERS.length; i += 3) {
    const batch = YLNIME_LETTERS.slice(i, i + 3);
    const settled = await Promise.allSettled(
      batch.map(async (ch) => {
        const url = ch ? `${ylBase}/anime-list.php?l=${ch}` : `${ylBase}/anime-list.php`;
        const html = await fetchHtml(url, `${ylBase}/`);
        const items = parseYlnimeCatalog(html, ylBase);
        if (items.length === 0 && !firstEmptySample) {
          const hrefs = [...html.matchAll(/href=(["'])([^"']+)\1/gi)]
            .map((m) => m[2])
            .filter((h) => !h.startsWith('#') && !h.endsWith('.css') && !h.endsWith('.png') && !h.endsWith('.ico'))
            .slice(0, 12)
            .join(', ');
          firstEmptySample = `${url} len=${html.length} head=${html.slice(0, 160).replace(/\s+/g, ' ')} hrefs=[${hrefs}]`;
        }
        return items;
      })
    );
    for (const r of settled) {
      if (r.status !== 'fulfilled') {
        if (!firstErr) firstErr = r.reason?.message || String(r.reason);
        continue;
      }
      for (const it of r.value) {
        if (!seen.has(it.url)) {
          seen.add(it.url);
          all.push(it);
        }
      }
    }
  }
  if (all.length === 0) {
    ylDiagSummary = `firstErr=${firstErr || 'none'} sample=${firstEmptySample || 'none'}`;
    console.warn(`[ylnime-diag] 0 judul dari A-Z. ${ylDiagSummary}`);
  }
  return all;
}

const bases = await resolveProviderBaseUrls();
const providerResults = await Promise.allSettled([
  fetchHtml(`${bases.otakudesu}/anime-list/`, `${bases.otakudesu}/`).then(parseOtakudesuCatalog),
  fetchYlnimeCatalogAllLetters(bases.ylnime),
  fetchHtml(`${bases.otakudesu}/jadwal-rilis/`, `${bases.otakudesu}/`).then(parseOtakudesuSchedule),
  fetchHtml(`${bases.ylnime}/ongoing.php`, `${bases.ylnime}/`).then((h) => parseYlnimeCatalog(h, bases.ylnime)),
  fetchHtml(`${bases.ylnime}/jadwals.php`, `${bases.ylnime}/`).then(parseYlnimeSchedule),
]);

const otakuList = providerResults[0].status === 'fulfilled' ? providerResults[0].value : [];
const ylOngoingRaw = providerResults[3].status === 'fulfilled' ? providerResults[3].value : [];
const ylOngoingUrls = new Set(ylOngoingRaw.map((x) => x.url));
const ylBaseList = providerResults[1].status === 'fulfilled' ? providerResults[1].value : [];
const ylList = (ylBaseList.length > 0 ? ylBaseList : ylOngoingRaw.map((x) => ({ ...x, status: 1 }))).map((x) =>
  ylOngoingUrls.has(x.url) ? { ...x, status: 1 } : x
);
const scheduleMap = new Map(providerResults[2].status === 'fulfilled' ? providerResults[2].value : []);
const ylScheduleMap = providerResults[4].status === 'fulfilled' ? providerResults[4].value : new Map();
for (const [k, day] of ylScheduleMap.entries()) {
  if (!scheduleMap.has(k)) scheduleMap.set(k, day);
}

console.log(
  `[otakudesu] ${providerResults[0].status === 'fulfilled' ? `${otakuList.length} judul` : `GAGAL: ${providerResults[0].reason?.message}`}`
);
console.log(
  `[ylnime] ${providerResults[1].status === 'fulfilled' ? `${ylList.length} judul` : `GAGAL: ${providerResults[1].reason?.message}`}`
);

const merged = mergeProviderCatalogs([otakuList, ylList], scheduleMap);
const otakuMsg = providerResults[0].status === 'fulfilled' ? `${otakuList.length}` : `ERR:${providerResults[0].reason?.message}`;
const ylOnMsg = providerResults[3].status === 'fulfilled' ? `${ylOngoingRaw.length}` : `ERR:${providerResults[3].reason?.message}`;
if (merged.length === 0) {
  emitNotice('sync-diag', `0 items! bases=${JSON.stringify(bases)} otaku=${otakuMsg} ylAZ=${ylBaseList.length} ylOngoing=${ylOnMsg} ${ylDiagSummary}`);
  console.error('0 item dari YLnime & Otakudesu — batalkan sinkronisasi');
  process.exit(1);
}

const existingMap = await fetchExistingEnrichedMap();
let enrichedCount = 0;
let enrichAttempts = 0;

for (let i = 0; i < merged.length; i++) {
  const item = merged[i];
  const prev = existingMap.get(canonicalCatalogKey(item.title));
  if (prev && Number.isInteger(prev.id) && prev.id > 0) {
    // Kunci ID wadah yang sudah ada di D1 agar favorit & riwayat tonton user tidak pernah terputus
    item.anime_id = prev.id;
  }
  if (prev && prev.poster_url && Number(prev.score) > 0 && Number(prev.episodes_total) > 0 && prev.genres_json && prev.genres_json !== '[]') {
    continue;
  }
  if (enrichAttempts >= MAX_ENRICH) continue;
  enrichAttempts++;
  const media = await enrichFromAniList(item.title).catch(() => null);
  if (media) {
    merged[i] = applyAniListEnrichment(item, media);
    enrichedCount++;
  }
  if (PAGE_DELAY_MS > 0) await sleep(PAGE_DELAY_MS);
}

const enough = merged.length >= MIN_UNIQUE;
const batches = chunk(merged, SYNC_CHUNK);
let totalUpdated = 0;
let totalInserted = 0;
let importFailed = false;

for (let i = 0; i < batches.length; i++) {
  const part = batches[i];
  const isLast = i === batches.length - 1;
  const shouldPrune = isLast && enough;
  const r = await fetchWithRetry(
    `${WORKER}/v1/internal/sync-source-catalog`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Secret': SECRET,
        'User-Agent': 'MilkglassCatalogSync/3.0',
      },
      body: JSON.stringify({ items: part, scope: SCOPE, prune: shouldPrune }),
      signal: AbortSignal.timeout(30000),
    },
    { attempts: 3, baseDelayMs: RETRY_BASE_MS }
  );
  if (!r.ok) {
    const errMsg = `sync-source-catalog batch ${i + 1} gagal: HTTP ${r.last.status} ${r.last.body}`;
    emitNotice('sync-diag', errMsg);
    console.error(errMsg);
    importFailed = true;
    break;
  }
  const j = await r.response.json().catch(() => ({}));
  totalUpdated += Number(j.updated || 0);
  totalInserted += Number(j.inserted || 0);
  console.log(`batch ${i + 1}/${batches.length}: updated=${j.updated ?? 0} inserted=${j.inserted ?? 0} pruned=${j.pruned ?? false}`);
}

const summaryStr = JSON.stringify({
  scope: SCOPE,
  ylnime: ylList.length,
  otakudesu: otakuList.length,
  unique: merged.length,
  enriched: enrichedCount,
  updated: totalUpdated,
  inserted: totalInserted,
  min: MIN_UNIQUE,
  enough,
});
console.log(`SUMMARY ${summaryStr}`);
emitNotice('sync-diag', `SUMMARY ${summaryStr}`);

if (importFailed) process.exit(1);
if (!enough) {
  console.error(`Katalog tidak lengkap (${merged.length} < ${MIN_UNIQUE})`);
  process.exit(1);
}
