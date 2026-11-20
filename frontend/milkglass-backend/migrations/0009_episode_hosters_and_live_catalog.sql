-- Multi-hoster verified episode URLs & live-only catalog visibility flag.
ALTER TABLE animes ADD COLUMN has_live_source INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS episode_hosters (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  anime_id INTEGER NOT NULL,
  episode_number INTEGER NOT NULL,
  title_key TEXT NOT NULL,
  title_alt_key TEXT,
  url TEXT NOT NULL,
  quality TEXT NOT NULL DEFAULT '720p',
  hoster TEXT NOT NULL DEFAULT 'Hoster',
  provider TEXT NOT NULL DEFAULT 'otakudesu',
  container TEXT NOT NULL DEFAULT 'mp4',
  referer TEXT NOT NULL DEFAULT '',
  is_live INTEGER NOT NULL DEFAULT 1,
  last_verified_at INTEGER NOT NULL,
  fail_count INTEGER NOT NULL DEFAULT 0,
  UNIQUE(anime_id, episode_number, url),
  FOREIGN KEY (anime_id) REFERENCES animes(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_episode_hosters_title
  ON episode_hosters(title_key, episode_number, is_live);
CREATE INDEX IF NOT EXISTS idx_episode_hosters_alt
  ON episode_hosters(title_alt_key, episode_number, is_live);
CREATE INDEX IF NOT EXISTS idx_episode_hosters_anime
  ON episode_hosters(anime_id, is_live, episode_number);
CREATE INDEX IF NOT EXISTS idx_animes_live_pop
  ON animes(has_live_source, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_animes_live_status_pop
  ON animes(has_live_source, status, popularity DESC);
