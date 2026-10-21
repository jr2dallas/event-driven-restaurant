// src/store/waiterSpritesStore.ts
import { create } from 'zustand';
import type { Position } from './clientSpritesStore';
import { WaiterState } from '../api/generated/model/waiter-state';

export { WaiterState };

const { Idle, WalkingToClient, TakingOrder, WalkingToKitchen, Delivering } = WaiterState;
const VALID_TRANSITIONS: Record<WaiterState, WaiterState[]> = {
    [Idle]:               [WalkingToClient, Delivering],
    [WalkingToClient]:    [TakingOrder, Idle],
    [TakingOrder]:        [WalkingToKitchen, Idle],
    [WalkingToKitchen]:   [Delivering, Idle],
    [Delivering]:         [Idle],
};

// ── Types ──────────────────────────────────────────────────────
export interface WaiterSpriteData {
    id: string;
    state: WaiterState;
    position: Position;
    targetSessionId: string | null;
    targetPosition: Position | null; // pixel position of the primary client
    /** Every client of the current round (one table, one visit) */
    targetSessionIds: string[];
    /** Plates carried while delivering */
    dishes: number;
    /** Where the backend wants the waiter to wait while idle (home post, or the pass) */
    idlePosition: Position | null;
}

export interface WaiterUpdate {
    state: WaiterState;
    targetSessionId: string | null;
    targetPos: Position | null;
    targetSessionIds: string[];
    dishes: number;
    idlePosition: Position | null;
}

// ── Store ──────────────────────────────────────────────────────
interface WaiterSpritesState {
    waiters: Map<string, WaiterSpriteData>;
    upsert:      (id: string, update: WaiterUpdate) => void;
    transition:  (id: string, next: WaiterState) => void;
    setPosition: (id: string, pos: Position) => void;
    getById:     (id: string) => WaiterSpriteData | undefined;
    remove:      (id: string) => void;
}

// Waiter spawn position (kitchen / counter area)
const WAITER_SPAWN_POS: Position = { x: 1900, y: 760 };

const samePos = (a: Position | null, b: Position | null) =>
    a === b || (!!a && !!b && a.x === b.x && a.y === b.y);

const sameIds = (a: string[], b: string[]) =>
    a.length === b.length && a.every((id, i) => id === b[i]);

export const useWaiterSpritesStore = create<WaiterSpritesState>((set, get) => ({
    waiters: new Map(),

    upsert(id, update) {
        const existing = get().waiters.get(id);
        // If position resolution fails (session absent from current payload)
        // but targetSessionId is unchanged, keep the last known position
        // so we don't lose track of a waiter already en route.
        const sameSession = existing?.targetSessionId === update.targetSessionId;
        const resolvedPos = update.targetPos ?? (sameSession ? (existing?.targetPosition ?? null) : null);

        // Pushed states arrive often: skip no-op updates so sprites are not re-synced for nothing
        if (existing
            && existing.state === update.state
            && sameSession
            && samePos(existing.targetPosition, resolvedPos)
            && samePos(existing.idlePosition, update.idlePosition)
            && existing.dishes === update.dishes
            && sameIds(existing.targetSessionIds, update.targetSessionIds)) return;

        set(s => {
            const waiters = new Map(s.waiters);
            waiters.set(id, {
                id,
                state:            update.state,
                position:         existing?.position ?? WAITER_SPAWN_POS,
                targetSessionId:  update.targetSessionId,
                targetPosition:   resolvedPos,
                targetSessionIds: update.targetSessionIds,
                dishes:           update.dishes,
                idlePosition:     update.idlePosition,
            });
            return { waiters };
        });
    },

    transition(id, next) {
        set(s => {
            const waiters = new Map(s.waiters);
            const waiter = waiters.get(id);
            if (!waiter) return s;
            const allowed = VALID_TRANSITIONS[waiter.state];
            if (!allowed.includes(next)) {
                console.warn(`[WaiterFSM] Invalid transition: ${waiter.state} → ${next} (${id})`);
                return s;
            }
            waiters.set(id, { ...waiter, state: next });
            return { waiters };
        });
    },

    setPosition(id, pos) {
        set(s => {
            const waiters = new Map(s.waiters);
            const waiter = waiters.get(id);
            if (!waiter) return s;
            waiters.set(id, { ...waiter, position: pos });
            return { waiters };
        });
    },

    getById(id) { return get().waiters.get(id); },

    remove(id) {
        set(s => {
            const waiters = new Map(s.waiters);
            waiters.delete(id);
            return { waiters };
        });
    },
}));
