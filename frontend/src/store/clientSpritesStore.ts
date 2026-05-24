// src/store/clientSpritesStore.ts
import { create } from 'zustand';
import { SessionState } from '../api/generated/model/session-state';

export { SessionState };

// Animation-only intermediate states (not in server SessionState)
export const AT_DOOR  = 'AT_DOOR'  as const;
export const ENTERING = 'ENTERING' as const;

// Animation FSM: server SessionState values + two animation-only intermediate states
export type ClientState =
    | typeof SessionState.Queuing        // 'QUEUING'
    | typeof AT_DOOR                     // animation only: walking to door threshold
    | typeof ENTERING                    // animation only: walking through corridor
    | typeof SessionState.WalkingToSeat  // 'WALKING_TO_SEAT'
    | typeof SessionState.Seated         // 'SEATED'
    | typeof SessionState.WaitingOrder   // 'WAITING_ORDER'
    | typeof SessionState.Eating         // 'EATING'
    | typeof SessionState.Leaving        // 'LEAVING'
    | typeof SessionState.Despawned;     // 'DESPAWNED'

export interface Position { x: number; y: number; }

export interface ClientSpriteData {
    id: string;
    state: ClientState;
    position: Position;
    targetSeat: Position | null;
    seatId: string | null;
    queueIndex: number;
    spawnSide: 'top' | 'bottom';
    arrivedAt: number;
    tag: string | null;
}

// Strict FSM — only these transitions are allowed
const { Queuing, WalkingToSeat, Seated, WaitingOrder, Eating, Leaving, Despawned } = SessionState;
const VALID_TRANSITIONS: Record<ClientState, ClientState[]> = {
    [Queuing]:    [AT_DOOR],
    [AT_DOOR]:    [ENTERING],
    [ENTERING]:   [WalkingToSeat],
    [WalkingToSeat]:  [Seated],
    [Seated]:         [WaitingOrder],
    [WaitingOrder]:   [Eating],
    [Eating]:         [Leaving],
    [Leaving]:        [Despawned],
    [Despawned]:      [],
};

interface ClientSpritesState {
    clients: Map<string, ClientSpriteData>;
    spawn:                (sessionId: string, spawnSide: 'top' | 'bottom', tag?: string | null) => void;
    spawnAt:              (sessionId: string, spawnSide: 'top' | 'bottom', pos: Position, state: ClientState, seatId: string, tag?: string | null) => void;
    transition:           (sessionId: string, next: ClientState) => void;
    assignSeat:           (sessionId: string, seatId: string, pos: Position) => void;
    updateQueuePositions: () => void;
    despawn:              (sessionId: string) => void;
    getQueued:            () => ClientSpriteData[];
    getById:              (id: string) => ClientSpriteData | undefined;
}

const SPAWN_X = 198;

export const useClientSpritesStore = create<ClientSpritesState>((set, get) => ({
    clients: new Map(),

    spawn(sessionId, spawnSide, tag = null) {
        set(s => {
            const clients = new Map(s.clients);
            if (clients.has(sessionId)) return s;
            clients.set(sessionId, {
                id:         sessionId,
                state:      SessionState.Queuing,
                position:   spawnSide === 'top' ? { x: SPAWN_X, y: 40 } : { x: SPAWN_X, y: 1496 },
                targetSeat: null,
                seatId:     null,
                queueIndex: -1,
                spawnSide,
                arrivedAt:  Date.now(),
                tag,
            });
            return { clients };
        });
        get().updateQueuePositions();
    },

    spawnAt(sessionId, spawnSide, pos, state, seatId, tag = null) {
        set(s => {
            const clients = new Map(s.clients);
            if (clients.has(sessionId)) return s;
            clients.set(sessionId, {
                id: sessionId, state, position: pos, targetSeat: pos,
                seatId, queueIndex: -1, spawnSide, arrivedAt: Date.now(), tag,
            });
            return { clients };
        });
    },

    transition(sessionId, next) {
        const prevState = get().clients.get(sessionId)?.state;
        set(s => {
            const clients = new Map(s.clients);
            const client  = clients.get(sessionId);
            if (!client) return s;
            const allowed = VALID_TRANSITIONS[client.state] ?? [];
            if (!allowed.includes(next)) {
                console.warn(`[FSM] invalide: ${client.state} → ${next} (${sessionId.slice(-4)})`);
                return s;
            }
            clients.set(sessionId, { ...client, state: next });
            return { clients };
        });
        if (prevState === SessionState.Queuing) get().updateQueuePositions();
    },

    assignSeat(sessionId, seatId, pos) {
        set(s => {
            const clients = new Map(s.clients);
            const client  = clients.get(sessionId);
            if (!client) return s;
            clients.set(sessionId, { ...client, seatId, targetSeat: pos, queueIndex: -1 });
            return { clients };
        });
        get().updateQueuePositions();
    },

    updateQueuePositions() {
        set(s => {
            const clients = new Map(s.clients);
            const queued  = Array.from(clients.values())
                .filter(c => c.state === SessionState.Queuing)
                .sort((a, b) => a.arrivedAt - b.arrivedAt);
            queued.forEach((c, i) => {
                clients.set(c.id, { ...c, queueIndex: i });
            });
            return { clients };
        });
    },

    despawn(sessionId) {
        set(s => {
            const clients = new Map(s.clients);
            clients.delete(sessionId);
            return { clients };
        });
        get().updateQueuePositions();
    },

    getQueued() {
        return Array.from(get().clients.values())
            .filter(c => c.state === SessionState.Queuing)
            .sort((a, b) => a.arrivedAt - b.arrivedAt);
    },

    getById(id) { return get().clients.get(id); },
}));
