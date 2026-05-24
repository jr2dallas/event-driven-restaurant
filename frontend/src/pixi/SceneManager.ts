// src/pixi/SceneManager.ts
import * as PIXI from 'pixi.js';
import EasyStar from 'easystarjs';
import { AT_DOOR, ENTERING, useClientSpritesStore, SessionState } from '../store/clientSpritesStore';
import { notifyClientState } from '../api/animationCallbacks';
import { hashId } from '../utils/hashId';
import { useWaiterSpritesStore } from '../store/waiterSpritesStore';
import { useKitchenStore } from '../store/kitchenStore';
import { useDishReactionStore } from '../store/dishReactionStore';
import type { DishReaction } from '../store/dishReactionStore';
import type { ClientSpriteData, ClientState } from '../store/clientSpritesStore';
import type { WaiterSpriteData } from '../store/waiterSpritesStore';
import type { ChefData } from '../store/kitchenStore';
import { buildNavGrid, CHAIR_TILE } from '../pathfinding/buildNavGrid';
import { ClientSprite } from './ClientSprite';
import { WaiterSprite } from './WaiterSprite';
import { KitchenSprite } from './KitchenSprite';
import { SpeechBubble } from './SpeechBubble';
import { CrowdPhysics } from './CrowdPhysics';
import { loadCharacterVariants } from './spritesheet';
import type { WalkTextures } from './spritesheet';


// ── Crowd + door geometry ──────────────────────────────────────
// outsideAnchor: where the crowd presses toward
// wallX: right boundary — clients cannot cross this in crowd state
// (the door threshold is at x=262, handled by AT_DOOR state)
const CROWD_ANCHOR_X = 228;
const CROWD_ANCHOR_Y = 450;
const CROWD_WALL_X   = 262;  // door threshold — crowd stays left of this

export class SceneManager {
    private app: PIXI.Application;
    private nav: ReturnType<typeof buildNavGrid>;
    private easystar: EasyStar.js;
    private clientSprites:  Map<string, ClientSprite>  = new Map();
    private waiterSprites:  Map<string, WaiterSprite>  = new Map();
    private kitchenSprites: Map<string, KitchenSprite> = new Map();
    private bgLayer:     PIXI.Container | null = null;
    private spriteLayer: PIXI.Container | null = null;
    private debugLayer:  PIXI.Graphics  | null = null;
    private _debugPaths = false;
    private unsubClients:       () => void = () => {};
    private unsubWaiters:       () => void = () => {};
    private unsubKitchen:       () => void = () => {};
    private unsubDishReaction:  () => void = () => {};
    private activeBubble: {
        bubble: SpeechBubble
        actorType: 'waiter' | 'chef'
        actorId: string
        timerId: ReturnType<typeof setTimeout>
    } | null = null;
    private _waiterSlots    = new Map<string, number>();
    private _nextWaiterSlot = 0;
    private ready = false;
    private _destroyed = false;

    private crowd        = new CrowdPhysics(CROWD_ANCHOR_X, CROWD_ANCHOR_Y, CROWD_WALL_X);
    private physicsAccum = 0;
    private queueingIds  = new Set<string>();
    private toRemove     = new Array<string>();
    // Variantes de sprites clients — vide = fallback Graphics
    private clientVariants: WalkTextures[] = [];

    constructor(private canvas: HTMLCanvasElement, private imageW: number, private imageH: number) {
        this.app = new PIXI.Application();
        this.nav = buildNavGrid(imageW, imageH);
        this.easystar = new EasyStar.js();
        this.easystar.setGrid(this.nav.grid);
        this.easystar.setAcceptableTiles([0, CHAIR_TILE]);
        for (const { tx, ty } of this.nav.chairTiles)
            this.easystar.setAdditionalPointCost(tx, ty, 12);
        this.easystar.enableDiagonals();
        this.easystar.disableCornerCutting();
    }

    async init(backgroundUrl: string): Promise<void> {
        await this.app.init({
            canvas: this.canvas,
            width: this.imageW,
            height: this.imageH,
            backgroundAlpha: 0,
            antialias: true,
            autoDensity: true,
            resolution: window.devicePixelRatio || 1,
        });
        if (this._destroyed) { this.app.ticker.stop(); return; }

        this.bgLayer = new PIXI.Container();
        this.spriteLayer = new PIXI.Container();
        this.debugLayer = new PIXI.Graphics();
        this.app.stage.addChild(this.bgLayer, this.spriteLayer, this.debugLayer);

        await this.loadBackground(backgroundUrl);
        if (this._destroyed) { this.app.ticker.stop(); return; }

        // Tentative de chargement des variantes de sprites clients.
        // Ajouter des fichiers dans public/sprites/ pour activer le mode sprite.
        // Nommage : client_1.png, client_2.png, … (autant que de variantes voulues)
        // Si aucun fichier n'existe, fallback transparent sur les Graphics.
        try {
            this.clientVariants = await loadCharacterVariants([
                '/sprites/client_1.png',
                '/sprites/client_2.png',
                '/sprites/client_3.png',
            ]);
        } catch {
            // pas de sprites → Graphics fallback
        }
        if (this._destroyed) { this.app.ticker.stop(); return; }

        this.app.ticker.add(() => this.tick());

        this.unsubClients = useClientSpritesStore.subscribe(
            (state, prevState) => {
                if (state.clients !== prevState.clients)
                    this.syncClientSprites(state.clients);
            }
        );

        this.unsubWaiters = useWaiterSpritesStore.subscribe(
            (state, prevState) => {
                if (state.waiters !== prevState.waiters)
                    this.syncWaiterSprites(state.waiters);
            }
        );

        this.unsubKitchen = useKitchenStore.subscribe(
            (state, prevState) => {
                if (state.chefs !== prevState.chefs)
                    this.syncKitchenSprites(state.chefs);
            }
        );

        this.unsubDishReaction = useDishReactionStore.subscribe(
            (state, prevState) => {
                if (state.reaction && state.reaction !== prevState.reaction)
                    this.showSpeechBubble(state.reaction);
            }
        );

        this.ready = true;

        this.syncClientSprites(useClientSpritesStore.getState().clients);
        this.syncWaiterSprites(useWaiterSpritesStore.getState().waiters);
        this.syncKitchenSprites(useKitchenStore.getState().chefs);
    }

    private async loadBackground(url: string): Promise<void> {
        const texture = await PIXI.Assets.load(url);
        const bg = new PIXI.Sprite(texture);
        bg.width = this.imageW;
        bg.height = this.imageH;
        this.bgLayer!.addChild(bg);
    }

    // ── Sync clients ──────────────────────────────────────────
    private syncClientSprites(clients: Map<string, ClientSpriteData>): void {
        if (!this.ready || !this.spriteLayer) return;

        for (const [id, data] of clients) {
            if (!this.clientSprites.has(id)) {
                // Deterministic variant by ID (same client always gets the same look)
                let walkTex: WalkTextures | undefined;
                if (this.clientVariants.length > 0)
                    walkTex = this.clientVariants[hashId(id) % this.clientVariants.length];
                const sprite = new ClientSprite(
                    data, this.nav, this.easystar,
                    (sid, next) => this.handleClientStateChange(sid, next),
                    walkTex,
                );
                this.clientSprites.set(id, sprite);
                this.spriteLayer.addChild(sprite.container);
            } else {
                this.clientSprites.get(id)!.sync(data);
            }
        }

        for (const [id, sprite] of this.clientSprites) {
            if (!clients.has(id)) {
                sprite.destroy();
                this.clientSprites.delete(id);
                this.crowd.remove(id);
            }
        }
    }

    // ── Sync waiters ──────────────────────────────────────────
    private syncWaiterSprites(waiters: Map<string, WaiterSpriteData>): void {
        if (!this.ready || !this.spriteLayer) return;
        for (const [id, data] of waiters) {
            if (!this.waiterSprites.has(id)) {
                if (!this._waiterSlots.has(id))
                    this._waiterSlots.set(id, this._nextWaiterSlot++);
                const slot   = this._waiterSlots.get(id)!;
                const sprite = new WaiterSprite(data, this.nav, this.easystar, slot);
                this.waiterSprites.set(id, sprite);
                this.spriteLayer.addChild(sprite.container);
            } else {
                this.waiterSprites.get(id)!.sync(data);
            }
        }
        for (const [id, sprite] of this.waiterSprites) {
            if (!waiters.has(id)) {
                sprite.destroy();
                this.waiterSprites.delete(id);
                this._waiterSlots.delete(id);
            }
        }
    }

    // ── Sync kitchen ─────────────────────────────────────────
    private syncKitchenSprites(chefs: ChefData[]): void {
        if (!this.ready || !this.spriteLayer) return;
        const nextIds = new Set(chefs.map(c => c.instanceId));
        for (const chef of chefs) {
            if (!this.kitchenSprites.has(chef.instanceId)) {
                const sprite = new KitchenSprite(chef);
                this.kitchenSprites.set(chef.instanceId, sprite);
                this.spriteLayer.addChild(sprite.container);
            } else {
                this.kitchenSprites.get(chef.instanceId)!.sync(chef);
            }
        }
        for (const [id, sprite] of this.kitchenSprites) {
            if (!nextIds.has(id)) { sprite.destroy(); this.kitchenSprites.delete(id); }
        }
    }

    // ── Speech bubbles ────────────────────────────────────────
    private showSpeechBubble(reaction: DishReaction): void {
        if (!this.spriteLayer) return;

        if (this.activeBubble) {
            clearTimeout(this.activeBubble.timerId);
            this.spriteLayer.removeChild(this.activeBubble.bubble.container);
            this.activeBubble.bubble.destroy();
            this.activeBubble = null;
        }

        const actorContainer = reaction.actorType === 'waiter'
            ? this.waiterSprites.get(reaction.actorId)?.container
            : this.kitchenSprites.get(reaction.actorId)?.container;

        if (!actorContainer) return;

        const bubble = new SpeechBubble(reaction.message);
        bubble.container.x = actorContainer.x;
        bubble.container.y = actorContainer.y - 42;
        this.spriteLayer.addChild(bubble.container);

        const timerId = setTimeout(() => {
            if (this.spriteLayer) this.spriteLayer.removeChild(bubble.container);
            bubble.destroy();
            this.activeBubble = null;
        }, 2800);

        this.activeBubble = { bubble, actorType: reaction.actorType, actorId: reaction.actorId, timerId };
    }

    // ── FSM callbacks ─────────────────────────────────────────
    private handleClientStateChange(sessionId: string, next: ClientState): void {
        const store = useClientSpritesStore.getState();
        switch (next) {
            case ENTERING:
                void notifyClientState(sessionId, SessionState.AtEntrance);
                store.transition(sessionId, ENTERING);
                break;
            case SessionState.Seated:
                void notifyClientState(sessionId, SessionState.Seated);
                store.transition(sessionId, SessionState.Seated);
                break;
            case SessionState.Despawned:
                void notifyClientState(sessionId, SessionState.Despawned);
                store.despawn(sessionId);
                break;
            default:
                store.transition(sessionId, next);
        }
    }

    // ── Door admission ────────────────────────────────────────

    /** No AT_DOOR client currently exists → door is free for the next candidate. */
    private doorIsFree(): boolean {
        for (const sprite of this.clientSprites.values()) {
            if (sprite.data.state === AT_DOOR) return false;
        }
        return true;
    }

    /**
     * Pick the next client to admit through the door:
     * oldest arrival time first (FIFO queue), then proximity as tiebreaker.
     * Deliberately NOT based on who physically pressed to the front — the crowd
     * is visual jostling only; admission order is fair.
     */
    private pickDoorCandidate(): string | null {
        let best: ClientSpriteData | null = null;
        for (const sprite of this.clientSprites.values()) {
            if (sprite.data.state !== SessionState.Queuing) continue;
            if (!best) { best = sprite.data; continue; }
            if (sprite.data.arrivedAt < best.arrivedAt) best = sprite.data;
            else if (sprite.data.arrivedAt === best.arrivedAt) {
                // Tiebreak by proximity to door
                const s  = this.clientSprites.get(sprite.data.id)!;
                const b  = this.clientSprites.get(best.id)!;
                const ds = (s.container.x - CROWD_WALL_X) ** 2 + (s.container.y - CROWD_ANCHOR_Y) ** 2;
                const db = (b.container.x - CROWD_WALL_X) ** 2 + (b.container.y - CROWD_ANCHOR_Y) ** 2;
                if (ds < db) best = sprite.data;
            }
        }
        return best?.id ?? null;
    }

    /** Stable side bias from ID — breaks crowd symmetry */
    private sideBiasFor(id: string): number {
        return (hashId(id) & 1) ? 1 : -1;
    }

    toggleDebugPaths(): void {
        this._debugPaths = !this._debugPaths;
        if (!this._debugPaths) this.debugLayer?.clear();
    }

    // ── Game loop ─────────────────────────────────────────────
    private tick(): void {
        if (!this.ready) return;
        const deltaMS = this.app.ticker.deltaMS;
        this.easystar.calculate();

        // ── Crowd sync (every frame, O(n)) ────────────────────
        const queueingIds = this.queueingIds;
        queueingIds.clear();
        for (const [id, sprite] of this.clientSprites) {
            if (sprite.data.state !== SessionState.Queuing) continue;
            queueingIds.add(id);
            if (!this.crowd.has(id))
                this.crowd.add(id, sprite.container.x, sprite.container.y, this.sideBiasFor(id));
        }
        // Remove agents that are no longer QUEUING — collect first, then remove
        const toRemove = this.toRemove;
        toRemove.length = 0;
        for (const id of this.crowd.ids())
            if (!queueingIds.has(id)) toRemove.push(id);
        for (const id of toRemove) this.crowd.remove(id);

        // ── Crowd physics — step scales with crowd size ───────────
        const n = queueingIds.size;
        const physicsStep = n < 40 ? 50 : n < 100 ? 80 : 120;
        this.physicsAccum += deltaMS;
        if (this.physicsAccum >= physicsStep) {
            this.crowd.tick(this.physicsAccum);
            this.physicsAccum = 0;
            for (const id of queueingIds) {
                const pos = this.crowd.getPos(id);
                if (pos) this.clientSprites.get(id)!.setCrowdPos(pos.x, pos.y);
            }
        }

        // ── Door admission (FIFO, one at a time) ──────────────
        if (this.doorIsFree()) {
            const candidateId = this.pickDoorCandidate();
            if (candidateId)
                useClientSpritesStore.getState().transition(candidateId, AT_DOOR);
        }

        // ── Movement for all sprites ──────────────────────────
        for (const sprite of this.clientSprites.values()) sprite.update(deltaMS);
        for (const sprite of this.waiterSprites.values()) sprite.update(deltaMS);
        for (const sprite of this.kitchenSprites.values()) sprite.update(deltaMS);

        // ── Debug path overlay ────────────────────────────────
        if (this._debugPaths && this.debugLayer) {
            const g = this.debugLayer;
            g.clear();
            for (const sprite of this.clientSprites.values()) {
                const { path, pathIndex, container } = sprite;
                if (path.length === 0 || pathIndex >= path.length) continue;
                g.moveTo(container.x, container.y);
                for (let i = pathIndex; i < path.length; i++) g.lineTo(path[i].x, path[i].y);
                g.stroke({ color: 0x22c55e, width: 2, alpha: 0.75 });
                g.circle(path[path.length - 1].x, path[path.length - 1].y, 5);
                g.fill({ color: 0x22c55e, alpha: 0.9 });
            }
            for (const sprite of this.waiterSprites.values()) {
                const { path, pathIdx, container } = sprite;
                if (path.length === 0 || pathIdx >= path.length) continue;
                g.moveTo(container.x, container.y);
                for (let i = pathIdx; i < path.length; i++) g.lineTo(path[i].x, path[i].y);
                g.stroke({ color: 0x3b82f6, width: 2, alpha: 0.75 });
                g.circle(path[path.length - 1].x, path[path.length - 1].y, 5);
                g.fill({ color: 0x3b82f6, alpha: 0.9 });
            }
        }

        // ── Follow active speech bubble actor ─────────────────
        if (this.activeBubble) {
            const { actorType, actorId, bubble } = this.activeBubble;
            const c = actorType === 'waiter'
                ? this.waiterSprites.get(actorId)?.container
                : this.kitchenSprites.get(actorId)?.container;
            if (c) { bubble.container.x = c.x; bubble.container.y = c.y - 42; }
        }
    }

    destroy(): void {
        this._destroyed = true;
        this.unsubClients();
        this.unsubWaiters();
        this.unsubKitchen();
        this.unsubDishReaction();
        if (this.activeBubble) {
            clearTimeout(this.activeBubble.timerId);
            this.activeBubble.bubble.destroy();
            this.activeBubble = null;
        }
        if (this.ready) { this.app.destroy(false); this.ready = false; }
    }
}
