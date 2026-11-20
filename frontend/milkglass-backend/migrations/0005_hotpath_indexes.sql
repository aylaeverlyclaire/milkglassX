CREATE INDEX IF NOT EXISTS idx_histories_user_updated ON user_histories(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_bookmarks_user_pin_updated ON user_bookmarks(user_id, pinned DESC, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sources_ep_active_rank ON stream_sources(episode_id, fail_count ASC, quality DESC) WHERE is_active = 1;
CREATE INDEX IF NOT EXISTS idx_sources_unhealthy ON stream_sources(cooldown_until, fail_count) WHERE fail_count > 0 OR cooldown_until > 0;
CREATE INDEX IF NOT EXISTS idx_catalog_updated ON catalog_episodes(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_animes_pop ON animes(popularity DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON user_sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_sessions_revoked_rot ON user_sessions(rotated_at) WHERE is_revoked = 1;
