import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeTitle,
  buildSourceFirstCatalog,
  extractMatchableTitles,
  scoreTitleMatch,
  unwrapShortenerUrl,
  isBlacklistedUrl,
  parseOtakudesuAnimeList,
  parseOtakudesuEpisodes,
  parseGenericEpisodes,
  parseYlnimeAnimeList,
  parseYlnimeEpisodes,
  parseEpisodeHostersFromHtml,
  selectEpisodesToIndex,
  hasCompleteResolutionSet,
  mergeEpisodeHostersBalanced,
} from './index-hosters.mjs';

test('scoreTitleMatch matches exact and sub-indo variants while rejecting wrong format/season/part', () => {
  assert.equal(normalizeTitle('Sousou no Frieren Subtitle Indonesia'), 'sousou no frieren');
  assert.ok(scoreTitleMatch('Sousou no Frieren', 'Sousou no Frieren Sub Indo') >= 0.9);
  assert.equal(scoreTitleMatch('Jujutsu Kaisen Season 2', 'Jujutsu Kaisen Season 1'), 0);
  assert.equal(scoreTitleMatch('Ansatsu Kyoushitsu', 'Ansatsu Kyoushitsu Movie Minna no Jikan'), 0);
  assert.equal(scoreTitleMatch('Dr. Stone', 'Dr. Stone Season 4'), 0);
  assert.equal(scoreTitleMatch('Bleach TYBW Part 1', 'Bleach TYBW Part 2'), 0);
});

test('unwrapShortenerUrl decodes shrinkearn base64 targets and filters archives/blacklisted hosts', () => {
  const target = 'https://pixeldrain.com/u/test123';
  const encoded = Buffer.from(target, 'utf8').toString('base64');
  assert.equal(unwrapShortenerUrl(`https://shrinkearn.com/st?url=${encoded}`), target);
  assert.equal(isBlacklistedUrl('Mediafire (RAR)', 'https://mediafire.com/file/abc'), true);
  assert.equal(isBlacklistedUrl('Mega', 'https://mega.nz/file/xyz'), true);
  assert.equal(isBlacklistedUrl('Racaty', 'https://racaty.io/123'), true);
  assert.equal(isBlacklistedUrl('GoFile', 'https://gofile.io/d/abc123'), false);
});

test('parseOtakudesuAnimeList and parseOtakudesuEpisodes extract clean links', () => {
  const listHtml = `
    <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren</a>
    <a class="hodebgst" href="https://otakudesu.blog/anime/solo-leveling-sub-indo/">Solo Leveling</a>
  `;
  const list = parseOtakudesuAnimeList(listHtml);
  assert.equal(list.length, 2);
  assert.equal(list[0].title, 'Sousou no Frieren');

  const epHtml = `
    <a href="https://otakudesu.blog/episode/frrn-episode-2-sub-indo/">Frieren Episode 2 Sub Indo</a>
    <a href="https://otakudesu.blog/episode/frrn-episode-1-sub-indo/">Frieren Episode 1 Sub Indo</a>
  `;
  const eps = parseOtakudesuEpisodes(epHtml);
  assert.deepEqual(eps.map((e) => e.ep), [1, 2]);
});

test('parseEpisodeHostersFromHtml ranks reliable hosters, balances qualities, and filters dead/archive links', () => {
  const html = `
    <div class="download">
      <ul>
        <li>
          <strong>Mp4 720p</strong>
          <a href="https://krakenfiles.com/view/k1">KFiles</a>
          <a href="https://pixeldrain.com/u/abc123">Pdrain</a>
          <a href="https://gofile.io/d/xyz789">Gofile</a>
          <a href="https://mega.nz/file/dead">Mega</a>
          <a href="https://ouo.io/spam">Spam</a>
        </li>
        <li>
          <strong>MKV 1080p</strong>
          <a href="https://buzzheavier.com/f/999">Buzzheavier</a>
          <a href="https://mediafire.com/file/rar">Mediafire (RAR)</a>
        </li>
        <li>
          <strong>Mp4 360p</strong>
          <a href="https://filedon.co/view/f360">Filedon</a>
        </li>
      </ul>
    </div>
  `;
  const hosters = parseEpisodeHostersFromHtml(html, 'https://otakudesu.blog/episode/test-1/', 'otakudesu');
  assert.equal(hosters.length, 4);
  assert.deepEqual(
    hosters.map((h) => `${h.quality}:${h.container}:${h.hoster}`),
    ['1080p:mkv:Buzzheavier', '720p:mp4:Pdrain', '720p:mp4:Gofile', '360p:mp4:Filedon']
  );
});

test('selectEpisodesToIndex always keeps Episode 1 plus latest episodes', () => {
  const eps = Array.from({ length: 12 }, (_, i) => ({ ep: i + 1, url: `https://x/ep-${i + 1}` }));
  const picked = selectEpisodesToIndex(eps, 4);
  assert.deepEqual(picked.map((e) => e.ep), [1, 10, 11, 12]);
  const picked8 = selectEpisodesToIndex(eps, 8);
  assert.deepEqual(picked8.map((e) => e.ep), [1, 2, 3, 4, 9, 10, 11, 12]);
  assert.equal(isBlacklistedUrl('Solidfiles', 'https://link.desustream.com/?id=dead1'), true);
  assert.equal(isBlacklistedUrl('Mega', 'https://link.desustream.com/?id=dead2'), true);
});

test('extractMatchableTitles merges title, title_alt, and Latin synonyms_json safely', () => {
  const titles = extractMatchableTitles({
    title: 'Sousou no Frieren',
    title_alt: "Frieren: Beyond Journey's End",
    synonyms_json: JSON.stringify(['Frieren at the Funeral', '葬送のフリーレン', 'Sousou no Frieren', 'ab']),
  });
  assert.deepEqual(titles, [
    'Sousou no Frieren',
    "Frieren: Beyond Journey's End",
    'Frieren at the Funeral',
  ]);
  const bad = extractMatchableTitles({ title: 'Naruto', synonyms_json: '{bad json' });
  assert.deepEqual(bad, ['Naruto']);
});

test('buildSourceFirstCatalog binds source_urls to matched anime and keeps source-only titles deduplicated', () => {
  const catalog = [{ id: 11061, title: 'Hunter x Hunter (2011)', title_alt: 'Hunter x Hunter' }];
  const providerIndices = [
    {
      provider: 'ylnime',
      base: 'https://ylnime.com',
      list: [
        { title: 'Hunter x Hunter (2011)', url: 'https://ylnime.com/?series=hunter-x-hunter-2011', provider: 'ylnime' },
        { title: 'Classroom of the Elite Season 4', url: 'https://ylnime.com/?series=classroom-of-the-elite-season-4', provider: 'ylnime' },
      ],
    },
    {
      provider: 'otakudesu',
      base: 'https://otakudesu.blog',
      list: [
        { title: 'Classroom of the Elite Season 4 Subtitle Indonesia', url: 'https://otakudesu.blog/anime/cote-s4/', provider: 'otakudesu' },
      ],
    },
  ];
  const { matched, syncItems } = buildSourceFirstCatalog(catalog, providerIndices);
  assert.equal(matched.length, 1);
  assert.equal(syncItems.length, 2);
  assert.equal(syncItems[0].anime_id, 11061);
  assert.equal(syncItems[0].source_urls.ylnime, 'https://ylnime.com/?series=hunter-x-hunter-2011');
  assert.equal(syncItems[1].anime_id, undefined);
  assert.equal(syncItems[1].source_urls.ylnime, 'https://ylnime.com/?series=classroom-of-the-elite-season-4');
  assert.equal(syncItems[1].source_urls.otakudesu, 'https://otakudesu.blog/anime/cote-s4/');
});

test('parseEpisodeHostersFromHtml ignores Otakudesu episode navigation dropdown options', () => {
  const html = `
    <select id="select-ep">
      <option value="https://otakudesu.blog/episode/yscnd-episode-26-sub-indo/">Episode 26</option>
      <option value="https://otakudesu.blog/episode/yscnd-episode-25-sub-indo/">Episode 25</option>
    </select>
    <iframe src="https://odstream.xyz/embed/abc123"></iframe>
  `;
  const res = parseEpisodeHostersFromHtml(html, 'https://otakudesu.blog/episode/yscnd-episode-27-sub-indo/', 'otakudesu');
  assert.equal(res.some((r) => r.url.includes('otakudesu.blog/episode/')), false);
  assert.equal(res.some((r) => r.url.includes('odstream.xyz/embed/abc123')), true);
});

test('parseEpisodeHostersFromHtml keeps Otakudesu desustream safelink for fast hosters and drops blacklisted labels', () => {
  const html = `
    <li>
      <strong>Mp4 1080p</strong>
      <a href="https://desustream.com/safelink/link/?id=eX1">Filedon</a>
      <a href="https://desustream.com/safelink/link/?id=eX2">Pdrain</a>
      <a href="https://desustream.com/safelink/link/?id=eX3">GoFile</a>
      <a href="https://desustream.com/safelink/link/?id=eX4">Mega</a>
      <a href="https://desustream.com/safelink/link/?id=eX5">Acefile</a>
    </li>
  `;
  const res = parseEpisodeHostersFromHtml(html, 'https://otakudesu.blog/episode/blkclv-s2-episode-9-sub-indo/', 'otakudesu');
  const hosters = res.map((r) => r.hoster);
  assert.deepEqual(hosters.sort(), ['Filedon', 'GoFile', 'Pdrain']);
});

test('parseEpisodeHostersFromHtml harvests YLnime standalone GoFile button alongside mirror select embeds', () => {
  const b64 = Buffer.from('<iframe src="https://icefile.web.id/e/hd2"></iframe>').toString('base64');
  const html = `
    <select class="mirror">
      <option value="">Select Video Server</option>
      <option value="${b64}">HD-2</option>
    </select>
    <a class="download-btn" href="https://gofile.io/d/MYZtgPQN">Download</a>
  `;
  const res = parseEpisodeHostersFromHtml(html, 'https://ylnime.com/black-clover-2nd-season-episode-9-subtitle-indonesia/', 'ylnime');
  assert.equal(res.some((r) => r.isEmbed && r.url === 'https://icefile.web.id/e/hd2'), true);
  assert.equal(res.some((r) => !r.isEmbed && r.url === 'https://gofile.io/d/MYZtgPQN'), true);
});

test('parseOtakudesuEpisodes maps single-episode Movie link without episode number to ep 1', () => {
  const html = `
    <div class="episodelist">
      <ul>
        <li><a href="https://otakudesu.blog/episode/kimi-na-wa-sub-indo/">Kimi no Na Wa BD Subtitle Indonesia</a></li>
      </ul>
    </div>
  `;
  const eps = parseOtakudesuEpisodes(html);
  assert.deepEqual(eps, [{ ep: 1, url: 'https://otakudesu.blog/episode/kimi-na-wa-sub-indo/' }]);
});

test('parseEpisodeHostersFromHtml recognizes YLnime MP4HD (720p) and FULLHD (1080p) labels', () => {
  const html = `
    <li><strong>MP4HD</strong> <a href="https://pixeldrain.com/u/mp4hd720">PDrain</a></li>
    <li><strong>FULLHD</strong> <a href="https://filedon.co/view/fullhd1080">Filedon</a></li>
  `;
  const res = parseEpisodeHostersFromHtml(html, 'https://ylnime.com/tensura-nikki-episode-12-end/', 'ylnime');
  assert.equal(res.some((r) => r.quality === '720p' && r.url.includes('mp4hd720')), true);
  assert.equal(res.some((r) => r.quality === '1080p' && r.url.includes('fullhd1080')), true);
});

test('parseGenericEpisodes maps YLnime Movie link inside eplister/lstepsiode without /episode/ to ep 1', () => {
  const html = `
    <div class="lstepsiode listeps">
      <ul>
        <li>
          <span class="eps">Movie</span>
          <a href="https://ylnime.com/black-clover-mahou-tei-no-ken-sword-of-the-wizard-king/">Black Clover: Mahou Tei no Ken</a>
        </li>
      </ul>
    </div>
  `;
  const eps = parseGenericEpisodes(html, 'https://ylnime.com');
  assert.deepEqual(eps, [{ ep: 1, url: 'https://ylnime.com/black-clover-mahou-tei-no-ken-sword-of-the-wizard-king/' }]);
});

test('parseYlnimeAnimeList and parseYlnimeEpisodes handle index.php?series= and Episode 0 (al-24930-0)', () => {
  const listHtml = `<a href="index.php?series=mushoku-tensei-honki-dasu">Mushoku Tensei II</a>`;
  const list = parseYlnimeAnimeList(listHtml, 'https://ylnime.com');
  assert.deepEqual(list, [{ title: 'Mushoku Tensei II', url: 'https://ylnime.com/index.php?series=mushoku-tensei-honki-dasu', provider: 'ylnime' }]);

  const epHtml = `
    <a href="index.php?series=mushoku-tensei-honki-dasu&episode=al-24930-0">Episode 0</a>
    <a href="index.php?series=mushoku-tensei-honki-dasu&episode=al-24930-1">Episode 1</a>
  `;
  const eps = parseYlnimeEpisodes(epHtml, 'https://ylnime.com');
  assert.deepEqual(eps, [
    { ep: 0, url: 'https://ylnime.com/index.php?series=mushoku-tensei-honki-dasu&episode=al-24930-0' },
    { ep: 1, url: 'https://ylnime.com/index.php?series=mushoku-tensei-honki-dasu&episode=al-24930-1' },
  ]);
});

test('parseYlnimeAnimeList extracts title from Bootstrap card with empty stretched-link', () => {
  const html = `
    <div class="card h-100">
      <div class="card-img-wrapper">
        <img src="https://assets.animekita.org/cover/2026/02/f6ca1bf501dde32e.jpg" alt="A Portrait of Jianghu: Bu Liang Ren">
        <a href="index.php?series=a-portrait-of-jianghu-bu-liang-ren" class="stretched-link"></a>
      </div>
      <div class="card-body">
        <h6 class="card-title text-truncate">A Portrait of Jianghu: Bu Liang Ren</h6>
      </div>
    </div>`;
  const list = parseYlnimeAnimeList(html, 'https://ylnime.com');
  assert.deepEqual(list, [{
    title: 'A Portrait of Jianghu: Bu Liang Ren',
    url: 'https://ylnime.com/index.php?series=a-portrait-of-jianghu-bu-liang-ren',
    provider: 'ylnime',
  }]);
});

test('parseYlnimeEpisodes recognizes Episode Movie with al-149330-1 and inline script video/pixeldrain URLs', () => {
  const seriesHtml = `
    <a href="index.php?series=date-a-live-movie-mayuri-judgment&amp;episode=al-149330-1">Tonton Sekarang</a>
    <a href="index.php?series=date-a-live-movie-mayuri-judgment&amp;episode=al-149330-1&amp;reso=720p">Episode Movie</a>
  `;
  const eps = parseYlnimeEpisodes(seriesHtml, 'https://ylnime.com');
  assert.equal(eps.length, 1);
  assert.equal(eps[0].ep, 1);
  assert.equal(eps[0].url, 'https://ylnime.com/index.php?series=date-a-live-movie-mayuri-judgment&episode=al-149330-1');

  const epHtml = `
    <div data-video="https://cdn.animeverse.example/streams/mayuri-720p.mp4"></div>
    <script>
      const sources = [{"file":"https://cdn.animeverse.example/hls/mayuri-480p.m3u8"},{"url":"https://pixeldrain.com/u/Mayuri720"}];
    </script>
  `;
  const hosters = parseEpisodeHostersFromHtml(epHtml, eps[0].url, 'ylnime');
  const urls = hosters.map((h) => h.url);
  assert.ok(urls.includes('https://cdn.animeverse.example/hls/mayuri-480p.m3u8'));
  assert.ok(urls.includes('https://pixeldrain.com/u/Mayuri720'));
});

test('parseEpisodeHostersFromHtml mengekstrak const streams JSON YLnime dengan slash ter-escape', () => {
  const html = `<script>const streams = [{"reso":"720p","link":"https:\\/\\/s3.animeverse.id\\/ro\\/ffa8-1791046151587.mp4","provide":871},{"reso":"720p","link":"https:\\/\\/pixeldrain.com\\/api\\/file\\/wF9Lj6Ad?download","provide":11}];</script>`;
  const hosters = parseEpisodeHostersFromHtml(html, 'https://ylnime.com/?series=blk-clver-s2-sub-indo&episode=al-154261-1', 'ylnime');
  assert.equal(hosters.length, 2);
  assert.equal(hosters[0].hoster, 'Pixeldrain');
  assert.equal(hosters[0].url, 'https://pixeldrain.com/api/file/wF9Lj6Ad?download');
  assert.equal(hosters[1].hoster, 'Animeverse');
  assert.equal(hosters[1].url, 'https://s3.animeverse.id/ro/ffa8-1791046151587.mp4');
});

test('parseEpisodeHostersFromHtml meng-encode spasi di URL s3.animeverse.id dan mengenali sjkt.animekita.org HLS/MP4', () => {
  const html = `<script>const streams = [{"reso":"1080p","link":"https:\\/\\/s3.animeverse.id\\/mp4\\/Violet Evergarden Movie.mp4"},{"reso":"720p","link":"https:\\/\\/sjkt.animekita.org\\/hls\\/zuFmWTSf\\/output.m3u8"}];</script>`;
  const hosters = parseEpisodeHostersFromHtml(html, 'https://ylnime.com/?series=violet-evergarden-movie&episode=al-153096-movie', 'ylnime');
  assert.equal(hosters.length, 2);
  assert.equal(hosters.some((h) => h.url === 'https://s3.animeverse.id/mp4/Violet%20Evergarden%20Movie.mp4'), true);
  assert.equal(hosters.some((h) => h.hoster === 'AnimeKita' && h.container === 'hls' && h.url.includes('sjkt.animekita.org')), true);
});

test('parseEpisodeHostersFromHtml menerima domain desustream.net untuk mirror odcdn/odstream', () => {
  const html = `
    <div id="embed_holder">
      <iframe src="https://desustream.net/dstream/odcdn/?id=TXdXSlhBdGVKMGNnWkQy"></iframe>
    </div>
  `;
  const rows = parseEpisodeHostersFromHtml(html, 'https://otakudesu.blog/episode/mgexpl-episode-9-sub-indo/');
  assert.ok(rows.some((r) => r.url.startsWith('https://desustream.net/dstream/odcdn/')));
});

test('parseEpisodeHostersFromHtml extracts 480p, 720p, and 1080p from <source size> and context-labeled Pixeldrain links', () => {
  const html = `
    <video id="player">
      <source src="https://s3.animeverse.id/ro/hash-a.mp4" type="video/mp4" size="1080" label="1080p" />
      <source src="https://s3.animeverse.id/ro/hash-b.mp4" type="video/mp4" size="720" label="720p" />
      <source src="https://s3.animeverse.id/ro/hash-c.mp4" type="video/mp4" size="480" label="480p" />
    </video>
    <div class="card"><h6>Download 1080p</h6><a href="https://pixeldrain.com/u/pd1080">Pixeldrain</a></div>
    <div class="card"><h6>Download 480p</h6><a href="https://pixeldrain.com/u/pd480">Pixeldrain</a></div>
  `;
  const rows = parseEpisodeHostersFromHtml(html, 'https://ylnime.com/index.php?series=frieren&episode=al-1-1', 'ylnime');
  const quals = new Set(rows.map((r) => r.quality));
  assert.equal(quals.has('1080p'), true);
  assert.equal(quals.has('720p'), true);
  assert.equal(quals.has('480p'), true);
});

test('hasCompleteResolutionSet enforces all 3 resolutions (480p, 720p, 1080p) and mergeEpisodeHostersBalanced preserves cross-provider resolutions', () => {
  assert.equal(hasCompleteResolutionSet([{ quality: '1080p' }]), false);
  assert.equal(hasCompleteResolutionSet([{ quality: '1080p' }, { quality: '720p' }]), false);
  assert.equal(hasCompleteResolutionSet([{ quality: '480p' }, { quality: '720p' }]), false);
  assert.equal(hasCompleteResolutionSet([{ quality: '480p' }, { quality: '720p' }, { quality: '1080p' }]), true);

  const fromYlnime = [
    { quality: '1080p', url: 'https://s3.animeverse.id/ep1-1080.mp4', hoster: 'Animeverse', provider: 'ylnime' },
    { quality: '720p', url: 'https://s3.animeverse.id/ep1-720.mp4', hoster: 'Animeverse', provider: 'ylnime' },
  ];
  const fromOtaku = [
    { quality: '480p', url: 'https://pixeldrain.com/u/ep1480', hoster: 'Pixeldrain', provider: 'otakudesu' },
    { quality: '720p', url: 'https://pixeldrain.com/u/ep1720', hoster: 'Pixeldrain', provider: 'otakudesu' },
    { quality: '1080p', url: 'https://filedon.co/view/ep11080', hoster: 'Filedon', provider: 'otakudesu' },
  ];
  const merged = mergeEpisodeHostersBalanced(fromYlnime, fromOtaku);
  assert.equal(hasCompleteResolutionSet(merged), true);
  assert.equal(merged.filter((x) => x.quality === '480p').length, 1);
  assert.equal(merged.filter((x) => x.quality === '720p').length, 2);
  assert.equal(merged.filter((x) => x.quality === '1080p').length, 2);

  const fullSeason = Array.from({ length: 24 }, (_, i) => ({ ep: i + 1, url: `https://x/ep-${i + 1}` }));
  assert.equal(selectEpisodesToIndex(fullSeason, 500).length, 24);
});
