import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  ALLOWED_CATALOG_PROVIDERS,
  applyAniListEnrichment,
  chunk,
  fetchWithRetry,
  isStrictIdentityMatch,
  mergeProviderCatalogs,
  minUnique,
  parseOtakudesuCatalog,
  parseSamehadakuCatalog,
  retryAfterMs,
} from './lib/catalog-sync.mjs';
import { evaluateStatus } from './check-freshness.mjs';

const SCRIPT = fileURLToPath(new URL('./sync-catalog.mjs', import.meta.url));

test('ALLOWED_CATALOG_PROVIDERS hanya berisi samehadaku dan otakudesu', () => {
  assert.deepEqual(ALLOWED_CATALOG_PROVIDERS, ['samehadaku', 'otakudesu']);
  assert.ok(minUnique('full') > minUnique('ongoing'));
});

test('mergeProviderCatalogs: hanya menggabungkan Samehadaku & Otakudesu, menolak provider lain', () => {
  const same = parseSamehadakuCatalog(`
    <a href="https://v2.samehadaku.how/anime/sousou-no-frieren/">Sousou no Frieren Sub Indo</a>
    <a href="https://v2.samehadaku.how/anime/solo-leveling/">Solo Leveling</a>
  `);
  const otaku = parseOtakudesuCatalog(`
    <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren Subtitle Indonesia</a>
    <a class="hodebgst" href="https://otakudesu.blog/anime/dandadan-sub-indo/">Dandadan</a>
  `);
  const rejected = [
    { title: 'Animasu Only Anime', url: 'https://animasu.love/anime/animasu-only/', provider: 'animasu' },
    { title: 'Oploverz Only Anime', url: 'https://oploverz.ch/series/oplo-only/', provider: 'oploverz' },
    { title: 'AniList Only Anime', url: 'https://anilist.co/anime/999', provider: 'anilist' },
  ];

  const merged = mergeProviderCatalogs([same, otaku, rejected]);
  assert.equal(merged.length, 3);
  const frieren = merged.find((x) => x.title === 'Sousou no Frieren');
  assert.ok(frieren);
  assert.deepEqual(frieren.source_urls, {
    samehadaku: 'https://v2.samehadaku.how/anime/sousou-no-frieren/',
    otakudesu: 'https://otakudesu.blog/anime/frieren-sub-indo/',
  });
  assert.equal(merged.some((x) => x.title.includes('Animasu') || x.title.includes('Oploverz') || x.title.includes('AniList')), false);
});

test('isStrictIdentityMatch menegakkan Law of Identity untuk season, part, dan movie', () => {
  assert.equal(isStrictIdentityMatch('Sousou no Frieren', 'Sousou no Frieren'), true);
  assert.equal(isStrictIdentityMatch('Jujutsu Kaisen Season 2', 'Jujutsu Kaisen Season 1'), false);
  assert.equal(isStrictIdentityMatch('Bleach TYBW Part 1', 'Bleach TYBW Part 2'), false);
  assert.equal(isStrictIdentityMatch('Chainsaw Man', 'Chainsaw Man Movie: Reze Arc'), false);
});

test('applyAniListEnrichment hanya mengisi anime_id, mal_id, total eps, rating, dan poster tanpa mengubah source_urls', () => {
  const base = {
    title: 'Sousou no Frieren',
    source_urls: { samehadaku: 'https://v2.samehadaku.how/anime/sousou-no-frieren/' },
  };
  const enriched = applyAniListEnrichment(base, {
    id: 154587,
    idMal: 52991,
    episodes: 28,
    averageScore: 91,
    coverImage: { extraLarge: 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx154587.jpg' },
  });
  assert.equal(enriched.title, 'Sousou no Frieren');
  assert.deepEqual(enriched.source_urls, base.source_urls);
  assert.equal(enriched.anime_id, 154587);
  assert.equal(enriched.mal_id, 52991);
  assert.equal(enriched.episodes_total, 28);
  assert.equal(enriched.score, 9.1);
  assert.equal(enriched.poster_url, 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx154587.jpg');
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

function listen(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler);
    srv.listen(0, '127.0.0.1', () => resolve({ srv, url: `http://127.0.0.1:${srv.address().port}` }));
  });
}

const readBody = (req) => new Promise((res) => { let b = ''; req.on('data', (d) => (b += d)); req.on('end', () => res(b)); });

function runScript(env) {
  return new Promise((resolve) => {
    const p = spawn(process.execPath, [SCRIPT], { env: { ...process.env, PAGE_DELAY_MS: '0', RETRY_BASE_MS: '1', ...env } });
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.stderr.on('data', (d) => (out += d));
    p.on('close', (code) => resolve({ code, out }));
  });
}

test('integrasi: katalog hanya dari Samehadaku + Otakudesu, AniList hanya enrich metadata, dan prune di batch terakhir', async () => {
  const posts = [];
  let anilistQueries = 0;

  const providerSrv = await listen((req, res) => {
    if (req.url.startsWith('/daftar-anime-2/')) {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end(`
        <a href="https://v2.samehadaku.how/anime/frieren/">Sousou no Frieren</a>
        <a href="https://v2.samehadaku.how/anime/solo-leveling/">Solo Leveling</a>
      `);
      return;
    }
    if (req.url.startsWith('/anime-list/')) {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end(`
        <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren Sub Indo</a>
        <a class="hodebgst" href="https://otakudesu.blog/anime/dandadan-sub-indo/">Dandadan</a>
      `);
      return;
    }
    res.writeHead(404).end('not found');
  });

  const anilistSrv = await listen(async (req, res) => {
    const { variables } = JSON.parse(await readBody(req));
    anilistQueries++;
    const media = variables.search === 'Sousou no Frieren'
      ? [{ id: 154587, idMal: 52991, title: { romaji: 'Sousou no Frieren' }, episodes: 28, averageScore: 91, coverImage: { large: 'https://s4.anilist.co/frieren.jpg' } }]
      : [];
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ data: { Page: { media } } }));
  });

  const workerSrv = await listen(async (req, res) => {
    if (req.method === 'GET' && req.url === '/v1/internal/catalog-list') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ status: 'ok', data: [] }));
      return;
    }
    if (req.method === 'POST' && req.url === '/v1/internal/sync-source-catalog') {
      const body = JSON.parse(await readBody(req));
      posts.push({ secret: req.headers['x-admin-secret'], body });
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(
        JSON.stringify({ status: 'ok', updated: 0, inserted: body.items.length, pruned: body.prune })
      );
      return;
    }
    res.writeHead(404).end('not found');
  });

  try {
    const r = await runScript({
      WORKER_URL: workerSrv.url,
      ADMIN_SECRET: 's3cret-s3cret-s3cret',
      SAMEHADAKU_BASE_URL: providerSrv.url,
      OTAKUDESU_BASE_URL: providerSrv.url,
      ANILIST_URL: anilistSrv.url,
      SCOPE: 'full',
      MIN_UNIQUE: '3',
      MAX_ENRICH: '10',
    });
    assert.equal(r.code, 0, r.out);
    assert.equal(posts.length, 1);
    assert.equal(posts[0].body.prune, true);
    assert.equal(posts[0].body.items.length, 3);
    assert.equal(anilistQueries, 3);
    const frieren = posts[0].body.items.find((x) => x.title === 'Sousou no Frieren');
    assert.equal(frieren.anime_id, 154587);
    assert.equal(frieren.episodes_total, 28);
    assert.equal(frieren.score, 9.1);
    assert.equal(frieren.poster_url, 'https://s4.anilist.co/frieren.jpg');
    assert.ok(frieren.source_urls.samehadaku);
    assert.ok(frieren.source_urls.otakudesu);
    assert.match(r.out, /SUMMARY .*"enough":true/);
  } finally {
    providerSrv.srv.close();
    anilistSrv.srv.close();
    workerSrv.srv.close();
  }
});

test('integrasi: Samehadaku & Otakudesu gagal total -> exit 1, tidak ada sinkronisasi', async () => {
  const posts = [];
  const deadSrv = await listen((_req, res) => { res.writeHead(503).end('down'); });
  const workerSrv = await listen(async (req, res) => {
    if (req.method === 'POST') posts.push(req.url);
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ status: 'ok', data: [] }));
  });
  try {
    const r = await runScript({
      WORKER_URL: workerSrv.url,
      ADMIN_SECRET: 'x'.repeat(20),
      SAMEHADAKU_BASE_URL: deadSrv.url,
      OTAKUDESU_BASE_URL: deadSrv.url,
      SCOPE: 'ongoing',
    });
    assert.equal(r.code, 1, r.out);
    assert.equal(posts.length, 0);
  } finally {
    deadSrv.srv.close();
    workerSrv.srv.close();
  }
});

test('evaluateStatus: sehat, basi, belum pernah sinkron, terlalu sedikit', () => {
  const ok = { total: 480, synced_at: 1, age_sec: 3600, stale: false, stale_after_sec: 64800 };
  assert.deepEqual(evaluateStatus(ok, 300), []);
  assert.match(evaluateStatus({ ...ok, stale: true, age_sec: 20 * 3600 }, 300)[0], /basi.*20\.0 jam/);
  assert.match(evaluateStatus({ ...ok, stale: true, age_sec: null, synced_at: null }, 300)[0], /belum pernah/);
  assert.match(evaluateStatus({ ...ok, total: 120 }, 300)[0], /terlalu sedikit: 120 < 300/);
  assert.equal(evaluateStatus(null, 300).length, 1);
});
