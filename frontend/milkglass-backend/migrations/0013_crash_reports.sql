-- Automated client app crash reports + GitHub Issue deduplication
CREATE TABLE IF NOT EXISTS crash_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fingerprint TEXT NOT NULL,
    exception_type TEXT NOT NULL,
    exception_message TEXT NOT NULL DEFAULT '',
    stack_trace TEXT NOT NULL,
    thread_name TEXT NOT NULL DEFAULT '',
    app_version TEXT NOT NULL DEFAULT '',
    version_code INTEGER NOT NULL DEFAULT 0,
    device_model TEXT NOT NULL DEFAULT '',
    android_version TEXT NOT NULL DEFAULT '',
    screen TEXT NOT NULL DEFAULT '',
    is_fatal INTEGER NOT NULL DEFAULT 1,
    ip_hash TEXT NOT NULL DEFAULT '',
    github_issue_url TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_crash_reports_created ON crash_reports(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_crash_reports_fp_created ON crash_reports(fingerprint, created_at DESC);
