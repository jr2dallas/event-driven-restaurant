// src/pixi/ClientSprite.ts
import * as PIXI from 'pixi.js';
import EasyStar from 'easystarjs';
import { AT_DOOR, ENTERING } from '../store/clientSpritesStore';
import type { ClientSpriteData, ClientState, Position } from '../store/clientSpritesStore';
import type { NavGrid } from '../pathfinding/buildNavGrid';
import type { WalkTextures } from './spritesheet';
import { ANIM_SPEED } from './spritesheet';
import { SessionState } from '../api/generated/model/session-state';
import { hashId } from '../utils/hashId';
import { resolveTableCenterForSeat } from '../pathfinding/resolveSeatsFromLayout';

// ── Layout constants ───────────────────────────────────────────
const ENTRANCE_X   = 288;
const ENTRANCE_Y   = 450;
const QUEUE_LINE_X = 198;
const DOOR_X       = 262;
const DOOR_Y       = 450;
const INSIDE_X     = 312;
const INSIDE_Y     = 450;
const WALK_SPEED   = 120;

// ── Palette (mode Graphics) ────────────────────────────────────
const SKIN         = 0xfcd5a0;
const SKIN_STROKE  = 0xd4954a;

const CLOTHES_PALETTE: number[] = [
    0x3b82f6, 0xef4444, 0x22c55e, 0xf59e0b,
    0x8b5cf6, 0xec4899, 0x06b6d4, 0xf97316,
];

// TFC palette — purple gradient (official Toulouse FC colours)
const TFC_PALETTE: number[] = [
    0x5B21B6, 0x6D28D9, 0x7C3AED, 0x4C1D95, 0x8B5CF6,
];
const HAIR_PALETTE: number[] = [
    0x3d2b1f, 0xc89b3c, 0x8b4513, 0x111111, 0xd97706,
];

const MENU_FILL    = 0xfef3c7;
const MENU_STROKE  = 0xd97706;
const PLATE_FILL   = 0xf3f4f6;
const PLATE_STROKE = 0xd1d5db;
const FOOD_COLOR   = 0xf97316;
const FORK_COLOR   = 0x9ca3af;


function drawMenu(g: PIXI.Graphics) {
    g.clear();
    g.roundRect(-4, -7, 8, 14, 1)
     .fill({ color: MENU_FILL })
     .stroke({ color: MENU_STROKE, width: 1 });
    g.moveTo(0, -7).lineTo(0, 7);
    g.stroke({ color: MENU_STROKE, alpha: 0.5, width: 0.8 });
    g.moveTo(-3, -3).lineTo(-0.5, -3);
    g.moveTo(-3, -1).lineTo(-0.5, -1);
    g.moveTo(-3,  1).lineTo(-0.5,  1);
    g.stroke({ color: MENU_STROKE, alpha: 0.4, width: 0.6 });
}

function drawPlateWithFork(g: PIXI.Graphics) {
    g.clear();
    g.circle(2, 1, 8)
     .fill({ color: PLATE_FILL })
     .stroke({ color: PLATE_STROKE, width: 1.5 });
    g.circle(2, 1, 6)
     .stroke({ color: PLATE_STROKE, alpha: 0.6, width: 0.8 });
    g.circle(2, 1, 3)
     .fill({ color: FOOD_COLOR });
    g.moveTo(-8, -5).lineTo(-8, 6);
    g.stroke({ color: FORK_COLOR, width: 1.5 });
    g.moveTo(-10, -5).lineTo(-10, -1);
    g.moveTo(-8,  -5).lineTo(-8,  -1);
    g.moveTo(-6,  -5).lineTo(-6,  -1);
    g.stroke({ color: FORK_COLOR, width: 0.8 });
}

// ── Per-state sprite tints ────────────────────────────────────
// (sprite mode only — reduce opacity if white tint is unwanted)
const STATE_TINTS: Partial<Record<ClientState, number>> = {
    [SessionState.Queuing]:       0xffffff,
    [AT_DOOR]:                    0xffffff,
    [ENTERING]:                   0xffffff,
    [SessionState.WalkingToSeat]: 0xffffff,
    [SessionState.Seated]:        0xffffff,
    [SessionState.WaitingOrder]:  0xfffbe6, // slightly warm
    [SessionState.Eating]:        0xfff5e4,
    [SessionState.Leaving]:       0xe0e0e0, // slightly greyed
};

// ── Sprite principal ──────────────────────────────────────────
export class ClientSprite {
    /** Position container — lu par SceneManager */
    public  container: PIXI.Container;
    /** Inner container visuel — reçoit le bob de marche */
    private inner:     PIXI.Container;

    // Mode Graphics (fallback sans spritesheet)
    private hair:  PIXI.Graphics | null = null;
    private head:  PIXI.Graphics | null = null;
    private body:  PIXI.Graphics | null = null;
    private item:  PIXI.Graphics | null = null;

    // Mode Sprite (avec spritesheet)
    private animSprite: PIXI.AnimatedSprite | null = null;
    private walkTextures: WalkTextures | null;

    path:             Position[] = [];
    pathIndex         = 0;
    private _onPathComplete?: () => void;
    private _bobAccum         = 0;
    private _lastDx           = 0; // last X direction (used to pick animation frame)
    private _lastDy           = 1; // last Y direction (default: facing down)

    public  data: ClientSpriteData;
    private nav:      NavGrid;
    private easystar: EasyStar.js;
    private onStateChange?: (id: string, state: ClientState) => void;

    private clothesColor: number;
    private hairColor:    number;

    constructor(
        data: ClientSpriteData,
        nav: NavGrid,
        easystar: EasyStar.js,
        onStateChange?: (id: string, state: ClientState) => void,
        walkTextures?: WalkTextures,
    ) {
        this.data          = data;
        this.nav           = nav;
        this.easystar      = easystar;
        this.onStateChange = onStateChange;
        this.walkTextures  = walkTextures ?? null;

        const h = hashId(data.id);
        if (data.tag === 'tfc') {
            this.clothesColor = TFC_PALETTE[h % TFC_PALETTE.length];
        } else {
            this.clothesColor = CLOTHES_PALETTE[h % CLOTHES_PALETTE.length];
        }
        this.hairColor = HAIR_PALETTE[(h >>> 3) % HAIR_PALETTE.length];

        this.container = new PIXI.Container();
        this.inner     = new PIXI.Container();
        this.container.addChild(this.inner);

        if (this.walkTextures) {
            this._buildSpriteMode();
        } else {
            this._buildGraphicsMode();
        }

        this.container.position.set(data.position.x, data.position.y);
        this.redraw();
        this.handleStateEnter(data.state);
        if (data.state === SessionState.Queuing) this.container.cacheAsTexture(true);
    }

    // ── Seated orientation ────────────────────────────────────

    /**
     * Returns the (unnormalised) direction from the seat toward the table centre,
     * or null if the information is unavailable.
     */
    private _seatedDirection(): { dx: number; dy: number } | null {
        if (!this.data.seatId || !this.data.targetSeat) return null;
        const table = resolveTableCenterForSeat(this.data.seatId);
        if (!table) return null;
        return {
            dx: table.x - this.data.targetSeat.x,
            dy: table.y - this.data.targetSeat.y,
        };
    }

    // ── Construction du mode Sprite ───────────────────────────

    private _buildSpriteMode() {
        const tex = this.walkTextures!;
        this.animSprite = new PIXI.AnimatedSprite(tex.down);
        this.animSprite.anchor.set(0.5, 0.75); // pied au centre du container
        this.animSprite.animationSpeed = ANIM_SPEED;
        this.animSprite.play();
        this.inner.addChild(this.animSprite);
    }

    // ── Construction du mode Graphics ────────────────────────

    private _buildGraphicsMode() {
        this.hair = new PIXI.Graphics();
        this.head = new PIXI.Graphics();
        this.body = new PIXI.Graphics();
        this.item = new PIXI.Graphics();
        this.item.position.set(14, 0);
        this.inner.addChild(this.item, this.body, this.hair, this.head);
    }

    // ── Crowd physics ─────────────────────────────────────────

    setCrowdPos(x: number, y: number) {
        this.container.position.set(x, y);
    }

    sync(next: ClientSpriteData) {
        const prevState = this.data.state;
        this.data = next;
        if (prevState !== next.state) {
            if (prevState === SessionState.Queuing) this.container.cacheAsTexture(false);
            this.redraw();
            this.handleStateEnter(next.state);
            if (next.state === SessionState.Queuing) this.container.cacheAsTexture(true);
        }
    }

    private isSeatedState(s: ClientState): boolean {
        return s === SessionState.Seated || s === SessionState.WaitingOrder || s === SessionState.Eating;
    }

    // ── Rendu visuel ──────────────────────────────────────────

    private redraw() {
        const s = this.data.state;

        if (this.walkTextures) {
            this._redrawSprite(s);
        } else {
            this._redrawGraphics(s);
        }
    }

    private _redrawSprite(s: ClientState) {
        if (!this.animSprite || !this.walkTextures) return;
        const tint = STATE_TINTS[s] ?? 0xffffff;
        this.animSprite.tint = tint;

        const seated = this.isSeatedState(s);
        if (seated) {
            // Frame statique assis
            this.animSprite.textures = [this.walkTextures.idle];
            this.animSprite.stop();
            this.animSprite.scale.set(1, 0.75); // slightly squashed to suggest a seated posture
            this.inner.scale.x = 1;
        } else {
            // Walk animation in the last known direction
            this._updateWalkAnimation();
            this.animSprite.scale.set(1, 1);
            if (s === SessionState.Leaving) this.animSprite.alpha = 0.7;
            else                 this.animSprite.alpha = 1;
        }
    }

    private _updateWalkAnimation() {
        if (!this.animSprite || !this.walkTextures) return;
        const { down, left, right, up } = this.walkTextures;
        const ax = Math.abs(this._lastDx);
        const ay = Math.abs(this._lastDy);

        let frames: PIXI.Texture[];
        if (ax > ay) {
            frames = this._lastDx > 0 ? right : left;
        } else {
            frames = this._lastDy > 0 ? down : up;
        }

        if (this.animSprite.textures !== frames) {
            this.animSprite.textures = frames;
            this.animSprite.play();
        }
    }

    private _redrawGraphics(s: ClientState) {
        const seated = this.isSeatedState(s);

        this.hair!.clear();
        this.hair!.circle(0, 0, 5).fill({ color: this.hairColor });
        this.hair!.position.set(0, seated ? -19 : -23);

        this.head!.clear();
        this.head!.circle(0, 0, 6)
            .fill({ color: SKIN })
            .stroke({ color: SKIN_STROKE, width: 1 });
        this.head!.position.set(0, seated ? -16 : -20);

        this.body!.clear();
        if (seated) {
            this.body!
                .roundRect(-7, -8, 14, 14, 3)
                .fill({ color: this.clothesColor, alpha: 0.9 })
                .stroke({ color: 0x374151, alpha: 0.3, width: 1 });
            this.body!
                .roundRect(-9, 6, 18, 4, 2)
                .fill({ color: this.clothesColor, alpha: 0.65 });
        } else {
            this.body!
                .roundRect(-7, -10, 14, 18, 3)
                .fill({ color: this.clothesColor, alpha: 0.9 })
                .stroke({ color: 0x374151, alpha: 0.3, width: 1 });
        }

        if (seated) {
            this.inner.scale.x = 1;

            // Position the item toward the table centre
            const ITEM_DIST = 22;
            const dir = this._seatedDirection();
            let ix = 14, iy = 0; // fallback si pas d'info table
            if (dir) {
                const len = Math.sqrt(dir.dx * dir.dx + dir.dy * dir.dy) || 1;
                ix = (dir.dx / len) * ITEM_DIST;
                iy = (dir.dy / len) * ITEM_DIST;
            }

            if (s === SessionState.Seated) {
                this.item!.position.set(ix, iy);
                this.item!.rotation = 0;
                drawMenu(this.item!);
                this.item!.visible = true;
            } else if (s === SessionState.Eating) {
                this.item!.position.set(ix, iy);
                this.item!.rotation = 0;
                drawPlateWithFork(this.item!);
                this.item!.visible = true;
            } else {
                this.item!.visible = false;
            }
        } else {
            this.item!.visible = false;
        }
    }

    // ── FSM ───────────────────────────────────────────────────

    private handleStateEnter(state: ClientState) {
        this.path      = [];
        this.pathIndex = 0;
        this._onPathComplete = undefined;

        switch (state) {
            case SessionState.Queuing:
                break;

            case AT_DOOR:
                this.walkStraightTo({ x: DOOR_X, y: DOOR_Y }, () => {
                    this.onStateChange?.(this.data.id, ENTERING);
                });
                break;

            case ENTERING:
                this.walkStraightTo({ x: INSIDE_X, y: INSIDE_Y }, () => {
                    this.onStateChange?.(this.data.id, SessionState.WalkingToSeat);
                });
                break;

            case SessionState.WalkingToSeat:
                if (this.data.targetSeat) {
                    this.walkAStar(this.data.targetSeat, () => {
                        this.onStateChange?.(this.data.id, SessionState.Seated);
                    });
                } else {
                    setTimeout(() => {
                        if (this.data.state === SessionState.WalkingToSeat)
                            this.handleStateEnter(SessionState.WalkingToSeat);
                    }, 150);
                }
                break;

            case SessionState.Leaving:
                this.walkAStar({ x: ENTRANCE_X, y: ENTRANCE_Y }, () => {
                    const exitY = this.data.spawnSide === 'top' ? -40 : 1560;
                    this.walkStraightTo({ x: QUEUE_LINE_X, y: exitY }, () => {
                        this.fadeOut(() => {
                            this.onStateChange?.(this.data.id, SessionState.Despawned);
                        });
                    });
                });
                break;

            case SessionState.Despawned:
                this.fadeOut(() => {
                    this.onStateChange?.(this.data.id, SessionState.Despawned);
                });
                break;

            default:
                break;
        }
    }

    // ── Pathfinding helpers ────────────────────────────────────

    private walkStraightTo(target: Position, onComplete?: () => void) {
        this.path      = [target];
        this.pathIndex = 0;
        this._onPathComplete = onComplete;
    }

    walkAStar(target: Position, onComplete?: () => void) {
        const free  = this.nav.nearestFree(target.x, target.y);
        const start = this.nav.toTile(this.container.x, this.container.y);
        const end   = this.nav.toTile(free.px, free.py);
        this.easystar.findPath(start.tx, start.ty, end.tx, end.ty, (path) => {
            if (!path || path.length === 0) {
                console.warn('[A*] no path → straight fallback', this.data.id.slice(-4));
                this.walkStraightTo(target, onComplete);
                return;
            }
            this.path      = path.map(p => { const r = this.nav.toPixel(p.x, p.y); return { x: r.px, y: r.py }; });
            this.pathIndex = 0;
            this._onPathComplete = onComplete;
        });
        this.easystar.calculate();
    }

    // ── Game loop ──────────────────────────────────────────────

    update(deltaMS: number) {
        if (this.data.state === SessionState.Queuing) return;

        const isMoving = this.path.length > 0 && this.pathIndex < this.path.length;

        // Bob de marche (mode Graphics) ou stop animation (mode Sprite)
        if (this.animSprite) {
            if (!isMoving && !this.isSeatedState(this.data.state)) {
                this.animSprite.stop();
                this.animSprite.currentFrame = 0;
            } else if (isMoving && !this.animSprite.playing) {
                this.animSprite.play();
            }
        } else {
            if (isMoving) {
                this._bobAccum += deltaMS;
                this.inner.y = Math.sin(this._bobAccum * 0.012) * 1.5;
            } else {
                this.inner.y *= 0.8;
                if (Math.abs(this.inner.y) < 0.05) { this.inner.y = 0; this._bobAccum = 0; }
            }
        }

        if (!isMoving) return;

        const target = this.path[this.pathIndex];
        const dx     = target.x - this.container.x;
        const dy     = target.y - this.container.y;
        const dist   = Math.sqrt(dx * dx + dy * dy);
        const step   = WALK_SPEED * (deltaMS / 1000);

        if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
            this._lastDx = dx;
            this._lastDy = dy;
            // Mode Graphics : flip horizontal
            if (!this.animSprite && Math.abs(dx) > 2)
                this.inner.scale.x = dx > 0 ? 1 : -1;
            // Mode Sprite : changer l'animation de direction
            if (this.animSprite && !this.isSeatedState(this.data.state))
                this._updateWalkAnimation();
        }

        if (dist <= step) {
            this.container.position.set(target.x, target.y);
            this.pathIndex++;
            if (this.pathIndex >= this.path.length) {
                this.path = [];
                const cb = this._onPathComplete;
                this._onPathComplete = undefined;
                cb?.();
            }
        } else {
            this.container.x += (dx / dist) * step;
            this.container.y += (dy / dist) * step;
        }
    }

    private fadeOut(onDone?: () => void) {
        let alpha = 1;
        const fade = () => {
            alpha -= 0.05;
            this.container.alpha = Math.max(0, alpha);
            if (alpha > 0) requestAnimationFrame(fade);
            else { this.container.visible = false; onDone?.(); }
        };
        requestAnimationFrame(fade);
    }

    destroy() {
        this.container.destroy({ children: true });
    }
}
