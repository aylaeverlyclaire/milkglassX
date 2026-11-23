-- Terapkan seed terverifikasi dari data/curated_catalog_patch.json ke D1
-- Date A Live Season 1-5 & Date A Bullet (Mayuri Judgment, Dead or Bullet, Nightmare or Queen)

UPDATE animes
SET episodes_total = 12, status = 2, type = 'TV',
    source_urls_json = '{"ylnime":"https://ylnime.com/index.php?series=date-live-subtitle-indonesia"}'
WHERE id = 1367699440;

UPDATE animes
SET episodes_total = 10, status = 2, type = 'TV',
    source_urls_json = '{"ylnime":"https://ylnime.com/index.php?series=date-live-ii-subtitle-indonesia"}'
WHERE id = 931329839;

UPDATE animes
SET episodes_total = 12, status = 2, type = 'TV',
    source_urls_json = '{"ylnime":"https://ylnime.com/index.php?series=date-live-season-3-subtitle-indonesia"}'
WHERE id = 914552220;

UPDATE animes
SET episodes_total = 12, status = 2, type = 'TV',
    source_urls_json = '{"ylnime":"https://ylnime.com/index.php?series=date-live-iv-subtitle-indonesia"}'
WHERE id = 1192741901;

UPDATE animes
SET episodes_total = 12, status = 2, type = 'TV'
WHERE id = 1175964282;

UPDATE animes
SET episodes_total = 1, status = 2, type = 'MOVIE'
WHERE id IN (1056084127, 1759005256, 975727851);

-- Pastikan semua Movie yang masih 0 episode otomatis terisi 1 episode & status tamat
UPDATE animes
SET episodes_total = 1, status = 2
WHERE type = 'MOVIE' AND episodes_total = 0;
