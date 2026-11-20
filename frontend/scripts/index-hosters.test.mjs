import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeTitle,
  scoreTitleMatch,
  parseOtakudesuAnimeList,
  parseOtakudesuEpisodes,
  parseEpisodeHostersFromHtml,
  selectEpisodesToIndex,
} from './index-hosters.mjs';

test('scoreTitleMatch matches exact and sub-indo variants while rejecting wrong season', () => {
  assert.equal(normalizeTitle('Sousou no Frieren Subtitle Indonesia'), 'sousou no frieren');
  assert.ok(scoreTitleMatch('Sousou no Frieren', 'Sousou no Frieren Sub Indo') >= 0.9);
  assert.ok(scoreTitleMatch('Jujutsu Kaisen Season 2', 'Jujutsu Kaisen Season 1') < 0.45);
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

test('parseEpisodeHostersFromHtml extracts multi-hoster multi-quality links and filters ads', () => {
  const html = `
    <div class="download">
      <ul>
        <li>
          <strong>Mp4 720p</strong>
          <a href="https://pixeldrain.com/u/abc123">Pdrain</a>
          <a href="https://gofile.io/d/xyz789">Gofile</a>
          <a href="https://ouo.io/spam">Spam</a>
        </li>
        <li>
          <strong>MKV 1080p</strong>
          <a href="https://buzzheavier.com/f/999">Buzzheavier</a>
          <a href="https://krakenfiles.com/view/kkk">KFiles</a>
        </li>
      </ul>
    </div>
  `;
  const hosters = parseEpisodeHostersFromHtml(html, 'https://otakudesu.blog/episode/test-1/', 'otakudesu');
  assert.equal(hosters.length, 4);
  assert.deepEqual(
    hosters.map((h) => `${h.quality}:${h.container}:${h.hoster}`),
    ['1080p:mkv:Buzzheavier', '1080p:mkv:KFiles', '720p:mp4:Pdrain', '720p:mp4:Gofile']
  );
});

test('selectEpisodesToIndex always keeps Episode 1 plus latest episodes', () => {
  const eps = Array.from({ length: 12 }, (_, i) => ({ ep: i + 1, url: `https://x/ep-${i + 1}` }));
  const picked = selectEpisodesToIndex(eps, 4);
  assert.deepEqual(picked.map((e) => e.ep), [1, 10, 11, 12]);
});
