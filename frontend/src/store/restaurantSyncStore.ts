// src/store/restaurantSyncStore.ts
import { create } from 'zustand';
import type { RestaurantState } from '../api/generated/model/restaurant-state';
import { SessionState } from '../api/generated/model/session-state';
import { WaiterState } from '../api/generated/model/waiter-state';
import { AT_DOOR, useClientSpritesStore, type ClientState, type Position } from './clientSpritesStore';
import { useWaiterSpritesStore } from './waiterSpritesStore';
import { resolveSeatById, resolveWaiterServicePosition } from '../pathfinding/resolveSeatsFromLayout';

interface SyncState {
    lastState: RestaurantState | null;
    isPolling: boolean;
    pollInterval: number;
    error: string | null;
    setPollInterval: (ms: number) => void;
    startPolling: () => void;
    stopPolling: () => void;
    _tick: () => Promise<void>;
}

let _handle: ReturnType<typeof setInterval> | null = null;
let _spawnCounter = 0;
const _spawnSides = new Map<string, 'top' | 'bottom'>();

function getSpawnSide(id: string): 'top' | 'bottom' {
    if (!_spawnSides.has(id)) _spawnSides.set(id, _spawnCounter++ % 2 === 0 ? 'top' : 'bottom');
    return _spawnSides.get(id)!;
}

const QUEUE_X       = 80;
const ENTRANCE_Y    = 760;
const QUEUE_SPACING = 48;

const IN_ROOM_STATES: SessionState[] = [
    SessionState.Seated,
    SessionState.WaitingOrder,
    SessionState.Eating,
    SessionState.Leaving,
];

export const useRestaurantSyncStore = create<SyncState>((set, get) => ({
    lastState: null,
    isPolling: false,
    pollInterval: 2000,
    error: null,

    setPollInterval(ms) {
        set({ pollInterval: ms });
        const { isPolling, stopPolling, startPolling } = get();
        if (isPolling) { stopPolling(); startPolling(); }
    },

    startPolling() {
        if (get().isPolling) return;
        set({ isPolling: true, error: null });
        void get()._tick();
        _handle = setInterval(() => void get()._tick(), get().pollInterval);
    },

    stopPolling() {
        if (_handle) { clearInterval(_handle); _handle = null; }
        set({ isPolling: false });
    },

    async _tick() {
        try {
            const res = await fetch('/internal/restaurant/state');
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const next: RestaurantState = await res.json();

            // ── 1. Spawn / page-reload teleport ───────────────────
            for (const session of (next.sessions ?? [])) {
                if (useClientSpritesStore.getState().getById(session.id)) continue;

                const seatPos = session.seatId ? resolveSeatById(session.seatId) : null;
                const side    = getSpawnSide(session.id);
                const inRoom  = IN_ROOM_STATES.includes(session.state);

                if (inRoom && seatPos) {
                    useClientSpritesStore.getState().spawnAt(
                        session.id, side, seatPos,
                        session.state as ClientState,
                        session.seatId!,
                        session.tag,
                    );
                } else {
                    useClientSpritesStore.getState().spawn(session.id, side, session.tag);
                }
            }

            await Promise.resolve();

            // ── 2. Sync state for already-known clients ───────────
            for (const session of (next.sessions ?? [])) {
                const store  = useClientSpritesStore.getState();
                const sprite = store.getById(session.id);
                if (!sprite) continue;

                if (session.seatId && !sprite.seatId) {
                    const pos = resolveSeatById(session.seatId);
                    if (pos) store.assignSeat(session.id, session.seatId, pos);
                }

                const s = useClientSpritesStore.getState().getById(session.id)!;
                const tr = (next: ClientState) => useClientSpritesStore.getState().transition(session.id, next);

                switch (session.state) {
                    case SessionState.Queuing:
                    case SessionState.AtEntrance:
                        // Crowd physics + SceneManager door logic handles everything.
                        break;

                    case SessionState.WalkingToSeat:
                    case SessionState.Seated:
                        if (s.state === SessionState.Queuing && s.targetSeat) tr(AT_DOOR);
                        break;

                    case SessionState.WaitingOrder:
                        if (s.state === SessionState.Queuing && s.targetSeat) tr(AT_DOOR);
                        else if (s.state === SessionState.Seated)              tr(SessionState.WaitingOrder);
                        break;

                    case SessionState.Eating:
                        if (s.state === SessionState.Queuing && s.targetSeat) tr(AT_DOOR);
                        else if (s.state === SessionState.WaitingOrder)        tr(SessionState.Eating);
                        break;

                    case SessionState.Leaving:
                        if ([SessionState.WalkingToSeat, SessionState.Seated, SessionState.WaitingOrder, SessionState.Eating].includes(s.state as SessionState))
                            tr(SessionState.Leaving);
                        break;

                    case SessionState.Despawned:
                        if (![SessionState.Leaving, SessionState.Despawned].includes(s.state as SessionState))
                            tr(SessionState.Leaving);
                        break;
                }
            }

            // ── 3. Remove sessions that disappeared from backend ──
            const nextIds = new Set((next.sessions ?? []).map(s => s.id));
            for (const [id] of useClientSpritesStore.getState().clients) {
                if (!nextIds.has(id)) {
                    useClientSpritesStore.getState().despawn(id);
                    _spawnSides.delete(id);
                }
            }

            // ── 4. Sync waiters ───────────────────────────────────
            const waiterStore   = useWaiterSpritesStore.getState();
            const nextWaiterIds = new Set((next.waiters ?? []).map(w => w.id));

            for (const waiter of (next.waiters ?? [])) {
                let targetPos: Position | null = null;
                if (waiter.targetSessionId) {
                    const ts = next.sessions.find(s => s.id === waiter.targetSessionId);
                    if (ts?.seatId) {
                        targetPos = resolveWaiterServicePosition(ts.seatId) ?? resolveSeatById(ts.seatId);
                    }
                }
                waiterStore.upsert(waiter.id, waiter.state, waiter.targetSessionId ?? null, targetPos);
            }

            for (const [id] of waiterStore.waiters) {
                if (!nextWaiterIds.has(id)) waiterStore.remove(id);
            }

            set({ lastState: next, error: null });

        } catch (err: any) {
            console.error('[restaurantSyncStore] tick error', err);
            set({ error: err?.message ?? 'Unknown error' });
        }
    },
}));
