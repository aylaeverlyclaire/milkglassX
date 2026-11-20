const BATCH_CHUNK_SIZE = 50;

export async function aggregateSourceHealthCron(db: D1Database): Promise<void> {
  const upperBoundSec = Math.floor(Date.now() / 1000);
  const windowStartSec = upperBoundSec - 900;
  const cooldownDurationSec = 7200;
  const cooldownThreshold = 5;

  const { results } = await db
    .prepare(
      `SELECT source_id, COUNT(DISTINCT ip_hash) AS unique_reports
       FROM source_telemetry_events
       WHERE created_at >= ?1
         AND created_at <= ?2
         AND reason IN ('http_403', 'expired', 'not_found')
       GROUP BY source_id`
    )
    .bind(windowStartSec, upperBoundSec)
    .all<{ source_id: number; unique_reports: number }>();

  const statements: D1PreparedStatement[] = [];
  const failingIds: number[] = [];

  for (const row of results ?? []) {
    if (row.unique_reports <= 0) continue;
    failingIds.push(row.source_id);

    const targetCooldown =
      row.unique_reports >= cooldownThreshold ? upperBoundSec + cooldownDurationSec : 0;

    statements.push(
      db
        .prepare(
          `UPDATE stream_sources
           SET fail_count = fail_count + ?1,
               cooldown_until = CASE WHEN ?2 > cooldown_until THEN ?2 ELSE cooldown_until END
           WHERE id = ?3`
        )
        .bind(row.unique_reports, targetCooldown, row.source_id)
    );
  }

  if (failingIds.length === 0) {
    statements.push(
      db
        .prepare(
          `UPDATE stream_sources
           SET fail_count = CASE WHEN fail_count > 0 THEN fail_count - 1 ELSE 0 END,
               cooldown_until = CASE WHEN cooldown_until <= ?1 THEN 0 ELSE cooldown_until END
           WHERE (fail_count > 0) OR (cooldown_until > 0 AND cooldown_until <= ?1)`
        )
        .bind(upperBoundSec)
    );
  } else {
    const placeholders = failingIds.map((_, idx) => `?${idx + 2}`).join(',');
    statements.push(
      db
        .prepare(
          `UPDATE stream_sources
           SET fail_count = CASE WHEN fail_count > 0 THEN fail_count - 1 ELSE 0 END,
               cooldown_until = CASE WHEN cooldown_until <= ?1 THEN 0 ELSE cooldown_until END
           WHERE ((fail_count > 0) OR (cooldown_until > 0 AND cooldown_until <= ?1))
             AND id NOT IN (${placeholders})`
        )
        .bind(upperBoundSec, ...failingIds)
    );
  }

  statements.push(
    db
      .prepare(`DELETE FROM source_telemetry_events WHERE created_at <= ?1`)
      .bind(upperBoundSec)
  );

  // Split user_sessions cleanup so SQLite uses idx_sessions_expires and idx_sessions_revoked_rot independently.
  statements.push(
    db
      .prepare(`DELETE FROM user_sessions WHERE expires_at <= ?1`)
      .bind(upperBoundSec)
  );
  statements.push(
    db
      .prepare(`DELETE FROM user_sessions WHERE is_revoked = 1 AND rotated_at <= ?1`)
      .bind(upperBoundSec - 86400)
  );

  for (let i = 0; i < statements.length; i += BATCH_CHUNK_SIZE) {
    await db.batch(statements.slice(i, i + BATCH_CHUNK_SIZE));
  }
  try {
    await db.batch([
      db.prepare(`DELETE FROM play_direct WHERE updated_at < ?1`).bind(upperBoundSec - 7200),
      db.prepare(`DELETE FROM play_hints WHERE updated_at < ?1`).bind(upperBoundSec - 86400),
      db.prepare(`DELETE FROM hoster_asn_stats WHERE updated_at < ?1`).bind(upperBoundSec - 14 * 86400),
    ]);
  } catch {
  }
}
