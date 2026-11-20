-- Optimasi index D1 untuk menghindari full table scan & filesort pada /catalog/home, /catalog/browse, dan /catalog/episode/:id
CREATE INDEX IF NOT EXISTS idx_animes_popularity ON animes(popularity DESC);
CREATE INDEX IF NOT EXISTS idx_sources_ep_sort ON stream_sources(episode_id, is_active, cooldown_until, fail_count, quality DESC);
