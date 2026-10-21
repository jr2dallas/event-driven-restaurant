// src/pixi/WaiterSprite.ts
import * as PIXI from 'pixi.js';
import EasyStar from 'easystarjs';
import type { NavGrid } from '../pathfinding/buildNavGrid';
import { WaiterState, type WaiterSpriteData } from '../store/waiterSpritesStore';
import type { Position } from '../store/clientSpritesStore';
import { notifyWaiterState } from '../api/animationCallbacks';

const WALK_SPEED = 100;
// Common pickup point where waiters collect dishes from the kitchen
const KITCHEN_POS: Position = { x: 920, y: 452 };
// Idle waiting slots — 3 columns × 3 kitchen aisles (between stations)
const IDLE_SLOTS: Position[] = [
    { x: 856, y: 290 }, { x: 888, y: 290 }, { x: 920, y: 290 },
    { x: 856, y: 452 }, { x: 888, y: 452 }, { x: 920, y: 452 },
    { x: 856, y: 605 }, { x: 888, y: 605 }, { x: 920, y: 605 },
];
// Taking orders: a base time plus one per client of the table (2.5 s for a single client)
const TAKING_ORDER_BASE_MS       = 1500;
const TAKING_ORDER_PER_CLIENT_MS = 1000;
// Closer than this to the pass = already there (no detour before delivering)
const AT_PASS_RADIUS = 40;

// ── Uniform colours ───────────────────────────────────────────
const UNIFORM_FILL   = 0xffffff;
const UNIFORM_STROKE = 0x9ca3af;
const SKIN           = 0xfcd5a0;
const SKIN_STROKE    = 0xd4954a;
const NOTEPAD_FILL   = 0xfefce8;
const NOTEPAD_STROKE = 0x9ca3af;
const PLATE_FILL     = 0xf3f4f6;
const PLATE_STROKE   = 0xd1d5db;
const FOOD_COLOR     = 0xf97316;


// ── Drawing helpers ───────────────────────────────────────────

function drawNotepad(g: PIXI.Graphics) {
    g.clear();
    // Notebook body
    g.roundRect(-5, -6, 10, 12, 1)
     .fill({ color: NOTEPAD_FILL })
     .stroke({ color: NOTEPAD_STROKE, width: 1 });
    // Writing lines
    g.moveTo(-3, -2).lineTo(3, -2);
    g.moveTo(-3,  0).lineTo(3,  0);
    g.moveTo(-3,  2).lineTo(3,  2);
    g.stroke({ color: 0xaaaaaa, width: 0.8 });
    // Spiral spine on the left
    g.moveTo(-5, -6).lineTo(-5, 6);
    g.stroke({ color: NOTEPAD_STROKE, width: 1.2 });
}

function drawPlate(g: PIXI.Graphics) {
    g.clear();
    // Plate (outer circle)
    g.circle(0, 0, 8)
     .fill({ color: PLATE_FILL })
     .stroke({ color: PLATE_STROKE, width: 1.5 });
    // Inner rim
    g.circle(0, 0, 6)
     .stroke({ color: PLATE_STROKE, alpha: 0.6, width: 0.8 });
    // Food (small coloured dot at centre)
    g.circle(0, 0, 3)
     .fill({ color: FOOD_COLOR });
}

/** One plate, or a tray holding one plate per client of the table. */
function drawTray(g: PIXI.Graphics, plates: number) {
    if (plates <= 1) { drawPlate(g); return; }
    g.clear();
    g.roundRect(-11, -9, 22, 18, 3)
     .fill({ color: 0x9ca3af, alpha: 0.9 })
     .stroke({ color: 0x6b7280, width: 1 });
    const spots = [[-5, -4], [5, -4], [-5, 4], [5, 4]];
    for (let i = 0; i < Math.min(plates, spots.length); i++) {
        const [cx, cy] = spots[i];
        g.circle(cx, cy, 4.5).fill({ color: PLATE_FILL }).stroke({ color: PLATE_STROKE, width: 1 });
        g.circle(cx, cy, 2).fill({ color: FOOD_COLOR });
    }
}

// ── Main sprite ───────────────────────────────────────────────
export class WaiterSprite {
    /** Position container used by SceneManager to read x/y */
    public container: PIXI.Container;

    /** Visual inner container — receives the walking bob */
    private inner:    PIXI.Container;

    private head:   PIXI.Graphics;
    private body:   PIXI.Graphics;
    private item:   PIXI.Graphics; // notepad or plate
    private badge:  PIXI.Graphics; // blinking dot (TAKING_ORDER)
    private label:  PIXI.Text;

    public data: WaiterSpriteData;

    private nav:      NavGrid;
    private easystar: EasyStar.js;

    path:    Position[] = [];
    pathIdx  = 0;
    private _onComplete?: () => void;

    private _pendingCallback = false;
    private _takingOrderRaf: number | null = null;
    private _bobAccum = 0;
    private _isWalking = false;

    // Prevents interrupting the kitchen walk visually: if the backend
    // transitions to DELIVERING while still en route, we buffer the state
    // and apply it only once the waiter physically reaches the kitchen.
    private _headingToKitchen  = false;
    private _pendingDelivery: WaiterSpriteData | null = null;
    // Fallback idle slot when the backend sends no idle position
    private _idlePos: Position;
    // Delivering: false while walking to the pass to collect the dishes
    private _carrying = false;

    constructor(data: WaiterSpriteData, nav: NavGrid, easystar: EasyStar.js, slotIndex: number) {
        this.data     = data;
        this.nav      = nav;
        this.easystar = easystar;
        this._idlePos = IDLE_SLOTS[slotIndex % IDLE_SLOTS.length];

        // ── Containers ──────────────────────────────────────
        this.container = new PIXI.Container();
        this.inner     = new PIXI.Container();
        this.container.addChild(this.inner);

        // ── Graphics ─────────────────────────────────────────
        this.body  = new PIXI.Graphics();
        this.head  = new PIXI.Graphics();
        this.item  = new PIXI.Graphics();
        this.badge = new PIXI.Graphics();
        this.label = new PIXI.Text({
            text:  `W${data.id.slice(-2)}`,
            style: { fontSize: 8, fill: 0x374151, align: 'center', fontWeight: 'bold' },
        });
        this.label.anchor.set(0.5, 0);
        this.label.position.set(0, 14);

        // item positioned to the right of the body
        this.item.position.set(14, 0);

        // badge positioned top right
        this.badge.position.set(10, -20);

        this.inner.addChild(this.item, this.body, this.head, this.badge, this.label);

        // ── Initial position ─────────────────────────────────
        const start = data.position ?? KITCHEN_POS;
        this.container.position.set(start.x, start.y);

        this.redraw();
        this.handleStateEnter(data.state);
    }

    sync(next: WaiterSpriteData) {
        const prevState    = this.data.state;
        const prevTargetId = this.data.targetSessionId;

        // Buffer DELIVERING while walking to the kitchen:
        // if the backend signals DELIVERING before we arrive, store the state
        // and apply it once we physically reach the kitchen.
        if (this._headingToKitchen && next.state === WaiterState.Delivering) {
            this._pendingDelivery = next;
            return;
        }

        // Any other state change clears the buffer
        if (next.state !== WaiterState.WalkingToKitchen && next.state !== WaiterState.Delivering) {
            this._headingToKitchen = false;
            this._pendingDelivery  = null;
        }

        const prevIdle = this.data.idlePosition;
        this.data = next;
        this.redraw();

        const stateChanged  = prevState !== next.state;
        const targetChanged = prevTargetId !== next.targetSessionId;
        // Idle spot moved (pass wait over → back to the sector post)
        const idleMoved = next.state === WaiterState.Idle && !stateChanged
            && (prevIdle?.x !== next.idlePosition?.x || prevIdle?.y !== next.idlePosition?.y);

        if (stateChanged || targetChanged) this._pendingCallback = false;
        if (stateChanged || (targetChanged && !!next.targetPosition) || idleMoved) {
            this.handleStateEnter(next.state);
        }
    }

    // ── Visual rendering by state ─────────────────────────────

    private redraw() {
        // Head
        this.head.clear();
        this.head
            .circle(0, 0, 6)
            .fill({ color: SKIN })
            .stroke({ color: SKIN_STROKE, width: 1 });
        this.head.position.set(0, -20);

        // Body (uniform)
        this.body.clear();
        this.body
            .roundRect(-8, -10, 16, 18, 4)
            .fill({ color: UNIFORM_FILL, alpha: 0.95 })
            .stroke({ color: UNIFORM_STROKE, width: 1.5 });
        // Uniform buttons
        this.body.circle(0, -4, 1.2).fill({ color: 0xd1d5db });
        this.body.circle(0,  0, 1.2).fill({ color: 0xd1d5db });
        this.body.circle(0,  4, 1.2).fill({ color: 0xd1d5db });

        // Held item — _headingToKitchen keeps the notepad visible through the
        // full walk even if the backend has already advanced the state
        const s = this.data.state;
        if (s === WaiterState.WalkingToKitchen || this._headingToKitchen) {
            drawNotepad(this.item);
            this.item.visible = true;
        } else if (s === WaiterState.Delivering && this._carrying) {
            drawTray(this.item, this.data.dishes || 1);
            this.item.visible = true;
        } else {
            this.item.visible = false;
        }

        // Badge
        this.badge.clear();
        if (s === WaiterState.TakingOrder) {
            this.badge.circle(0, 0, 4).fill({ color: 0xfbbf24 });
            this.badge.visible = true;
        } else {
            this.badge.visible = false;
        }
    }

    // ── FSM by state ──────────────────────────────────────────

    private handleStateEnter(state: WaiterState) {
        if (this._takingOrderRaf !== null) {
            cancelAnimationFrame(this._takingOrderRaf);
            this._takingOrderRaf = null;
        }
        this.path    = [];
        this.pathIdx = 0;
        this._onComplete = undefined;
        this._isWalking  = false;

        if (state !== WaiterState.Delivering) this._carrying = false;

        // Clear kitchen flag unless we are entering WALKING_TO_KITCHEN right now
        if (state !== WaiterState.WalkingToKitchen) {
            this._headingToKitchen = false;
            this._pendingDelivery  = null;
        }

        switch (state) {

            case WaiterState.Delivering: {
                if (!this.data.targetPosition) {
                    console.warn(`[Waiter ${this.data.id.slice(-4)}] ${state} without targetPosition`);
                    break;
                }
                const toTable = () => {
                    this._carrying = true;
                    this.redraw();
                    this._isWalking = true;
                    this.walkAStar(this.data.targetPosition ?? KITCHEN_POS, () => {
                        if (this._pendingCallback) return;
                        this._pendingCallback = true;
                        void notifyWaiterState(this.data.id, WaiterState.Idle);
                    });
                };
                const atPass = Math.hypot(this.container.x - KITCHEN_POS.x, this.container.y - KITCHEN_POS.y) <= AT_PASS_RADIUS;
                if (atPass) {
                    toTable();
                } else {
                    // Dishes are collected at the pass first
                    this._carrying = false;
                    this.redraw();
                    this._isWalking = true;
                    this.walkAStar(KITCHEN_POS, toTable);
                }
                break;
            }

            case WaiterState.WalkingToClient: {
                if (this.data.targetPosition) {
                    this._isWalking = true;
                    this.walkAStar(this.data.targetPosition, () => {
                        if (this._pendingCallback) return;
                        this._pendingCallback = true;
                        void notifyWaiterState(this.data.id, WaiterState.TakingOrder);
                    });
                } else {
                    // Missing target position — next sync with a valid position
                    // will re-trigger handleStateEnter via targetChanged.
                    console.warn(`[Waiter ${this.data.id.slice(-4)}] ${state} without targetPosition`);
                }
                break;
            }

            case WaiterState.TakingOrder:
                this.playTakingOrderAnimation(
                    TAKING_ORDER_BASE_MS + TAKING_ORDER_PER_CLIENT_MS * Math.max(1, this.data.targetSessionIds.length),
                    () => {
                        if (this._pendingCallback) return;
                        this._pendingCallback = true;
                        void notifyWaiterState(this.data.id, WaiterState.WalkingToKitchen);
                    },
                );
                break;

            case WaiterState.WalkingToKitchen:
                this._headingToKitchen = true;
                this._isWalking = true;
                this.walkAStar(KITCHEN_POS, () => {
                    this._headingToKitchen = false;
                    if (this._pendingCallback) return;
                    this._pendingCallback = true;
                    void notifyWaiterState(this.data.id, WaiterState.Idle).then(() => {
                        // If a DELIVERING state was buffered, apply it now that
                        // the waiter has physically reached the kitchen
                        if (this._pendingDelivery) {
                            const pending = this._pendingDelivery;
                            this._pendingDelivery = null;
                            this.sync(pending);
                        }
                    });
                });
                break;

            case WaiterState.Idle: {
                // Backend-chosen spot: the waiter's sector post, or the pass while waiting for dishes
                const spot = this.data.idlePosition ?? this._idlePos;
                const d = Math.hypot(this.container.x - spot.x, this.container.y - spot.y);
                if (d > 32) {
                    this._isWalking = true;
                    this.walkAStar(spot);
                }
                break;
            }
        }
    }

    // ── Order-taking animation ────────────────────────────────

    private playTakingOrderAnimation(durationMs: number, onDone: () => void) {
        let elapsed  = 0;
        let blinkOn  = true;
        let lastTime = performance.now();

        const blink = (now: number) => {
            elapsed  += now - lastTime;
            lastTime  = now;

            if (elapsed % 800 < 400 && !blinkOn) {
                blinkOn = true;
                this.badge.alpha = 1;
            } else if (elapsed % 800 >= 400 && blinkOn) {
                blinkOn = false;
                this.badge.alpha = 0.2;
            }

            if (elapsed < durationMs) {
                this._takingOrderRaf = requestAnimationFrame(blink);
            } else {
                this._takingOrderRaf = null;
                this.badge.alpha = 1;
                onDone();
            }
        };
        this._takingOrderRaf = requestAnimationFrame(blink);
    }

    // ── Pathfinding ───────────────────────────────────────────

    private walkAStar(target: Position, onComplete?: () => void) {
        const free  = this.nav.nearestFree(target.x, target.y);
        const start = this.nav.toTile(this.container.x, this.container.y);
        const end   = this.nav.toTile(free.px, free.py);

        this.easystar.findPath(start.tx, start.ty, end.tx, end.ty, (path) => {
            if (!path || path.length === 0) {
                this.walkStraightTo(target, onComplete);
                return;
            }
            this.path    = path.map(p => { const r = this.nav.toPixel(p.x, p.y); return { x: r.px, y: r.py }; });
            this.pathIdx = 0;
            this._onComplete = onComplete;
        });
        this.easystar.calculate();
    }

    private walkStraightTo(target: Position, onComplete?: () => void) {
        this.path    = [target];
        this.pathIdx = 0;
        this._onComplete = onComplete;
    }

    // ── Game loop ─────────────────────────────────────────────

    update(deltaMS: number) {
        const isMoving = this.path.length > 0 && this.pathIdx < this.path.length;

        // Walking bob — subtle vertical oscillation
        if (isMoving) {
            this._bobAccum += deltaMS;
            this.inner.y = Math.sin(this._bobAccum * 0.012) * 1.8;
        } else {
            // Smooth return to y=0
            this.inner.y *= 0.8;
            if (Math.abs(this.inner.y) < 0.05) { this.inner.y = 0; this._bobAccum = 0; }
        }

        if (!isMoving) return;

        const target = this.path[this.pathIdx];
        const dx     = target.x - this.container.x;
        const dy     = target.y - this.container.y;
        const dist   = Math.sqrt(dx * dx + dy * dy);
        const step   = WALK_SPEED * (deltaMS / 1000);

        // Horizontal mirror based on movement direction
        if (Math.abs(dx) > 2) this.inner.scale.x = dx > 0 ? 1 : -1;

        if (dist <= step) {
            this.container.position.set(target.x, target.y);
            this.pathIdx++;
            if (this.pathIdx >= this.path.length) {
                this.path = [];
                const cb = this._onComplete;
                this._onComplete = undefined;
                cb?.();
            }
        } else {
            this.container.x += (dx / dist) * step;
            this.container.y += (dy / dist) * step;
        }
    }

    destroy() {
        if (this._takingOrderRaf !== null) cancelAnimationFrame(this._takingOrderRaf);
        this.container.destroy({ children: true });
    }
}
