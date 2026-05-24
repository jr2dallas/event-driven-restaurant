// src/pathfinding/buildNavGrid.ts
import layoutData from '../config/restaurant-layout.json';

export const TILE_SIZE = 8;

// Chairs = reachable but costly destinations (value 2, not 0)
// A* avoids them if possible; nearestFree accepts them.
export const CHAIR_TILE = 2;

const PASSABLE_TYPES = new Set([
  'entrance', 'queue_start', 'queue_end',
  // 'chair' is handled separately below — marked CHAIR_TILE, not blocked
]);

const PADDING: Record<string, number> = {
  table:           6,
  kitchen_station: 8,
  obstacle:        6,
  plant:           4,
};

// ─── Types ────────────────────────────────────────────────────
export interface LayoutElement {
  id: string;
  x:  number;
  y:  number;
  r:  number;
}

export interface NavGrid {
  grid:        number[][];
  gridW:       number;
  gridH:       number;
  tileSize:    number;
  chairTiles:  Array<{ tx: number; ty: number }>; // for easystar per-tile cost
  toTile:      (px: number, py: number) => { tx: number; ty: number };
  toPixel:     (tx: number, ty: number) => { px: number; py: number };
  isFree:      (tx: number, ty: number) => boolean;
  nearestFree: (px: number, py: number) => { px: number; py: number };
}

// ─── Helpers ──────────────────────────────────────────────────
function blockCircle(
    grid: number[][], GW: number, GH: number,
    cx: number, cy: number, r: number, padding: number,
): void {
  const tr  = Math.ceil((r + padding) / TILE_SIZE);
  const tcx = Math.floor(cx / TILE_SIZE);
  const tcy = Math.floor(cy / TILE_SIZE);
  for (let dy = -tr; dy <= tr; dy++) {
    for (let dx = -tr; dx <= tr; dx++) {
      if (dx * dx + dy * dy <= tr * tr) {
        const tx = tcx + dx;
        const ty = tcy + dy;
        if (tx >= 0 && tx < GW && ty >= 0 && ty < GH) {
          grid[ty][tx] = 1;
        }
      }
    }
  }
}

function blockRect(
    grid: number[][], GW: number, GH: number,
    x1: number, y1: number, x2: number, y2: number,
    padding = 6,
): void {
  const tx1 = Math.max(0,    Math.floor((x1 - padding) / TILE_SIZE));
  const ty1 = Math.max(0,    Math.floor((y1 - padding) / TILE_SIZE));
  const tx2 = Math.min(GW-1, Math.floor((x2 + padding) / TILE_SIZE));
  const ty2 = Math.min(GH-1, Math.floor((y2 + padding) / TILE_SIZE));
  for (let ty = ty1; ty <= ty2; ty++)
    for (let tx = tx1; tx <= tx2; tx++)
      grid[ty][tx] = 1;
}

// ─── Export principal ─────────────────────────────────────────
export function buildNavGrid(imageW: number, imageH: number): NavGrid {
  const GW = Math.floor(imageW / TILE_SIZE);
  const GH = Math.floor(imageH / TILE_SIZE);
  const grid: number[][] = Array.from({ length: GH }, () => new Array(GW).fill(0));

  // Outer walls
  for (let tx = 0; tx < GW; tx++) { grid[0][tx] = 1; grid[GH-1][tx] = 1; }
  for (let ty = 0; ty < GH; ty++) { grid[ty][0] = 1; grid[ty][GW-1] = 1; }

  const zones = (layoutData as any).zones;

  // Kitchen — solid rectangular obstacle
  if (zones?.kitchen) {
    const k = zones.kitchen;
    blockRect(grid, GW, GH, k.x, k.y, k.x + k.w, k.y + k.h, 0);
  }

  // Street/restaurant dividing wall — with a gap at the entrance
  if (zones?.queue) {
    const q = zones.queue;
    const wallTileX = Math.floor((q.x + q.w) / TILE_SIZE);

    // Entrance pixel coordinates (from layout or fall-back constants)
    const entrance = (layoutData as any).entrance;
    const entranceY1 = entrance?.y         ?? 680;
    const entranceY2 = entranceY1 + (entrance?.h ?? 180);
    const entTy1 = Math.floor(entranceY1 / TILE_SIZE);
    const entTy2 = Math.ceil (entranceY2 / TILE_SIZE);

    for (let ty = 0; ty < GH; ty++) {
      // Leave the entrance gap open
      if (ty >= entTy1 && ty <= entTy2) continue;
      if (wallTileX >= 0 && wallTileX < GW) grid[ty][wallTileX] = 1;
    }
  }

  // Layout elements (tables, obstacles, plants...)
  const layout = layoutData as Record<string, LayoutElement[]>;
  for (const [typeKey, elements] of Object.entries(layout)) {
    if (!Array.isArray(elements))          continue;
    if (PASSABLE_TYPES.has(typeKey))       continue;
    if (typeKey === 'chair')               continue; // handled below
    const pad = PADDING[typeKey] ?? 4;
    for (const el of elements) {
      blockCircle(grid, GW, GH, el.x, el.y, el.r ?? 20, pad);
    }
  }

  // Chairs: passable but marked CHAIR_TILE so A* routes around them
  const chairTiles: Array<{ tx: number; ty: number }> = [];
  for (const chair of ((layoutData as any).chair ?? []) as LayoutElement[]) {
    const tr  = Math.ceil(chair.r / TILE_SIZE);
    const tcx = Math.floor(chair.x / TILE_SIZE);
    const tcy = Math.floor(chair.y / TILE_SIZE);
    for (let dy = -tr; dy <= tr; dy++) {
      for (let dx = -tr; dx <= tr; dx++) {
        if (dx * dx + dy * dy <= tr * tr) {
          const tx = tcx + dx, ty = tcy + dy;
          if (tx >= 0 && tx < GW && ty >= 0 && ty < GH && grid[ty][tx] === 0) {
            grid[ty][tx] = CHAIR_TILE;
            chairTiles.push({ tx, ty });
          }
        }
      }
    }
  }

  // ── API NavGrid ────────────────────────────────────────────
  return {
    grid,
    gridW:      GW,
    gridH:      GH,
    tileSize:   TILE_SIZE,
    chairTiles,

    toTile(px, py) {
      return {
        tx: Math.max(0, Math.min(GW - 1, Math.floor(px / TILE_SIZE))),
        ty: Math.max(0, Math.min(GH - 1, Math.floor(py / TILE_SIZE))),
      };
    },

    toPixel(tx, ty) {
      return {
        px: tx * TILE_SIZE + TILE_SIZE / 2,
        py: ty * TILE_SIZE + TILE_SIZE / 2,
      };
    },

    isFree(tx, ty) {
      if (tx < 0 || ty < 0 || tx >= GW || ty >= GH) return false;
      return grid[ty][tx] !== 1; // 0 = free, CHAIR_TILE = costly-but-passable
    },

    nearestFree(px, py) {
      const startTx = Math.max(0, Math.min(GW - 1, Math.floor(px / TILE_SIZE)));
      const startTy = Math.max(0, Math.min(GH - 1, Math.floor(py / TILE_SIZE)));
      if (grid[startTy][startTx] !== 1) return { px, py };

      const visited = new Set<number>();
      const queue: [number, number][] = [[startTx, startTy]];
      const dirs = [[-1,0],[1,0],[0,-1],[0,1]] as const;

      while (queue.length > 0) {
        const [tx, ty] = queue.shift()!;
        const key = ty * GW + tx;
        if (visited.has(key)) continue;
        visited.add(key);
        if (grid[ty][tx] !== 1) {
          return { px: tx * TILE_SIZE + TILE_SIZE / 2, py: ty * TILE_SIZE + TILE_SIZE / 2 };
        }
        for (const [dx, dy] of dirs) {
          const ntx = tx + dx, nty = ty + dy;
          if (ntx >= 0 && ntx < GW && nty >= 0 && nty < GH)
            queue.push([ntx, nty]);
        }
      }
      return { px, py };
    },
  };
}
