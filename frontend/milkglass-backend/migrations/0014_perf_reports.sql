-- Daily per-device performance telemetry reports (auto-stops on device once stable)
CREATE TABLE IF NOT EXISTS perf_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_key TEXT NOT NULL,
    app_version TEXT NOT NULL DEFAULT '',
    version_code INTEGER NOT NULL DEFAULT 0,
    device_model TEXT NOT NULL DEFAULT '',
    android_version TEXT NOT NULL DEFAULT '',
    startup_ms INTEGER NOT NULL DEFAULT 0,
    avg_ttff_ms INTEGER NOT NULL DEFAULT 0,
    max_ttff_ms INTEGER NOT NULL DEFAULT 0,
    play_attempts INTEGER NOT NULL DEFAULT 0,
    play_failures INTEGER NOT NULL DEFAULT 0,
    crash_count INTEGER NOT NULL DEFAULT 0,
    memory_peak_mb INTEGER NOT NULL DEFAULT 0,
    is_stable INTEGER NOT NULL DEFAULT 0,
    consecutive_stable_days INTEGER NOT NULL DEFAULT 0,
    github_issue_url TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_perf_reports_created ON perf_reports(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_perf_reports_device_created ON perf_reports(device_key, version_code, created_at DESC);
