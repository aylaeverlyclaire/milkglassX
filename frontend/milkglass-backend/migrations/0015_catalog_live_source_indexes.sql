-- Indeks komposit untuk mempercepat query katalog eksklusif Samehadaku & Otakudesu
-- (/v1/catalog/home, /snapshot, /browse, /status) serta antrean enrich AniList.
CREATE INDEX IF NOT EXISTS idx_animes_live_status_pop
  ON animes(has_live_source, status, popularity DESC, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_animes_live_enrich_queue
  ON animes(has_live_source, status, updated_at ASC);
