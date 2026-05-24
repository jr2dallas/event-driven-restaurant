import layoutData from '../config/restaurant-layout.json';

export interface Position { x: number; y: number; }

// ── Chair map ─────────────────────────────────────────────────
const seatMap = new Map<string, Position>();
const chairs = (layoutData as any).chair ?? [];
for (const chair of chairs) {
  seatMap.set(chair.id, { x: chair.x, y: chair.y });
}

export function resolveSeatById(seatId: string): Position | null {
  const pos = seatMap.get(seatId);
  if (!pos) {
    console.warn(`[resolveSeats] Siège inconnu: ${seatId}`, [...seatMap.keys()]);
    return null;
  }
  return pos;
}

// ── Chair → nearest table map (computed once at load) ────────
const tables: Array<{ id: string; x: number; y: number; r: number }> =
  (layoutData as any).table ?? [];

const chairTableMap = new Map<string, Position>(); // chairId → table center
for (const chair of chairs) {
  let nearest: { x: number; y: number } | null = null;
  let minDist = Infinity;
  for (const table of tables) {
    const dx = chair.x - table.x;
    const dy = chair.y - table.y;
    const d  = Math.sqrt(dx * dx + dy * dy);
    if (d < minDist) { minDist = d; nearest = table; }
  }
  if (nearest) chairTableMap.set(chair.id, { x: nearest.x, y: nearest.y });
}

// Distance (px) from chair center where the waiter stands.
// chair_r=11 + ~10px gap + waiter_half=12 → 33px minimum;
// using 40px gives comfortable visual breathing room for real sprites.
const SERVICE_OFFSET = 40;

/**
 * Returns the position the waiter should stand at when serving a client:
 * behind the chair (away from the table), in the aisle.
 * Falls back to the chair position itself if the table cannot be found.
 */
export function resolveWaiterServicePosition(seatId: string): Position | null {
  const chair = seatMap.get(seatId);
  if (!chair) return null;

  const table = chairTableMap.get(seatId);
  if (!table) return chair;

  const dx  = chair.x - table.x;
  const dy  = chair.y - table.y;
  const len = Math.sqrt(dx * dx + dy * dy) || 1;

  return {
    x: chair.x + (dx / len) * SERVICE_OFFSET,
    y: chair.y + (dy / len) * SERVICE_OFFSET,
  };
}

export function resolveTableCenterForSeat(seatId: string): Position | null {
  return chairTableMap.get(seatId) ?? null;
}

/** @deprecated use resolveWaiterServicePosition for waiters */
export function resolveTableCenterBySeatId(seatId: string): Position | null {
  return resolveWaiterServicePosition(seatId);
}
