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
    targetPosition: Position | null; // position pixel de la cible
}

// ── Store ──────────────────────────────────────────────────────
interface WaiterSpritesState {
    waiters: Map<string, WaiterSpriteData>;
    upsert:      (id: string, state: WaiterState, targetSessionId: string | null, targetPos: Position | null) => void;
    transition:  (id: string, next: WaiterState) => void;
    setPosition: (id: string, pos: Position) => void;
    getById:     (id: string) => WaiterSpriteData | undefined;
    remove:      (id: string) => void;
}

// Waiter spawn position (kitchen / counter area)
const WAITER_SPAWN_POS: Position = { x: 1900, y: 760 };

export const useWaiterSpritesStore = create<WaiterSpritesState>((set, get) => ({
    waiters: new Map(),

    upsert(id, state, targetSessionId, targetPos) {
        set(s => {
            const waiters = new Map(s.waiters);
            const existing = waiters.get(id);
            // If position resolution fails (session absent from current poll)
            // but targetSessionId is unchanged, keep the last known position
            // so we don't lose track of a waiter already en route.
            const sameSession = existing?.targetSessionId === targetSessionId;
            const resolvedPos = targetPos ?? (sameSession ? (existing?.targetPosition ?? null) : null);
            waiters.set(id, {
                id,
                state,
                position: existing?.position ?? WAITER_SPAWN_POS,
                targetSessionId,
                targetPosition: resolvedPos,
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
                console.warn(`[WaiterFSM] Transition invalide: ${waiter.state} → ${next} (${id})`);
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
