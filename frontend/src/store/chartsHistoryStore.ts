import { create } from 'zustand'
import { SessionState } from '../api/generated/model/session-state'
import { WaiterState } from '../api/generated/model/waiter-state'
import { useKitchenStore } from './kitchenStore'
import { KitchenState } from '../api/generated/model/kitchen-state';
import { useRestaurantSyncStore } from './restaurantSyncStore'

const HISTORY_LEN = 60
const QUEUE_STATES = [SessionState.Queuing, SessionState.AtEntrance, SessionState.WalkingToSeat]

interface ChartsHistoryState {
    flowHistory:    { queue: number; waiting: number; eating: number }[]
    kitchenHistory: { waiting: number; cooking: number; load: number }[]
    waiterHistory:  { idle: number; walking: number; serving: number }[]
}

export const useChartsHistoryStore = create<ChartsHistoryState>(() => ({
    flowHistory:    Array(HISTORY_LEN).fill({ queue: 0, waiting: 0, eating: 0 }),
    kitchenHistory: Array(HISTORY_LEN).fill({ waiting: 0, cooking: 0, load: 0 }),
    waiterHistory:  Array(HISTORY_LEN).fill({ idle: 0, walking: 0, serving: 0 }),
}))

setInterval(() => {
    const s = useRestaurantSyncStore.getState().lastState?.sessions ?? []
    const q = s.filter(x => QUEUE_STATES.includes(x.state)).length
    const w = s.filter(x => x.state === SessionState.WaitingOrder).length
    const e = s.filter(x => x.state === SessionState.Eating).length

    const cks  = useKitchenStore.getState().chefs.filter(c => c.status === KitchenState.Cooking).length
    const load = cks > 0 ? parseFloat((w / cks).toFixed(1)) : w > 0 ? 10 : 0

    const ws       = useRestaurantSyncStore.getState().lastState?.waiters ?? []
    const wIdle    = ws.filter(x => x.state === WaiterState.Idle).length
    const wWalking = ws.filter(x => x.state === WaiterState.WalkingToClient || x.state === WaiterState.WalkingToKitchen).length
    const wServing = ws.filter(x => x.state === WaiterState.TakingOrder || x.state === WaiterState.Delivering).length

    useChartsHistoryStore.setState(prev => ({
        flowHistory:    [...prev.flowHistory.slice(-(HISTORY_LEN - 1)),    { queue: q, waiting: w, eating: e }],
        kitchenHistory: [...prev.kitchenHistory.slice(-(HISTORY_LEN - 1)), { waiting: w, cooking: cks, load }],
        waiterHistory:  [...prev.waiterHistory.slice(-(HISTORY_LEN - 1)),  { idle: wIdle, walking: wWalking, serving: wServing }],
    }))
}, 1000)
