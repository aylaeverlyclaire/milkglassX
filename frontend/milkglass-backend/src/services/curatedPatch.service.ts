import curatedPatch from '../../data/curated_catalog_patch.json';

const META_VERSION_KEY = 'curated_patch_version';
const META_CURSOR_KEY = 'curated_patch_cursor';
const META_TARGET_KEY = 'curated_patch_target';
const BATCH_SIZE = 100;
const MAX_ROWS_PER_PASS = 1000;

let appliedInIsolateVersion = 0;

export interface CuratedPatchEntry {
  id: number;
  title: string;
  type: string;
  status: number;
  episodes_total: number;
  source_urls?: Record<string, string>;
}

const rawEntries = (curatedPatch.entries ?? []) as unknown as CuratedPatchEntry[];
export const CURATED_PATCH_VERSION: number = Number(curatedPatch?.version ?? 0) || 0;

const validEntries: CuratedPatchEntry[] = rawEntries.filter((e) =>
  Boolean(e && Number.isInteger(e.id) && e.id > 0)
);

export const CURATED_PATCH_MAP: ReadonlyMap<number, CuratedPatchEntry> = new Map(
  validEntries.map((e) => [e.id, e])
);

export async function ensureCuratedCatalogPatchApplied(db: D1Database): Promise<void> {
  const targetVersion = Number(curatedPatch?.version ?? 0);
  if (!Number.isFinite(targetVersion) || targetVersion <= 0) return;
  if (appliedInIsolateVersion >= targetVersion) return;

  try {
    const metaRows = await db
      .prepare(
        `SELECT key, value FROM catalog_meta WHERE key IN (?1, ?2, ?3)`
      )
      .bind(META_VERSION_KEY, META_CURSOR_KEY, META_TARGET_KEY)
      .all<{ key: string; value: string }>();

    const meta = new Map<string, string>();
    for (const r of metaRows.results ?? []) {
      meta.set(r.key, r.value);
    }

    const dbVersion = Number(meta.get(META_VERSION_KEY) ?? '0');
    if (Number.isFinite(dbVersion) && dbVersion >= targetVersion) {
      appliedInIsolateVersion = dbVersion;
      return;
    }

    const activeTarget = Number(meta.get(META_TARGET_KEY) ?? '0');
    let cursor = activeTarget === targetVersion ? Number(meta.get(META_CURSOR_KEY) ?? '0') : 0;
    if (!Number.isFinite(cursor) || cursor < 0) cursor = 0;

    const end = Math.min(cursor + MAX_ROWS_PER_PASS, validEntries.length);
    const slice = validEntries.slice(cursor, end);
    const nowSec = Math.floor(Date.now() / 1000);

    const stmts: D1PreparedStatement[] = [];
    for (const entry of slice) {
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

    const upsertMeta = (key: string, value: string) =>
      db
        .prepare(
          `INSERT INTO catalog_meta (key, value, updated_at)
           VALUES (?1, ?2, ?3)
           ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
        )
        .bind(key, value, nowSec);

    if (end >= validEntries.length) {
      stmts.push(upsertMeta(META_VERSION_KEY, String(targetVersion)));
      stmts.push(upsertMeta(META_TARGET_KEY, String(targetVersion)));
      stmts.push(upsertMeta(META_CURSOR_KEY, '0'));
    } else {
      stmts.push(upsertMeta(META_TARGET_KEY, String(targetVersion)));
      stmts.push(upsertMeta(META_CURSOR_KEY, String(end)));
    }

    for (let i = 0; i < stmts.length; i += BATCH_SIZE) {
      await db.batch(stmts.slice(i, i + BATCH_SIZE));
    }

    if (end >= validEntries.length) {
      appliedInIsolateVersion = targetVersion;
    }
  } catch (err: unknown) {
    console.error('[curatedPatch] failed to apply', {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}
