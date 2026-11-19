import { buildPlans, chunk, dedupeById, fetchWithRetry, IMPORT_CHUNK, minUnique, PER_PAGE, QUERY, sleep } from './lib/catalog-sync.mjs';

const WORKER_URL = process.env.WORKER_URL;
const ADMIN_SECRET = process.env.ADMIN_SECRET;
if (!WORKER_URL || !ADMIN_SECRET) { console.error('WORKER_URL/ADMIN_SECRET wajib'); process.exit(1); }

const SCOPE = process.env.SCOPE === 'ongoing' ? 'ongoing' : 'full';
const ANILIST_URL = process.env.ANILIST_URL || 'https://graphql.anilist.co';

const PAGE_DELAY_MS = Number(process.env.PAGE_DELAY_MS ?? 2200);
const BASE_DELAY_MS = Number(process.env.RETRY_BASE_MS ?? 2000);
const MIN_UNIQUE = Number(process.env.MIN_UNIQUE ?? minUnique(SCOPE));

const log = (m) => console.log(m);
const plans = buildPlans(SCOPE);
log(`Cakupan: ${SCOPE}; ${plans.length} halaman AniList; minimum ${MIN_UNIQUE} judul unik.`);

const fetched = [];
let pageFailures = 0;
for (const [i, plan] of plans.entries()) {
  const tag = `${plan.status ?? 'ALL'}#${plan.page}`;
  const res = await fetchWithRetry(
    ANILIST_URL,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query: QUERY, variables: { ...plan, perPage: PER_PAGE } }),
    },
    { baseDelayMs: BASE_DELAY_MS },
  );
  if (!res.ok) {
    pageFailures++;
    log(`AniList ${tag} -> GAGAL HTTP ${res.last.status} ${res.last.body}`);
  } else {
    const json = await res.response.json().catch(() => null);
    const media = json?.data?.Page?.media ?? [];
    if (json?.errors?.length) log(`AniList ${tag} -> graphql: ${String(json.errors[0]?.message ?? '').slice(0, 120)}`);
    log(`AniList ${tag} -> ${media.length} item${res.attempts > 1 ? ` (percobaan ke-${res.attempts})` : ''}`);
    fetched.push(...media);
    if (media.length === 0) pageFailures++;
  }
  if (i < plans.length - 1 && PAGE_DELAY_MS > 0) await sleep(PAGE_DELAY_MS);
}

const unique = dedupeById(fetched);
log(`Terambil ${fetched.length} item, ${unique.length} unik.`);

let imported = 0;
let importFailures = 0;
for (const [i, part] of chunk(unique, IMPORT_CHUNK).entries()) {
  const res = await fetchWithRetry(
    `${WORKER_URL}/v1/internal/catalog-import`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Secret': ADMIN_SECRET },
      body: JSON.stringify({ media: part, scope: SCOPE }),
    },
    { attempts: 3, baseDelayMs: BASE_DELAY_MS },
  );
  if (res.ok) {
    imported += part.length;
    log(`Worker chunk ${i + 1}: ${part.length} item -> HTTP ${res.response.status}`);
  } else {
    importFailures++;
    log(`Worker chunk ${i + 1}: GAGAL HTTP ${res.last.status} ${res.last.body}`);
  }
}

const enough = unique.length >= MIN_UNIQUE;
log(`SUMMARY ${JSON.stringify({ scope: SCOPE, fetched: fetched.length, unique: unique.length, imported, pageFailures, importFailures, minUnique: MIN_UNIQUE, enough })}`);
if (!enough) console.error(`Judul unik ${unique.length} < minimum ${MIN_UNIQUE}: sinkron dianggap tidak lengkap.`);
process.exit(imported > 0 && importFailures === 0 && enough ? 0 : 1);
