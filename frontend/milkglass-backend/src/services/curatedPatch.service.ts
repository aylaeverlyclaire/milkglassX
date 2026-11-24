import p1 from '../../data/curated_catalog_patch_p1.json';
import p2 from '../../data/curated_catalog_patch_p2.json';
import p3 from '../../data/curated_catalog_patch_p3.json';
import p4 from '../../data/curated_catalog_patch_p4.json';
import p5 from '../../data/curated_catalog_patch_p5.json';
import p6 from '../../data/curated_catalog_patch_p6.json';

const META_VERSION_KEY = 'curated_patch_version';
const META_CURSOR_KEY = 'curated_patch_cursor';
const META_TARGET_KEY = 'curated_patch_target';
const BATCH_SIZE = 50;
const MAX_ROWS_PER_PASS = 400;

let appliedInIsolateVersion = 0;

export interface CuratedPatchEntry {
  id: number;
  title?: string;
  type: string;
  status: number;
  episodes_total: number;
  source_urls?: Record<string, string>;
}

interface CompactPatchRow {
  id: number;
  t?: string;
  s?: number;
  e?: number;
}

export const CURATED_PATCH_VERSION = 5;

const rawRows: CompactPatchRow[] = [
  ...(p1 as CompactPatchRow[]),
  ...(p2 as CompactPatchRow[]),
  ...(p3 as CompactPatchRow[]),
  ...(p4 as CompactPatchRow[]),
  ...(p5 as CompactPatchRow[]),
  ...(p6 as CompactPatchRow[]),
];

const validEntries: CuratedPatchEntry[] = rawRows
  .filter((r) => Boolean(r && Number.isInteger(r.id) && r.id > 0))
  .map((r) => ({
    id: r.id,
    type: r.t || 'TV',
    status: Number.isInteger(r.s) ? Number(r.s) : 2,
    episodes_total: Number.isInteger(r.e) ? Number(r.e) : 0,
  }));

export const CURATED_PATCH_MAP: ReadonlyMap<number, CuratedPatchEntry> = new Map(
  validEntries.map((e) => [e.id, e])
);

export async function ensureCuratedCatalogPatchApplied(db: D1Database): Promise<void> {
  const targetVersion = CURATED_PATCH_VERSION;
  if (appliedInIsolateVersion >= targetVersion) return;

  try {
    const metaRows = await db
      .prepare(`SELECT key, value FROM catalog_meta WHERE key IN (?1, ?2, ?3)`)
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
      stmts.push(
        db
          .prepare(
            `UPDATE animes
             SET episodes_total = CASE WHEN ?1 > episodes_total THEN ?1 ELSE episodes_total END,
                 status = ?2,
                 type = COALESCE(NULLIF(?3, ''), type),
                 updated_at = ?4
             WHERE id = ?5
               AND (episodes_total < ?1 OR status != ?2 OR type != COALESCE(NULLIF(?3, ''), type))`
          )
          .bind(
            entry.episodes_total ?? 0,
            entry.status ?? 2,
            entry.type ?? 'TV',
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
