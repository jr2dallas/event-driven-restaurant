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
}

const stations: Position[] = ((layoutData as any).kitchen_station ?? []).map((s: any) => ({
    x: s.x,
    y: s.y,
}));

interface KitchenStoreState {
    chefs:           ChefData[];
    startPolling:    () => void;
    stopPolling:     () => void;
    triggerFastPoll: (durationMs?: number) => void;
}

let _handle:     ReturnType<typeof setInterval> | null = null;
let _fastHandle: ReturnType<typeof setTimeout>  | null = null;

async function fetchChefs(set: (s: Partial<KitchenStoreState>) => void) {
    try {
        const res = await fetch('/internal/kitchen/instances');
        if (!res.ok) return;
        const instances: KitchenInstance[] = await res.json();
        const chefs: ChefData[] = instances.map((inst, i) => ({
            instanceId:       inst.instanceId,
            status:           inst.status as KitchenState,
            position:         stations[i] ?? { x: 1034, y: 450 },
            cookingStartedAt: inst.cookingStartedAt ?? null,
            waitingOrders:    inst.waitingOrders ?? 0,
        }));
        set({ chefs });
    } catch { /* silent */ }
}

function setNormalInterval(set: (s: Partial<KitchenStoreState>) => void) {
    if (_handle) clearInterval(_handle);
    _handle = setInterval(() => void fetchChefs(set), 3000);
}

export const useKitchenStore = create<KitchenStoreState>((set) => ({
    chefs: [],

    startPolling() {
        if (_handle) return;
        void fetchChefs(set);
        _handle = setInterval(() => void fetchChefs(set), 3000);
    },

    stopPolling() {
        if (_handle)     { clearInterval(_handle);  _handle     = null; }
        if (_fastHandle) { clearTimeout(_fastHandle); _fastHandle = null; }
    },

    triggerFastPoll(durationMs = 20_000) {
        void fetchChefs(set);
        if (_handle) { clearInterval(_handle); _handle = null; }
        _handle = setInterval(() => void fetchChefs(set), 1000);
        if (_fastHandle) clearTimeout(_fastHandle);
        _fastHandle = setTimeout(() => {
            setNormalInterval(set);
            _fastHandle = null;
        }, durationMs);
    },
}));
