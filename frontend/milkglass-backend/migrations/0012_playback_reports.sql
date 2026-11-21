-- Wave 3: Episode-level playback failure & TTFF telemetry reports + automated GitHub Issue deduplication
CREATE TABLE IF NOT EXISTS playback_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    title_key TEXT NOT NULL,
    ep INTEGER NOT NULL,
    anime_id INTEGER,
    status TEXT NOT NULL,
    resolve_ms INTEGER NOT NULL DEFAULT 0,
    ttff_ms INTEGER NOT NULL DEFAULT 0,
    cache_hit INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT '',
    hoster TEXT NOT NULL DEFAULT '',
    quality TEXT NOT NULL DEFAULT '',
    error_code TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    provider_diagnostics TEXT NOT NULL DEFAULT '',
    app_version TEXT NOT NULL DEFAULT '',
    asn INTEGER NOT NULL DEFAULT 0,
    github_issue_url TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_playback_reports_created ON playback_reports(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_playback_reports_status_created ON playback_reports(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_playback_reports_key_created ON playback_reports(title_key, ep, status, created_at DESC);
