import test from 'node:test';
import assert from 'node:assert/strict';
import {
  POPULAR_PRIORITY_TITLES,
  prioritizePopularCatalog,
  buildCuratedHostersPatchPayload,
} from './lib/popular-priority.mjs';
import { parseTitleMeta, extractMatchableTitles } from './index-hosters.mjs';

test('POPULAR_PRIORITY_TITLES berisi tepat 100 judul prioritas populer', () => {
  assert.equal(POPULAR_PRIORITY_TITLES.length, 100);
});

test('prioritizePopularCatalog mendahulukan 100 anime populer dan mendukung mode popularOnly', () => {
  const sample = [
    { id: 1, title: 'Unknown Incomplete Show', episodes_total: 0, popularity: 100 },
    { id: 2, title: 'Spy x Family', episodes_total: 12, popularity: 50000 },
    { id: 3, title: 'Date A Live', episodes_total: 12, popularity: 40000 },
    { id: 4, title: 'Other Completed Show', episodes_total: 24, popularity: 200 },
  ];
  const allOrdered = prioritizePopularCatalog(sample, 10, false, parseTitleMeta, extractMatchableTitles);
  assert.deepEqual(
    allOrdered.map((x) => x.id),
    [3, 2, 1, 4]
  );

  const onlyPop = prioritizePopularCatalog(sample, 10, true, parseTitleMeta, extractMatchableTitles);
  assert.deepEqual(
    onlyPop.map((x) => x.id),
    [3, 2]
  );
});

test('buildCuratedHostersPatchPayload menyusun struktur ringkas dan memfilter URL non-HTTPS', () => {
  const payload = buildCuratedHostersPatchPayload(
    [
      {
        anime_id: 964377617,
        title: 'Spy x Family',
        episodes: [
          {
            ep: 1,
            hosters: [
              { quality: '720p', container: 'mp4', hoster: 'Pixeldrain', url: 'https://pixeldrain.com/u/abc123', provider: 'otakudesu' },
              { quality: '720p', container: 'mp4', hoster: 'Bad', url: 'javascript:alert(1)', provider: 'otakudesu' },
            ],
          },
        ],
      },
    ],
    2
  );
  assert.equal(payload.version, 2);
  assert.equal(payload.stats.anime_count, 1);
  assert.equal(payload.stats.episode_count, 1);
  assert.equal(payload.stats.hoster_links, 1);
  assert.equal(payload.entries[0].title_key, 'spyxfamily');
  assert.equal(payload.entries[0].episodes['1'][0].u, 'https://pixeldrain.com/u/abc123');
});
