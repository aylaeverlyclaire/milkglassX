-- Wave 2: Per-ASN hoster EMA telemetry untuk ranking adaptif /v1/play/resolve
CREATE TABLE IF NOT EXISTS hoster_asn_stats (
    asn INTEGER NOT NULL,
    hoster TEXT NOT NULL,
    ema_ttff_ms REAL NOT NULL DEFAULT 2500.0,
    ok_count INTEGER NOT NULL DEFAULT 0,
    err_count INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (asn, hoster)
);

CREATE INDEX IF NOT EXISTS idx_hoster_asn_updated ON hoster_asn_stats(updated_at);
