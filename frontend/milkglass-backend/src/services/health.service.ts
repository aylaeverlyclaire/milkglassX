
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

  for (const row of results ?? []) {
    if (row.unique_reports <= 0) continue;

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

  
  statements.push(
    db
      .prepare(
        `UPDATE stream_sources
         SET fail_count = CASE WHEN fail_count > 0 THEN fail_count - 1 ELSE 0 END,
             cooldown_until = CASE WHEN cooldown_until <= ?1 THEN 0 ELSE cooldown_until END
         WHERE ((fail_count > 0) OR (cooldown_until > 0 AND cooldown_until <= ?1))
           AND id NOT IN (
             SELECT DISTINCT source_id
             FROM source_telemetry_events
             WHERE created_at >= ?2
               AND created_at <= ?3
               AND reason IN ('http_403', 'expired', 'not_found')
           )`
      )
      .bind(upperBoundSec, windowStartSec, upperBoundSec)
  );

  
  statements.push(
    db
      .prepare(`DELETE FROM source_telemetry_events WHERE created_at <= ?1`)
      .bind(upperBoundSec)
  );

  
  statements.push(
    db
      .prepare(
        `DELETE FROM user_sessions
         WHERE expires_at <= ?1
            OR (is_revoked = 1 AND rotated_at <= ?2)`
      )
      .bind(upperBoundSec, upperBoundSec - 86400)
  );

  if (statements.length > 0) {
    await db.batch(statements);
  }
}
