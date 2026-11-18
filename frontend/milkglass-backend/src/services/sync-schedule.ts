
const SYNC_SLOTS_MIN_UTC = [17, 227, 377, 737, 1097];

const SYNC_GRACE_SEC = 5 * 60;
const SYNC_OVERDUE_AFTER_SEC = 45 * 60;
const RECHECK_MIN_SEC = 60;
const RECHECK_MAX_SEC = 6 * 3600;

export function surroundingSyncSlots(nowSec: number): { last: number; next: number } {
  const dayStart = nowSec - (nowSec % 86400);
  const slots: number[] = [];
  for (const dayOffset of [-1, 0, 1]) {
    for (const m of SYNC_SLOTS_MIN_UTC) slots.push(dayStart + dayOffset * 86400 + m * 60);
  }
  slots.sort((a, b) => a - b);
  let last = slots[0];
  let next = slots[slots.length - 1];
  for (const t of slots) {
    if (t <= nowSec) last = t;
    else { next = t; break; }
  }
  return { last, next };
}

export function recheckAfterSec(nowSec: number, syncedAt: number | null): number {
  const clamp = (n: number) => Math.min(RECHECK_MAX_SEC, Math.max(RECHECK_MIN_SEC, Math.round(n)));
  if (syncedAt === null) return clamp(300); 
  if (nowSec - syncedAt < 180) return clamp(240); 
  const { last, next } = surroundingSyncSlots(nowSec);
  if (syncedAt >= last) return clamp(next + SYNC_GRACE_SEC - nowSec); 
  
  return clamp(nowSec - last < SYNC_OVERDUE_AFTER_SEC ? 600 : 1800);
}
