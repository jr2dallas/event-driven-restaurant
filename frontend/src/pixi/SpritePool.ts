// src/pixi/SpritePool.ts
// Reusable pool of PixiJS containers to avoid repeated allocations
import * as PIXI from 'pixi.js';

type Factory<T> = () => T;
type Resetter<T> = (item: T) => void;

export class SpritePool<T extends { container: PIXI.Container }> {
    private pool:    T[]      = [];
    private active:  Set<T>   = new Set();
    private factory: Factory<T>;
    private reset:   Resetter<T>;
    private maxSize: number;

    constructor(factory: Factory<T>, reset: Resetter<T>, prealloc = 0, maxSize = 200) {
        this.factory = factory;
        this.reset   = reset;
        this.maxSize = maxSize;

        // Pre-allocation
        for (let i = 0; i < prealloc; i++) {
            const item = this.factory();
            item.container.visible = false;
            this.pool.push(item);
        }
    }

    // ── Acquire a sprite from the pool ───────────────────────
    acquire(): T {
        let item: T;

        if (this.pool.length > 0) {
            item = this.pool.pop()!;
        } else {
            item = this.factory();
        }

        this.reset(item);
        item.container.visible = true;
        item.container.alpha   = 1;
        this.active.add(item);
        return item;
    }

    // ── Return a sprite to the pool ──────────────────────────
    release(item: T): void {
        if (!this.active.has(item)) return;
        this.active.delete(item);
        item.container.visible = false;
        item.container.alpha   = 1;

        if (this.pool.length < this.maxSize) {
            this.pool.push(item);
        } else {
            // Pool full — destroy for real
            item.container.destroy({ children: true });
        }
    }

    // ── Return all active sprites ─────────────────────────────
    releaseAll(): void {
        for (const item of this.active) {
            this.release(item);
        }
    }

    get activeCount() { return this.active.size; }
    get pooledCount() { return this.pool.length; }

    destroy(): void {
        this.releaseAll();
        for (const item of this.pool) {
            item.container.destroy({ children: true });
        }
        this.pool = [];
    }
}
