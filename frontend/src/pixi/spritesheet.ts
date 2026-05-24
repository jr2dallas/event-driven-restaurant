// src/pixi/spritesheet.ts
//
// Supports two common top-down spritesheet formats.
//
// ── FORMAT A : schwarnhild / standard itch.io (default) ──────────
//   32×32 px per frame, 4 walk + 4 idle frames per direction
//   Row 0 : walk down   cols 0-3 | idle down   cols 4-7
//   Row 1 : walk left   cols 0-3 | idle left   cols 4-7
//   Row 2 : walk right  cols 0-3 | idle right  cols 4-7
//   Row 3 : walk up     cols 0-3 | idle up     cols 4-7
//
// ── FORMAT B : LPC (Universal LPC Sprite Sheet Generator) ────────
//   64×64 px per frame, 9 walk frames per direction
//   Row 8  : walk down  (9 frames)
//   Row 9  : walk left  (9 frames)
//   Row 10 : walk right (9 frames)
//   Row 11 : walk up    (9 frames)
//   Idle frame = col 0 row 8 (standing, facing camera)
//
// To switch format, change SHEET_FORMAT below.

import * as PIXI from 'pixi.js';

export type SheetFormat = 'schwarnhild' | 'lpc';

// ── Change here to switch sprite pack ────────────────────────────
export const SHEET_FORMAT: SheetFormat = 'schwarnhild';

// ── Format parameters ─────────────────────────────────────────────
const FORMATS = {
    schwarnhild: {
        frameW:      32,
        frameH:      32,
        walkFrames:  4,
        idleFrames:  4,
        // [row, colOffset] per direction
        walkRows:    [0, 1, 2, 3] as const,
        idleColOffset: 4,           // idle starts at column 4
        idleRow:     (dir: number) => dir, // same row as walk
    },
    lpc: {
        frameW:      64,
        frameH:      64,
        walkFrames:  9,
        idleFrames:  1,
        walkRows:    [8, 9, 10, 11] as const,
        idleColOffset: 0,
        idleRow:     (_: number) => 8,
    },
} as const;

const FMT = FORMATS[SHEET_FORMAT];

export const ANIM_SPEED = 0.15; // PIXI.AnimatedSprite speed (frames/tick)

// Directions: index = [down, left, right, up]
const enum Dir { Down = 0, Left = 1, Right = 2, Up = 3 }

function extractFrames(
    tex: PIXI.Texture,
    row: number,
    count: number,
    colOffset = 0,
): PIXI.Texture[] {
    const src = tex.source;
    const { frameW, frameH } = FMT;
    return Array.from({ length: count }, (_, col) =>
        new PIXI.Texture({
            source: src,
            frame:  new PIXI.Rectangle(
                (colOffset + col) * frameW,
                row               * frameH,
                frameW,
                frameH,
            ),
        }),
    );
}

export interface WalkTextures {
    down:  PIXI.Texture[];
    left:  PIXI.Texture[];
    right: PIXI.Texture[];
    up:    PIXI.Texture[];
    idle:  PIXI.Texture;
}

export function buildWalkTextures(sheet: PIXI.Texture): WalkTextures {
    const { walkRows, walkFrames, idleColOffset, idleRow } = FMT;
    return {
        down:  extractFrames(sheet, walkRows[Dir.Down],  walkFrames),
        left:  extractFrames(sheet, walkRows[Dir.Left],  walkFrames),
        right: extractFrames(sheet, walkRows[Dir.Right], walkFrames),
        up:    extractFrames(sheet, walkRows[Dir.Up],    walkFrames),
        idle:  extractFrames(sheet, idleRow(Dir.Down), 1, idleColOffset)[0],
    };
}

/**
 * Loads a spritesheet from /public and returns WalkTextures.
 *
 * schwarnhild → place in public/sprites/client_sheet.png
 *               (one file per character variant for visual variety)
 * LPC         → export from https://liberatedpixelcup.github.io/Universal-LPC-Spritesheet-Character-Generator/
 *               and place in public/sprites/client_sheet.png
 */
export async function loadCharacterSheet(url: string): Promise<WalkTextures> {
    const base = await PIXI.Assets.load<PIXI.Texture>(url);
    return buildWalkTextures(base);
}

/**
 * Loads multiple sheets (variants) and returns an array of WalkTextures.
 * ClientSprite picks a variant based on a hash of its ID.
 */
export async function loadCharacterVariants(urls: string[]): Promise<WalkTextures[]> {
    return Promise.all(urls.map(loadCharacterSheet));
}
