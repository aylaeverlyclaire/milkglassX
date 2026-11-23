import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  ALLOWED_CATALOG_PROVIDERS,
  applyAniListEnrichment,
  canonicalCatalogKey,
  chunk,
  fetchWithRetry,
  isStrictIdentityMatch,
  mergeProviderCatalogs,
  minUnique,
  parseOtakudesuCatalog,
  parseOtakudesuSchedule,
  parseYlnimeSchedule,
  parseYlnimeCatalog,
  retryAfterMs,
} from './lib/catalog-sync.mjs';
import { evaluateStatus } from './check-freshness.mjs';

const SCRIPT = fileURLToPath(new URL('./sync-catalog.mjs', import.meta.url));

test('ALLOWED_CATALOG_PROVIDERS hanya berisi ylnime dan otakudesu', () => {
  assert.deepEqual(ALLOWED_CATALOG_PROVIDERS, ['ylnime', 'otakudesu']);
  assert.ok(minUnique('full') > minUnique('ongoing'));
});

test('mergeProviderCatalogs: hanya menggabungkan YLnime & Otakudesu, menolak provider lain', () => {
  const same = parseYlnimeCatalog(`
    <a href="https://ylnime.com/?series=sousou-no-frieren">Sousou no Frieren Sub Indo</a>
    <a href="https://ylnime.com/?series=solo-leveling">Solo Leveling</a>
  `);
  const otaku = parseOtakudesuCatalog(`
    <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren Subtitle Indonesia</a>
    <a class="hodebgst" href="https://otakudesu.blog/anime/dandadan-sub-indo/">Dandadan</a>
  `);
  const rejected = [
    { title: 'Unknown Provider Anime', url: 'https://example.com/anime/unknown/', provider: 'other' },
    { title: 'AniList Only Anime', url: 'https://anilist.co/anime/999', provider: 'anilist' },
  ];

  const merged = mergeProviderCatalogs([same, otaku, rejected]);
  assert.equal(merged.length, 3);
  const frieren = merged.find((x) => x.title === 'Sousou no Frieren');
  assert.ok(frieren);
  assert.deepEqual(frieren.source_urls, {
    ylnime: 'https://ylnime.com/?series=sousou-no-frieren',
    otakudesu: 'https://otakudesu.blog/anime/frieren-sub-indo/',
  });
  assert.equal(merged.some((x) => x.title.includes('Unknown') || x.title.includes('AniList')), false);
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
    source_urls: { ylnime: 'https://ylnime.com/?series=sousou-no-frieren' },
  };
  const enriched = applyAniListEnrichment(base, {
    id: 154587,
    idMal: 52991,
    title: { romaji: 'Sousou no Frieren', english: "Frieren: Beyond Journey's End" },
    episodes: 28,
    averageScore: 91,
    genres: ['Adventure', 'Drama', 'Fantasy'],
    coverImage: { extraLarge: 'https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx154587.jpg' },
  });
  assert.equal(enriched.title, 'Sousou no Frieren');
  assert.deepEqual(enriched.source_urls, base.source_urls);
  assert.equal(enriched.anime_id, 154587);
  assert.equal(enriched.mal_id, 52991);
  assert.equal(enriched.episodes_total, 28);
  assert.equal(enriched.score, 9.1);
  assert.equal(enriched.title_alt, "Frieren: Beyond Journey's End");
  assert.deepEqual(enriched.genres, ['Adventure', 'Drama', 'Fantasy']);
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

test('integrasi: katalog hanya dari YLnime + Otakudesu, AniList hanya enrich metadata, dan prune di batch terakhir', async () => {
  const posts = [];
  let anilistQueries = 0;

  const providerSrv = await listen((req, res) => {
    if (req.url.startsWith('/anime-list.php')) {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end(`
        <a href="https://ylnime.com/?series=frieren">Sousou no Frieren</a>
        <a href="https://ylnime.com/?series=solo-leveling">Solo Leveling</a>
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
      YLNIME_BASE_URL: providerSrv.url,
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
    assert.ok(frieren.source_urls.ylnime);
    assert.ok(frieren.source_urls.otakudesu);
    assert.match(r.out, /SUMMARY .*"enough":true/);
  } finally {
    providerSrv.srv.close();
    anilistSrv.srv.close();
    workerSrv.srv.close();
  }
});

test('integrasi: YLnime & Otakudesu gagal total -> exit 1, tidak ada sinkronisasi', async () => {
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
      YLNIME_BASE_URL: deadSrv.url,
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

test('parseOtakudesuCatalog & parseOtakudesuSchedule: buang suffix On-Going, set status 1/2, dan petakan jadwal Senin-Minggu', () => {
  const list = parseOtakudesuCatalog(`
    <a class="hodebgst" href="https://otakudesu.blog/anime/black-clover-s2-sub-indo/">Black Clover Season 2 On-Going</a>
    <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren</a>
  `);
  assert.equal(list[0].title, 'Black Clover Season 2');
  assert.equal(list[0].status, 1);
  assert.equal(list[1].title, 'Sousou no Frieren');
  assert.equal(list[1].status, 2);

  const sched = parseOtakudesuSchedule(`
    <h2>Minggu</h2><ul><li><a href="https://otakudesu.blog/anime/black-clover-s2-sub-indo/">Black Clover Season 2</a></li></ul>
    <h2>Jumat</h2><ul><li><a href="https://otakudesu.blog/anime/one-piece-sub-indo/">One Piece</a></li></ul>
  `);
  const merged = mergeProviderCatalogs([list], sched);
  assert.equal(merged[0].schedule_day, 7);
  assert.equal(merged[0].status, 1);
  assert.equal(merged[1].schedule_day, undefined);
  assert.equal(merged[1].status, 2);
});

test('Law of Identity: Date A Live Romawi/+OVA, Tensura side-story, dan Mushoku Tensei multi-cour', () => {
  assert.equal(isStrictIdentityMatch('Date A Live Season 2', 'Date A Live II'), true);
  assert.equal(isStrictIdentityMatch('Date A Live BD (Episode 1 – 12) Subtitle Indonesia + OVA', 'Date A Live'), true);
  assert.equal(isStrictIdentityMatch('Date A Live II BD (Episode 1 – 10) Subtitle Indonesia + OVA', 'Date A Live II'), true);
  assert.equal(isStrictIdentityMatch('Date A Live', 'Date A Bullet: Dead or Bullet'), false);
  assert.equal(isStrictIdentityMatch('Tensei shitara Slime Datta Ken', 'Tensura Nikki: Tensei shitara Slime Datta Ken'), false);
  assert.equal(isStrictIdentityMatch('Tensei shitara Slime Datta Ken', 'Tensei shitara Slime Datta Ken: Coleus no Yume'), false);
  assert.equal(isStrictIdentityMatch('Mushoku Tensei II: Isekai Ittara Honki Dasu', 'Mushoku Tensei II: Isekai Ittara Honki Dasu Part 2'), false);
  assert.equal(isStrictIdentityMatch('Mushoku Tensei Season 2 Part 2', 'Mushoku Tensei II: Isekai Ittara Honki Dasu Part 2'), true);
});

test('canonicalCatalogKey menyatukan Date A Live II & Season 2 tanpa duplikat, tapi memisahkan S1 & side story', () => {
  assert.equal(canonicalCatalogKey('Date A Live II BD (Episode 1 – 10) Subtitle Indonesia + OVA'), canonicalCatalogKey('Date A Live Season 2'));
  assert.equal(canonicalCatalogKey('Date A Live 2nd Season'), canonicalCatalogKey('Date A Live Season 2'));
  assert.notEqual(canonicalCatalogKey('Date A Live'), canonicalCatalogKey('Date A Live II'));
  assert.notEqual(canonicalCatalogKey('Date A Live'), canonicalCatalogKey('Date A Bullet'));

  const merged = mergeProviderCatalogs([
    [{ title: 'Date A Live II BD (Episode 1 – 10) Subtitle Indonesia + OVA', url: 'https://otakudesu.blog/anime/dal-s2/', provider: 'otakudesu', status: 2 }],
    [{ title: 'Date A Live Season 2', url: 'https://ylnime.com/anime/date-a-live-s2/', provider: 'ylnime' }],
  ]);
  assert.equal(merged.length, 1);
  assert.ok(merged[0].source_urls.otakudesu);
  assert.ok(merged[0].source_urls.ylnime);
});

test('integrasi: hasil scrape di bawah MIN_UNIQUE tidak boleh mengirim prune=true (lindungi katalog existing)', async () => {
  const posts = [];
  const providerSrv = await listen((req, res) => {
    if (req.url.startsWith('/anime-list/')) {
      res.writeHead(200, { 'Content-Type': 'text/html' }).end('<a class="hodebgst" href="https://otakudesu.blog/anime/only-one/">Only One Anime</a>');
      return;
    }
    res.writeHead(503).end('down');
  });
  const workerSrv = await listen(async (req, res) => {
    if (req.method === 'GET' && req.url === '/v1/internal/catalog-list') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ status: 'ok', data: [] }));
      return;
    }
    if (req.method === 'POST' && req.url === '/v1/internal/sync-source-catalog') {
      const body = JSON.parse(await readBody(req));
      posts.push(body);
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ status: 'ok', updated: 0, inserted: body.items.length, pruned: body.prune }));
      return;
    }
    res.writeHead(404).end('not found');
  });
  try {
    const r = await runScript({
      WORKER_URL: workerSrv.url,
      ADMIN_SECRET: 'x'.repeat(20),
      YLNIME_BASE_URL: providerSrv.url,
      OTAKUDESU_BASE_URL: providerSrv.url,
      ANILIST_URL: providerSrv.url,
      SCOPE: 'ongoing',
      MIN_UNIQUE: '10',
      MAX_ENRICH: '0',
    });
    assert.equal(r.code, 1, r.out);
    assert.equal(posts.length, 1);
    assert.equal(posts[0].prune, false);
  } finally {
    providerSrv.srv.close();
    workerSrv.srv.close();
  }
});

test('applyAniListEnrichment mempertahankan anime_id wadah yang sudah ada agar riwayat tonton tidak putus', () => {
  const existingRow = {
    anime_id: 912345678,
    title: 'Sousou no Frieren',
    source_urls: { otakudesu: 'https://otakudesu.blog/anime/frieren-sub-indo/' },
  };
  const enriched = applyAniListEnrichment(existingRow, {
    id: 154587,
    idMal: 52991,
    title: { romaji: 'Sousou no Frieren', english: "Frieren: Beyond Journey's End" },
    episodes: 28,
    averageScore: 91,
    genres: ['Adventure', 'Fantasy'],
    coverImage: { large: 'https://s4.anilist.co/frieren.jpg' },
  });
  assert.equal(enriched.anime_id, 912345678);
  assert.equal(enriched.mal_id, 52991);
  assert.equal(enriched.score, 9.1);
  assert.equal(enriched.poster_url, 'https://s4.anilist.co/frieren.jpg');
});

test('parseYlnimeCatalog strips (HD)/(Uncen) prefix and skips [INFO] and (Live Action) entries', () => {
  const html = `
    <a href="?series=kny-infinity">(HD) Kimetsu no Yaiba: Infinity Castle Movie</a>
    <a href="?series=nande-koko">(Uncen) Nande Koko ni Sensei ga!?</a>
    <a href="?series=zom-100-la">(Live Action) Zom 100</a>
    <a href="?series=info-takedown">[INFO] App Ke Takedown Playstore</a>
  `;
  const list = parseYlnimeCatalog(html, 'https://ylnime.com');
  assert.deepEqual(
    list.map((x) => x.title),
    ['Kimetsu no Yaiba: Infinity Castle Movie', 'Nande Koko ni Sensei ga!?']
  );
});

test('parseYlnimeSchedule maps daily schedule sections (Senin..Minggu) for YLnime-only or Random titles', () => {
  const html = `
    <h2>Senin 23 November 2026</h2>
    <a href="?series=seitokai-ni-mo-ana-wa-aru">Seitokai ni mo Ana wa Aru!</a>
    <h2>Minggu 22 November 2026</h2>
    <a href="?series=black-clover-s2">Black Clover Season 2</a>
  `;
  const sched = parseYlnimeSchedule(html);
  assert.equal(sched.get(canonicalCatalogKey('Seitokai ni mo Ana wa Aru!')), 1);
  assert.equal(sched.get(canonicalCatalogKey('Black Clover Season 2')), 7);
});

test('parseYlnimeCatalog mengekstrak judul & poster dari Bootstrap card dengan stretched-link kosong', () => {
  const html = `
    <div class="col-6 col-md-3 col-lg-2 mb-4">
      <div class="card h-100">
        <div class="card-img-wrapper">
          <img src="https://assets.animekita.org/cover/2026/02/f6ca1bf501dde32e.jpg" class="card-img-top" loading="lazy" alt="A Portrait of Jianghu: Bu Liang Ren">
          <a href="index.php?series=a-portrait-of-jianghu-bu-liang-ren" class="stretched-link"></a>
        </div>
        <div class="card-body p-0 mt-2 text-center">
          <h6 class="card-title text-truncate mb-0 px-1">A Portrait of Jianghu: Bu Liang Ren</h6>
        </div>
      </div>
    </div>`;
  const items = parseYlnimeCatalog(html, 'https://ylnime.com');
  assert.equal(items.length, 1);
  assert.equal(items[0].title, 'A Portrait of Jianghu: Bu Liang Ren');
  assert.equal(items[0].url, 'https://ylnime.com/index.php?series=a-portrait-of-jianghu-bu-liang-ren');
  assert.equal(items[0].poster_url, 'https://assets.animekita.org/cover/2026/02/f6ca1bf501dde32e.jpg');
});
