import { fetchWithRetry } from './lib/catalog-sync.mjs';

export function evaluateStatus(data, minTotal) {
  const problems = [];
  if (!data || typeof data !== 'object') return ['respons status bukan objek'];
  if (data.stale) {
    const h = data.age_sec == null ? null : (data.age_sec / 3600).toFixed(1);
    problems.push(h === null ? 'katalog belum pernah disinkron' : `katalog basi: impor terakhir ${h} jam lalu (batas ${(data.stale_after_sec / 3600).toFixed(0)} jam)`);
  }
  if (!(data.total >= minTotal)) problems.push(`judul terlalu sedikit: ${data.total} < ${minTotal}`);
  return problems;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const base = process.env.WORKER_URL;
  if (!base) { console.error('WORKER_URL wajib'); process.exit(1); }
  const minTotal = Number(process.env.MIN_TOTAL ?? 300);
  const res = await fetchWithRetry(`${base}/v1/catalog/status`, { headers: { Accept: 'application/json' } }, { attempts: 3 });
  if (!res.ok) { console.error(`::error::Worker tidak menjawab /v1/catalog/status (HTTP ${res.last.status}) ${res.last.body}`); process.exit(1); }
  const json = await res.response.json().catch(() => null);
  console.log(JSON.stringify(json?.data));
  const problems = evaluateStatus(json?.data, minTotal);
  for (const p of problems) console.error(`::error::${p}`);
  process.exit(problems.length ? 1 : 0);
}
