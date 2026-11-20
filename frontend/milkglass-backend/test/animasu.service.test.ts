import { describe, expect, it } from 'vitest';
import {
  animasuSlugToStableId,
  mapAnimasuItemToCatalogRow,
  parseAnimasuSearchHtml,
} from '../src/services/animasu.service';

describe('animasu.service', () => {
  it('parses Animasu search result cards accurately', () => {
    const html = `
      <div class="postbody"><div class="listupd">
        <div class="bs"><div class="bsx">
          <a href="https://animasu.love/anime/date-a-live-s5/" title="Nonton Anime Date A Live Season 5 Sub Indo">
            <div class="limit">
              <div class="typez TV">TV</div>
              <div class="bt"><span class="epx">12 Episode</span><span class="sb">Selesai ✓</span></div>
              <img src="https://i2.wp.com/animasu.love/wp-content/uploads/2024/02/141438.jpg" class="wp-post-image"/>
            </div>
            <div class="tt">Date A Live Season 5</div>
          </a>
        </div></div>
        <div class="bs"><div class="bsx">
          <a href="https://animasu.love/anime/date-a-live-movie-mayuri-judgment/" title="Nonton Anime Date A Live Movie: Mayuri Judgment Sub Indo">
            <div class="limit">
              <div class="typez Movie">Movie</div>
              <div class="bt"><span class="epx">Movie</span><span class="sb">Selesai ✓</span></div>
              <img data-src="https://i1.wp.com/animasu.love/wp-content/uploads/mayuri.jpg" class="wp-post-image"/>
            </div>
            <div class="tt">Date A Live Movie: Mayuri Judgment</div>
          </a>
        </div></div>
      </div></div>
    `;
    const items = parseAnimasuSearchHtml(html, 'https://animasu.love');
    expect(items).toHaveLength(2);
    expect(items[0].slug).toBe('date-a-live-s5');
    expect(items[0].title).toBe('Date A Live Season 5');
    expect(items[0].episodes_total).toBe(12);
    expect(items[0].status).toBe(2);
    expect(items[0].type).toBe('TV');
    expect(items[1].type).toBe('Movie');
    expect(items[1].poster_url).toContain('mayuri.jpg');

    const row = mapAnimasuItemToCatalogRow(items[0]);
    expect(row.id).toBe(animasuSlugToStableId('date-a-live-s5'));
    expect(row.source).toBe('animasu');
    expect(row.title).toBe('Date A Live Season 5');
  });
});
