CREATE TABLE IF NOT EXISTS catalog_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS catalog_episodes (
  slug TEXT PRIMARY KEY,
  anilist_id INTEGER,
  title TEXT NOT NULL,
  romaji_title TEXT,
  episode INTEGER NOT NULL,
  poster_url TEXT NOT NULL,
  banner_url TEXT,
  synopsis TEXT,
  streams_json TEXT NOT NULL DEFAULT '[]',
  is_recommended INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_catalog_title ON catalog_episodes(title);
CREATE INDEX IF NOT EXISTS idx_catalog_anilist ON catalog_episodes(anilist_id, episode);
CREATE INDEX IF NOT EXISTS idx_catalog_rec ON catalog_episodes(is_recommended, updated_at DESC);
