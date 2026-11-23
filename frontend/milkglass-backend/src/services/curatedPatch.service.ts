import curatedPatch from '../../data/curated_catalog_patch.json';

const META_KEY = 'curated_patch_version';
let appliedInIsolateVersion = 0;

export async function ensureCuratedCatalogPatchApplied(db: D1Database): Promise<void> {
  const targetVersion = Number(curatedPatch?.version ?? 0);
  if (!Number.isFinite(targetVersion) || targetVersion <= 0) return;
  if (appliedInIsolateVersion >= targetVersion) return;

  try {
    const row = await db
      .prepare(`SELECT value FROM catalog_meta WHERE key = ?1`)
      .bind(META_KEY)
      .first<{ value: string }>();
    const dbVersion = Number(row?.value ?? '0');
    if (Number.isFinite(dbVersion) && dbVersion >= targetVersion) {
      appliedInIsolateVersion = dbVersion;
      return;
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const stmts: D1PreparedStatement[] = [];
    for (const entry of curatedPatch.entries ?? []) {
      if (!entry || !Number.isInteger(entry.id) || entry.id <= 0) continue;
      const urlsJson = entry.source_urls ? JSON.stringify(entry.source_urls) : null;
      stmts.push(
        db
          .prepare(
            `UPDATE animes
             SET episodes_total = CASE WHEN ?1 > episodes_total THEN ?1 ELSE episodes_total END,
                 status = ?2,
                 type = COALESCE(NULLIF(?3, ''), type),
                 source_urls_json = COALESCE(?4, source_urls_json),
                 updated_at = ?5
             WHERE id = ?6`
          )
          .bind(
            entry.episodes_total ?? 0,
            entry.status ?? 2,
            entry.type ?? 'TV',
            urlsJson,
            nowSec,
            entry.id
          )
      );
    }

    stmts.push(
      db.prepare(
        `UPDATE animes
         SET episodes_total = 1, status = 2, updated_at = ?1
         WHERE type = 'MOVIE' AND episodes_total = 0`
      ).bind(nowSec)
    );

    stmts.push(
      db
        .prepare(
          `INSERT INTO catalog_meta (key, value, updated_at)
           VALUES (?1, ?2, ?3)
           ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
        )
        .bind(META_KEY, String(targetVersion), nowSec)
    );

    for (let i = 0; i < stmts.length; i += 80) {
      await db.batch(stmts.slice(i, i + 80));
    }
    appliedInIsolateVersion = targetVersion;
  } catch (err: unknown) {
    console.error('[curatedPatch] failed to apply', {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}
