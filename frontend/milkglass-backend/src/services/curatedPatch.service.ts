import p1 from '../../data/curated_catalog_patch_p1.json';
import p2 from '../../data/curated_catalog_patch_p2.json';
import p3 from '../../data/curated_catalog_patch_p3.json';
import p4 from '../../data/curated_catalog_patch_p4.json';
import p5 from '../../data/curated_catalog_patch_p5.json';
import p6 from '../../data/curated_catalog_patch_p6.json';
import p7 from '../../data/curated_catalog_patch_p7.json';
import excludedIdsRaw from '../../data/curated_excluded_ids.json';

const META_VERSION_KEY = 'curated_patch_version';
const META_CURSOR_KEY = 'curated_patch_cursor';
const META_TARGET_KEY = 'curated_patch_target';
const BATCH_SIZE = 50;
const MAX_ROWS_PER_PASS = 250;

export const MAX_ALLOWED_EPISODES = 500;

const BANNED_FRANCHISE_RE =
  /\b(?:detective[\s\-_]*conan|meitantei[\s\-_]*conan|case[\s\-_]*closed|naruto|boruto|one[\s\-_]*piece|1piece)\b/i;

export function isExcludedAnimeText(raw: unknown): boolean {
  if (typeof raw !== 'string' || raw.trim().length === 0) return false;
  return BANNED_FRANCHISE_RE.test(raw);
}

export function isExcludedAnimeRecord(row: {
  id?: unknown;
  title?: unknown;
  title_alt?: unknown;
  title_native?: unknown;
  episodes_total?: unknown;
  source_urls_json?: unknown;
  source_urls?: unknown;
}): boolean {
  const numId = Number(row.id ?? 0);
  if (Number.isInteger(numId) && numId > 0 && CURATED_EXCLUDED_IDS.has(numId)) {
    return true;
  }
  const eps = Number(row.episodes_total ?? 0);
  if (Number.isFinite(eps) && eps > MAX_ALLOWED_EPISODES) {
    return true;
  }
  if (
    isExcludedAnimeText(row.title) ||
    isExcludedAnimeText(row.title_alt) ||
    isExcludedAnimeText(row.title_native) ||
    isExcludedAnimeText(row.source_urls_json)
  ) {
    return true;
  }
  if (row.source_urls && typeof row.source_urls === 'object') {
    for (const v of Object.values(row.source_urls as Record<string, unknown>)) {
      if (isExcludedAnimeText(v)) return true;
    }
  }
  return false;
}

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
  u?: Record<string, string>;
}

export const CURATED_PATCH_VERSION = 8;

export const CURATED_EXCLUDED_IDS: ReadonlySet<number> = new Set(
  (excludedIdsRaw as number[]).filter((id) => Number.isInteger(id) && id > 0)
);

export const CURATED_EXCLUDED_IDS_SQL_LIST = Array.from(CURATED_EXCLUDED_IDS).join(',');

const rawRows: CompactPatchRow[] = [
  ...(p1 as CompactPatchRow[]),
  ...(p2 as CompactPatchRow[]),
  ...(p3 as CompactPatchRow[]),
  ...(p4 as CompactPatchRow[]),
  ...(p5 as CompactPatchRow[]),
  ...(p6 as CompactPatchRow[]),
  ...(p7 as CompactPatchRow[]),
];

const validEntries: CuratedPatchEntry[] = rawRows
  .filter(
    (r) =>
      Boolean(
        r &&
          Number.isInteger(r.id) &&
          r.id > 0 &&
          !CURATED_EXCLUDED_IDS.has(r.id) &&
          (r.e ?? 0) <= MAX_ALLOWED_EPISODES
      )
  )
  .map((r) => ({
    id: r.id,
    type: r.t || 'TV',
    status: Number.isInteger(r.s) ? Number(r.s) : 2,
    episodes_total: Number.isInteger(r.e) ? Number(r.e) : 0,
    ...(r.u && typeof r.u === 'object' ? { source_urls: r.u } : {}),
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
    // Pastikan pembersihan franchise terlarang & >500 episode selalu dieksekusi dalam 2 statement ringkas
    stmts.push(
      db
        .prepare(
          `UPDATE animes
           SET has_live_source = 0, updated_at = ?1
           WHERE has_live_source != 0
             AND (
               episodes_total > ?2
               OR lower(title) LIKE '%detective conan%'
               OR lower(title) LIKE '%meitantei conan%'
               OR lower(title) LIKE '%case closed%'
               OR lower(title) LIKE '%naruto%'
               OR lower(title) LIKE '%boruto%'
               OR lower(title) LIKE '%one piece%'
               OR COALESCE(lower(title_alt), '') LIKE '%detective conan%'
               OR COALESCE(lower(title_alt), '') LIKE '%meitantei conan%'
               OR COALESCE(lower(title_alt), '') LIKE '%case closed%'
               OR COALESCE(lower(title_alt), '') LIKE '%naruto%'
               OR COALESCE(lower(title_alt), '') LIKE '%boruto%'
               OR COALESCE(lower(title_alt), '') LIKE '%one piece%'
             )`
        )
        .bind(nowSec, MAX_ALLOWED_EPISODES)
    );
    if (CURATED_EXCLUDED_IDS_SQL_LIST.length > 0) {
      stmts.push(
        db
          .prepare(
            `UPDATE animes
             SET has_live_source = 0, updated_at = ?1
             WHERE has_live_source != 0 AND id IN (${CURATED_EXCLUDED_IDS_SQL_LIST})`
          )
          .bind(nowSec)
      );
    }

    for (const entry of slice) {
      if (entry.source_urls && Object.keys(entry.source_urls).length > 0) {
        const patchUrlsJson = JSON.stringify(entry.source_urls);
        stmts.push(
          db
            .prepare(
              `UPDATE animes
               SET episodes_total = MAX(episodes_total, ?1),
                   status = ?2,
                   type = COALESCE(NULLIF(?3, ''), type),
                   source_urls_json = CASE
                     WHEN source_urls_json IS NOT NULL AND json_valid(source_urls_json)
                     THEN json_patch(source_urls_json, ?4)
                     ELSE ?4
                   END,
                   updated_at = ?5
               WHERE id = ?6`
            )
            .bind(
              entry.episodes_total ?? 0,
              entry.status ?? 2,
              entry.type ?? 'TV',
              patchUrlsJson,
              nowSec,
              entry.id
            )
        );
      } else {
        stmts.push(
          db
            .prepare(
              `UPDATE animes
               SET episodes_total = MAX(episodes_total, ?1),
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
