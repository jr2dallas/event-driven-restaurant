import { create } from 'zustand';
import layoutData from '../config/restaurant-layout.json';
import type { Position } from './clientSpritesStore';
import type { KitchenInstance } from '../api/generated/model/kitchen-instance';
import { KitchenState } from '../api/generated/model/kitchen-state';

export type { KitchenState };

export interface ChefData {
    instanceId:       string;
    status:           KitchenState;
    position:         Position;
    cookingStartedAt: number | null;
    waitingOrders:    number;
    capacity:         number;   // burners (dishes cooked in parallel)
    ghost?:           boolean; // true for optimistic starting placeholders
}

const stations: Position[] = ((layoutData as any).kitchen_station ?? []).map((s: any) => ({
    x: s.x,
    y: s.y,
}));
const FALLBACK_STATION: Position = { x: 1034, y: 450 };

interface KitchenStoreState {
    chefs:           ChefData[];
    startPolling:    () => void;
    stopPolling:     () => void;
    triggerFastPoll: (durationMs?: number) => void;
    addStartingChef: () => void;
    removeLastChef:  () => void;
}

let _handle:     ReturnType<typeof setInterval> | null = null;
let _fastHandle: ReturnType<typeof setTimeout>  | null = null;

// Tombstone set — real instanceIds removed optimistically, suppressed until Consul confirms removal
const _pendingRemovals = new Set<string>();

// ── Stable stations ───────────────────────────────────────────
// A chef keeps its station for life. Consul lists instances sorted by (random) id, so using
// the list index as the station made new chefs land on top of existing ones.
const _stationOf = new Map<string, number>();

function claimStation(id: string, preferred?: number): Position {
    let idx = _stationOf.get(id);
    if (idx === undefined) {
        const used = new Set(_stationOf.values());
        idx = preferred !== undefined && !used.has(preferred)
            ? preferred
            : stations.findIndex((_, i) => !used.has(i));
        _stationOf.set(id, idx);
    }
    return stations[idx] ?? FALLBACK_STATION;
}

function releaseStationsExcept(keep: Set<string>) {
    for (const id of [..._stationOf.keys()]) if (!keep.has(id)) _stationOf.delete(id);
}

async function fetchChefs(
    set: (s: Partial<KitchenStoreState>) => void,
    get: () => KitchenStoreState,
) {
    try {
        const res = await fetch('/internal/kitchen/instances');
        if (!res.ok) return;
        const instances: KitchenInstance[] = await res.json();

        // Clean up tombstones for instances that have truly disappeared from Consul
        for (const id of _pendingRemovals) {
            if (!instances.find(i => i.instanceId === id)) _pendingRemovals.delete(id);
        }

        // Filter out instances pending removal (optimistic fire)
        const visible = instances.filter(i => !_pendingRemovals.has(i.instanceId));

        // Ghost chefs that haven't materialised yet are kept; one is dropped per newly-arrived
        // real instance, which takes over the ghost's station (no visual jump)
        const current      = get().chefs;
        const prevRealIds  = new Set(current.filter(c => !c.ghost).map(c => c.instanceId));
        const newcomers    = visible.filter(i => !prevRealIds.has(i.instanceId));
        const ghosts       = current.filter(c => c.ghost);
        const droppedGhost = ghosts.slice(0, newcomers.length);
        const keptGhosts   = ghosts.slice(newcomers.length);
        const inherited    = new Map(newcomers.map((inst, i) => [
            inst.instanceId,
            droppedGhost[i] ? _stationOf.get(droppedGhost[i].instanceId) : undefined,
        ]));

        releaseStationsExcept(new Set([
            ...visible.map(i => i.instanceId),
            ...keptGhosts.map(g => g.instanceId),
        ]));

        const realChefs: ChefData[] = visible.map(inst => ({
            instanceId:       inst.instanceId,
            status:           inst.status as KitchenState,
            position:         claimStation(inst.instanceId, inherited.get(inst.instanceId)),
            cookingStartedAt: (inst as any).cookingStartedAt ?? null,
            waitingOrders:    inst.waitingOrders ?? 0,
            capacity:         inst.capacity ?? 1,
        }));

        set({ chefs: [...realChefs, ...keptGhosts] });
    } catch { /* silent */ }
}

function setNormalInterval(
    set: (s: Partial<KitchenStoreState>) => void,
    get: () => KitchenStoreState,
) {
    if (_handle) clearInterval(_handle);
    _handle = setInterval(() => void fetchChefs(set, get), 3000);
}

export const useKitchenStore = create<KitchenStoreState>((set, get) => ({
    chefs: [],

    startPolling() {
        if (_handle) return;
        void fetchChefs(set, get);
        _handle = setInterval(() => void fetchChefs(set, get), 3000);
    },

    stopPolling() {
        if (_handle)     { clearInterval(_handle);  _handle     = null; }
        if (_fastHandle) { clearTimeout(_fastHandle); _fastHandle = null; }
    },

    triggerFastPoll(durationMs = 20_000) {
        void fetchChefs(set, get);
        if (_handle) { clearInterval(_handle); _handle = null; }
        _handle = setInterval(() => void fetchChefs(set, get), 1000);
        if (_fastHandle) clearTimeout(_fastHandle);
        _fastHandle = setTimeout(() => {
            setNormalInterval(set, get);
            _fastHandle = null;
        }, durationMs);
    },

    addStartingChef() {
        set(s => {
            const id = `__ghost__${Date.now()}`;
            const ghost: ChefData = {
                instanceId:       id,
                status:           KitchenState.Starting,
                position:         claimStation(id),
                cookingStartedAt: null,
                waitingOrders:    0,
                capacity:         0,
                ghost:            true,
            };
            return { chefs: [...s.chefs, ghost] };
        });
    },

    removeLastChef() {
        set(s => {
            // Find the last real (non-ghost) chef
            let lastRealIdx = -1;
            for (let i = s.chefs.length - 1; i >= 0; i--) {
                if (!s.chefs[i].ghost) { lastRealIdx = i; break; }
            }
            if (lastRealIdx === -1) return {};
            _pendingRemovals.add(s.chefs[lastRealIdx].instanceId);
            return { chefs: s.chefs.filter((_, i) => i !== lastRealIdx) };
        });
    },
}));
