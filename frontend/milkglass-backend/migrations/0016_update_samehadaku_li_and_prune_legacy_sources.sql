-- Otomatis bersihkan override domain/konfigurasi 4 sumber lama dan arahkan Samehadaku ke https://samehadaku.li
DELETE FROM catalog_meta
WHERE key IN ('animasu_base_url', 'oploverz_base_url', 'kusonime_base_url', 'nontonanimeid_base_url');

UPDATE catalog_meta
SET value = 'https://samehadaku.li', updated_at = CAST(strftime('%s', 'now') AS INTEGER)
WHERE key = 'samehadaku_base_url' AND value LIKE '%samehadaku.how%';

INSERT OR REPLACE INTO app_config (key, value)
VALUES ('source_cascade_order', '["otakudesu","samehadaku"]');
