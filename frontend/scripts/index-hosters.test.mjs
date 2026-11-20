import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeTitle,
  scoreTitleMatch,
  unwrapShortenerUrl,
  isBlacklistedUrl,
  parseOtakudesuAnimeList,
  parseNontonAnimeIdList,
  parseOtakudesuEpisodes,
  parseEpisodeHostersFromHtml,
  selectEpisodesToIndex,
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

test('parseOtakudesuAnimeList, parseNontonAnimeIdList, and parseOtakudesuEpisodes extract clean links', () => {
  const listHtml = `
    <a class="hodebgst" href="https://otakudesu.blog/anime/frieren-sub-indo/">Sousou no Frieren</a>
    <a class="hodebgst" href="https://otakudesu.blog/anime/solo-leveling-sub-indo/">Solo Leveling</a>
  `;
  const list = parseOtakudesuAnimeList(listHtml);
  assert.equal(list.length, 2);
  assert.equal(list[0].title, 'Sousou no Frieren');

  const naHtml = `
    <a href="/anime/gachiakuta/">Gachiakuta</a>
    <a href="/anime/gachiakuta/episode-1/">Ep 1</a>
  `;
  const naList = parseNontonAnimeIdList(naHtml, 'https://nontonanimeid.stream');
  assert.equal(naList.length, 1);
  assert.equal(naList[0].url, 'https://nontonanimeid.stream/anime/gachiakuta/');

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
  assert.equal(hosters.length, 5);
  assert.deepEqual(
    hosters.map((h) => `${h.quality}:${h.container}:${h.hoster}`),
    ['1080p:mkv:Buzzheavier', '720p:mp4:Pdrain', '720p:mp4:Gofile', '720p:mp4:KFiles', '360p:mp4:Filedon']
  );
});

test('selectEpisodesToIndex always keeps Episode 1 plus latest episodes', () => {
  const eps = Array.from({ length: 12 }, (_, i) => ({ ep: i + 1, url: `https://x/ep-${i + 1}` }));
  const picked = selectEpisodesToIndex(eps, 4);
  assert.deepEqual(picked.map((e) => e.ep), [1, 10, 11, 12]);
});
