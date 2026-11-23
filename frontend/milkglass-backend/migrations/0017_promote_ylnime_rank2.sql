-- Promosikan YLnime (https://ylnime.com) ke Rank 2 menggantikan samehadaku.li
CREATE TABLE IF NOT EXISTS app_config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

DELETE FROM catalog_meta WHERE key = 'samehadaku_base_url';

INSERT OR REPLACE INTO catalog_meta (key, value, updated_at)
VALUES ('ylnime_base_url', 'https://ylnime.com', CAST(strftime('%s', 'now') AS INTEGER));

INSERT OR REPLACE INTO app_config (key, value)
VALUES ('source_cascade_order', '["otakudesu","ylnime"]');

UPDATE animes
SET has_live_source = 0
WHERE source_urls_json IS NULL
   OR (source_urls_json NOT LIKE '%"otakudesu"%' AND source_urls_json NOT LIKE '%"ylnime"%');
