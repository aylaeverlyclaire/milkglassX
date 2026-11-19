import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { buildPlans, chunk, dedupeById, fetchWithRetry, minUnique, retryAfterMs } from './lib/catalog-sync.mjs';

const SCRIPT = fileURLToPath(new URL('./sync-catalog.mjs', import.meta.url));

test('buildPlans: ongoing 3 halaman RELEASING; full tambah 8 halaman populer', () => {
  assert.equal(buildPlans('ongoing').length, 3);
  assert.ok(buildPlans('ongoing').every((p) => p.status === 'RELEASING'));
  const full = buildPlans('full');
  assert.equal(full.length, 11);
  assert.equal(full.filter((p) => p.status === undefined).length, 8);
  assert.equal(full[0].status, 'RELEASING');
  assert.ok(minUnique('full') > minUnique('ongoing'));
});

test('dedupeById: kemunculan pertama menang, id tak valid dibuang', () => {
  const out = dedupeById([{ id: 1, v: 'a' }, { id: 2 }, { id: 1, v: 'b' }, null, { id: 'x' }]);
  assert.deepEqual(out.map((m) => m.id), [1, 2]);
  assert.equal(out[0].v, 'a');
});

test('chunk membagi sesuai ukuran', () => {
  assert.deepEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  assert.deepEqual(chunk([], 100), []);
});

test('retryAfterMs: dibatasi 5..65 dtk, non-angka = null', () => {
  assert.equal(retryAfterMs('1'), 5000);
  assert.equal(retryAfterMs('30'), 30000);
  assert.equal(retryAfterMs('999'), 65000);
  assert.equal(retryAfterMs(null), null);
  assert.equal(retryAfterMs('abc'), null);
});

const fakeRes = (status, headers = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: (k) => headers[k.toLowerCase()] ?? null },
  text: async () => `body${status}`,
});

test('fetchWithRetry: 429 lalu 200 -> sukses, menunggu sesuai Retry-After', async () => {
  const seq = [fakeRes(429, { 'retry-after': '7' }), fakeRes(200)];
  const waits = [];
  const r = await fetchWithRetry('u', {}, { fetchImpl: async () => seq.shift(), sleepImpl: async (ms) => waits.push(ms) });
  assert.equal(r.ok, true);
  assert.equal(r.attempts, 2);
  assert.deepEqual(waits, [7000]);
});

test('fetchWithRetry: 4xx selain 429 tidak diulang', async () => {
  let calls = 0;
  const r = await fetchWithRetry('u', {}, { fetchImpl: async () => (calls++, fakeRes(400)), sleepImpl: async () => {} });
  assert.equal(r.ok, false);
  assert.equal(calls, 1);
  assert.equal(r.last.status, 400);
});

test('fetchWithRetry: 5xx diulang sampai batas percobaan', async () => {
  let calls = 0;
  const r = await fetchWithRetry('u', {}, { attempts: 3, fetchImpl: async () => (calls++, fakeRes(503)), sleepImpl: async () => {} });
  assert.equal(r.ok, false);
  assert.equal(calls, 3);
});

function listen(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler);
    srv.listen(0, '127.0.0.1', () => resolve({ srv, url: `http://127.0.0.1:${srv.address().port}` }));
  });
}

const readBody = (req) => new Promise((res) => { let b = ''; req.on('data', (d) => (b += d)); req.on('end', () => res(b)); });

function anilistHandler({ failAll = false, failFrom = Infinity } = {}) {
  const calls = [];
  const handler = async (req, res) => {
    const { variables } = JSON.parse(await readBody(req));
    calls.push(variables);
    if (failAll || calls.length >= failFrom) { res.writeHead(500).end('boom'); return; }
    const base = variables.status === 'RELEASING' ? 1000 + (variables.page - 1) * 50 : (variables.page - 1) * 50 + 1000 - 25 + 1;
    const media = Array.from({ length: 50 }, (_, i) => ({ id: base + i, title: { romaji: `T${base + i}` } }));
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data: { Page: { media } } }));
  };
  return { handler, calls };
}

function workerHandler() {
  const posts = [];
  const handler = async (req, res) => {
    const body = JSON.parse(await readBody(req));
    posts.push({ secret: req.headers['x-admin-secret'], path: req.url, scope: body.scope, ids: body.media.map((m) => m.id) });
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ status: 'ok', imported: body.media.length }));
  };
  return { handler, posts };
}

function runScript(env) {
  return new Promise((resolve) => {
    const p = spawn(process.execPath, [SCRIPT], { env: { ...process.env, PAGE_DELAY_MS: '0', RETRY_BASE_MS: '1', ...env } });
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.stderr.on('data', (d) => (out += d));
    p.on('close', (code) => resolve({ code, out }));
  });
}

test('integrasi full: dedup lintas halaman, potong ≤100 per panggilan, kirim scope + secret', async () => {
  const al = anilistHandler();
  const wk = workerHandler();
  const a = await listen(al.handler);
  const w = await listen(wk.handler);
  try {
    const r = await runScript({ WORKER_URL: w.url, ADMIN_SECRET: 's3cret-s3cret-s3cret', ANILIST_URL: a.url, SCOPE: 'full', MIN_UNIQUE: '300' });
    assert.equal(r.code, 0, r.out);
    assert.equal(al.calls.length, 11);
    const sent = wk.posts.flatMap((p) => p.ids);
    assert.equal(new Set(sent).size, sent.length, 'tidak boleh ada id ganda terkirim');
    assert.ok(sent.length >= 300 && sent.length <= 550);
    assert.ok(wk.posts.every((p) => p.ids.length <= 100 && p.scope === 'full' && p.secret === 's3cret-s3cret-s3cret'));
    assert.ok(wk.posts.every((p) => p.path === '/v1/internal/catalog-import'));
    assert.match(r.out, /SUMMARY .*"enough":true/);
  } finally { a.srv.close(); w.srv.close(); }
});

test('integrasi ongoing: hanya 3 halaman RELEASING', async () => {
  const al = anilistHandler();
  const wk = workerHandler();
  const a = await listen(al.handler);
  const w = await listen(wk.handler);
  try {
    const r = await runScript({ WORKER_URL: w.url, ADMIN_SECRET: 'x'.repeat(20), ANILIST_URL: a.url, SCOPE: 'ongoing' });
    assert.equal(r.code, 0, r.out);
    assert.equal(al.calls.length, 3);
    assert.ok(al.calls.every((c) => c.status === 'RELEASING'));
    assert.equal(wk.posts.flatMap((p) => p.ids).length, 150);
    assert.ok(wk.posts.every((p) => p.scope === 'ongoing'));
  } finally { a.srv.close(); w.srv.close(); }
});

test('integrasi: AniList gagal total -> exit 1, tidak ada impor', async () => {
  const al = anilistHandler({ failAll: true });
  const wk = workerHandler();
  const a = await listen(al.handler);
  const w = await listen(wk.handler);
  try {
    const r = await runScript({ WORKER_URL: w.url, ADMIN_SECRET: 'x'.repeat(20), ANILIST_URL: a.url, SCOPE: 'ongoing' });
    assert.equal(r.code, 1, r.out);
    assert.equal(wk.posts.length, 0);
  } finally { a.srv.close(); w.srv.close(); }
});

test('integrasi: sebagian halaman gagal -> impor yang ada tetap jalan, tapi exit 1 bila di bawah minimum', async () => {
  const al = anilistHandler({ failFrom: 3 });
  const wk = workerHandler();
  const a = await listen(al.handler);
  const w = await listen(wk.handler);
  try {
    const r = await runScript({ WORKER_URL: w.url, ADMIN_SECRET: 'x'.repeat(20), ANILIST_URL: a.url, SCOPE: 'ongoing', MIN_UNIQUE: '120' });
    assert.equal(r.code, 1, r.out);
    assert.equal(wk.posts.flatMap((p) => p.ids).length, 100);
    assert.match(r.out, /tidak lengkap/);
  } finally { a.srv.close(); w.srv.close(); }
});

import { evaluateStatus } from './check-freshness.mjs';

test('evaluateStatus: sehat, basi, belum pernah sinkron, terlalu sedikit', () => {
  const ok = { total: 480, synced_at: 1, age_sec: 3600, stale: false, stale_after_sec: 64800 };
  assert.deepEqual(evaluateStatus(ok, 300), []);
  assert.match(evaluateStatus({ ...ok, stale: true, age_sec: 20 * 3600 }, 300)[0], /basi.*20\.0 jam/);
  assert.match(evaluateStatus({ ...ok, stale: true, age_sec: null, synced_at: null }, 300)[0], /belum pernah/);
  assert.match(evaluateStatus({ ...ok, total: 120 }, 300)[0], /terlalu sedikit: 120 < 300/);
  assert.equal(evaluateStatus(null, 300).length, 1);
});
