CREATE TABLE IF NOT EXISTS animes (
  id INTEGER PRIMARY KEY,
  mal_id INTEGER,
  title TEXT NOT NULL,
  title_alt TEXT,
  synopsis TEXT,
  score REAL,
  type TEXT,
  status INTEGER NOT NULL,
  schedule_day INTEGER DEFAULT 0,
  duration TEXT,
  studio TEXT,
  release_season TEXT,
  poster_url TEXT,
  genres_json TEXT,
  popularity INTEGER DEFAULT 0,
  updated_at INTEGER NOT NULL,
  episodes_total INTEGER NOT NULL DEFAULT 0,
  next_airing_at INTEGER NOT NULL DEFAULT 0,
  next_airing_ep INTEGER NOT NULL DEFAULT 0,
  has_live_source INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS episodes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  anime_id INTEGER NOT NULL,
  episode_number INTEGER NOT NULL,
  name TEXT NOT NULL,
  op_start INTEGER,
  op_end INTEGER,
  ed_start INTEGER,
  ed_end INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(anime_id, episode_number),
  FOREIGN KEY (anime_id) REFERENCES animes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS stream_sources (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  episode_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  quality INTEGER NOT NULL,
  url_payload TEXT NOT NULL,
  release_group TEXT NOT NULL DEFAULT 'default',
  sub_kind TEXT NOT NULL DEFAULT 'hardsub-id',
  duration_sec INTEGER DEFAULT 0,
  file_size_bytes INTEGER DEFAULT 0,
  fail_count INTEGER DEFAULT 0,
  cooldown_until INTEGER DEFAULT 0,
  is_active INTEGER DEFAULT 1,
  UNIQUE(episode_id, type, quality, release_group),
  FOREIGN KEY (episode_id) REFERENCES episodes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS source_telemetry_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id INTEGER NOT NULL,
  reason TEXT NOT NULL,
  ip_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(source_id, ip_hash),
  FOREIGN KEY (source_id) REFERENCES stream_sources(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  photo_url TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  generation INTEGER NOT NULL DEFAULT 1,
  current_token_hash TEXT UNIQUE NOT NULL,
  previous_token_hash TEXT,
  rotated_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  is_revoked INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_histories (
  user_id TEXT NOT NULL,
  anime_id INTEGER NOT NULL,
  episode_id INTEGER NOT NULL,
  progress_sec INTEGER NOT NULL,
  max_progress_sec INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, episode_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (anime_id) REFERENCES animes(id) ON DELETE CASCADE,
  FOREIGN KEY (episode_id) REFERENCES episodes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_bookmarks (
  user_id TEXT NOT NULL,
  anime_id INTEGER NOT NULL,
  pinned INTEGER DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, anime_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (anime_id) REFERENCES animes(id) ON DELETE CASCADE
);

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

CREATE TABLE IF NOT EXISTS play_hints (
  key TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  report_count INTEGER NOT NULL DEFAULT 1
);

-- URL direct hoster yang SUDAH terverifikasi bisa diputar oleh klien lain (dilaporkan otomatis oleh klien).
-- Berumur pendek: banyak hoster memakai URL kedaluwarsa/terikat IP, jadi klien tetap memverifikasi sebelum memakai.
CREATE TABLE IF NOT EXISTS play_direct (
  key TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  report_count INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_play_direct_updated ON play_direct(updated_at);

CREATE INDEX IF NOT EXISTS idx_animes_status_pop ON animes(status, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_animes_sched_pop ON animes(schedule_day, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_animes_updated ON animes(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_episodes_anime ON episodes(anime_id, episode_number ASC);
CREATE INDEX IF NOT EXISTS idx_sources_ep ON stream_sources(episode_id, is_active);
CREATE INDEX IF NOT EXISTS idx_telemetry_created ON source_telemetry_events(created_at);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON user_sessions(user_id, is_revoked, rotated_at ASC);
CREATE INDEX IF NOT EXISTS idx_sessions_prev_hash ON user_sessions(previous_token_hash);
CREATE INDEX IF NOT EXISTS idx_catalog_title ON catalog_episodes(title);
CREATE INDEX IF NOT EXISTS idx_catalog_anilist ON catalog_episodes(anilist_id, episode);
CREATE INDEX IF NOT EXISTS idx_catalog_rec ON catalog_episodes(is_recommended, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_play_hints_updated ON play_hints(updated_at);

CREATE INDEX IF NOT EXISTS idx_animes_popularity ON animes(popularity DESC);
CREATE INDEX IF NOT EXISTS idx_sources_ep_sort ON stream_sources(episode_id, is_active, cooldown_until, fail_count, quality DESC);

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
CREATE INDEX IF NOT EXISTS idx_episode_hosters_title ON episode_hosters(title_key, episode_number, is_live);
CREATE INDEX IF NOT EXISTS idx_episode_hosters_alt ON episode_hosters(title_alt_key, episode_number, is_live);
CREATE INDEX IF NOT EXISTS idx_episode_hosters_anime ON episode_hosters(anime_id, is_live, episode_number);
CREATE INDEX IF NOT EXISTS idx_animes_live_pop ON animes(has_live_source, popularity DESC);
CREATE INDEX IF NOT EXISTS idx_animes_live_status_pop ON animes(has_live_source, status, popularity DESC);
