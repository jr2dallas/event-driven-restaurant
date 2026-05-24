// src/pathfinding/buildNavGrid.ts
// Génère la grille de navigation 0/1 depuis le layout JSON
// 0 = libre, 1 = bloqué — utilisé par EasyStar.js

import layoutData from '../config/restaurant-layout.json';

export const TILE_SIZE = 8; // px par case

// Types qui ne bloquent PAS (points de passage)
const PASSABLE_TYPES = new Set(['entrance', 'queue_start', 'queue_end']);

// Padding (px) autour de chaque type d'obstacle
const PADDING: Record<string, number> = {
  table:           6,
  chair:           4,
  kitchen_station: 8,
  bar:             8,
  bar_stool:       3,
  obstacle:        6,
  plant:           4,
};

// ─── Types ────────────────────────────────────────────────────
export interface LayoutElement {
  id: string;
  x: number;
  y: number;
  r: number;
}

export interface NavGrid {
  grid:      number[][];
  gridW:     number;
  gridH:     number;
  tileSize:  number;
  /** Convertit des coordonnées pixel → tile */
  toTile:    (px: number, py: number) => { tx: number; ty: number };
  /** Convertit des coordonnées tile → pixel (centre de la case) */
  toPixel:   (tx: number, ty: number) => { px: number; py: number };
  /** Vérifie si une case tile est libre */
  isFree:    (tx: number, ty: number) => boolean;
}

// ─── Helpers ──────────────────────────────────────────────────
function blockCircle(
  grid: number[][],
  GW: number, GH: number,
  cx: number, cy: number,
  r: number, padding: number,
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
  grid: number[][],
  GW: number, GH: number,
  x1: number, y1: number,
  x2: number, y2: number,
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
export function buildNavGrid(
  imageW: number,
  imageH: number,
): NavGrid {
  const GW = Math.floor(imageW / TILE_SIZE);
  const GH = Math.floor(imageH / TILE_SIZE);

  // Init grille libre
  const grid: number[][] = Array.from({ length: GH }, () => new Array(GW).fill(0));

  // Murs extérieurs
  for (let tx = 0; tx < GW; tx++) { grid[0][tx] = 1; grid[GH-1][tx] = 1; }
  for (let ty = 0; ty < GH; ty++) { grid[ty][0] = 1; grid[ty][GW-1] = 1; }

  // Zone cuisine (obstacle rectangulaire depuis les zones du layout)
  const zones = (layoutData as any).zones;
  if (zones?.kitchen) {
    const k = zones.kitchen;
    blockRect(grid, GW, GH, k.x, k.y, k.x + k.w, k.y + k.h, 0);
  }
  if (zones?.queue) {
    const q = zones.queue;
    // La zone queue est libre pour les clients — ne pas bloquer
    // mais on bloque le mur séparant rue/restaurant (bord droit de la queue)
    for (let ty = 0; ty < GH; ty++) {
      const tx = Math.floor((q.x + q.w) / TILE_SIZE);
      grid[ty][tx] = 1;
    }
  }

  // Éléments du layout
  const layout = layoutData as Record<string, LayoutElement[]>;
  for (const [typeKey, elements] of Object.entries(layout)) {
    if (!Array.isArray(elements)) continue;
    if (PASSABLE_TYPES.has(typeKey)) continue;

    const pad = PADDING[typeKey] ?? 4;
    for (const el of elements) {
      blockCircle(grid, GW, GH, el.x, el.y, el.r ?? 20, pad);
    }
  }

  // ── API du NavGrid ─────────────────────────────────────────
  return {
    grid,
    gridW:    GW,
    gridH:    GH,
    tileSize: TILE_SIZE,

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
      return grid[ty][tx] === 0;
    },
  };
}

// ─── Intégration EasyStar ─────────────────────────────────────
// Usage dans ton composant PixiJS :
//
// import EasyStar from 'easystarjs';
// import { buildNavGrid } from './pathfinding/buildNavGrid';
//
// const nav = buildNavGrid(2048, 1536);
// const easystar = new EasyStar.js();
// easystar.setGrid(nav.grid);
// easystar.setAcceptableTiles([0]);
// easystar.enableDiagonals();
// easystar.disableCornerCutting();  // évite de couper les coins d'obstacles
//
// function moveTo(sprite, targetPx, targetPy) {
//   const start = nav.toTile(sprite.x, sprite.y);
//   const end   = nav.toTile(targetPx, targetPy);
//   easystar.findPath(start.tx, start.ty, end.tx, end.ty, (path) => {
//     if (!path) return; // pas de chemin trouvé
//     const pixelPath = path.map(p => nav.toPixel(p.x, p.y));
//     sprite.followPath(pixelPath); // ta logique d'animation
//   });
//   easystar.calculate();
// }
