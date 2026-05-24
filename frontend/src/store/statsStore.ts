import { create } from 'zustand'
import { useRestaurantSyncStore } from './restaurantSyncStore'
import { useKitchenStore } from './kitchenStore'
import { KitchenState } from '../api/generated/model/kitchen-state';

import { SessionState } from '../api/generated/model/session-state'
import { WaiterState } from '../api/generated/model/waiter-state'

const MAX_SAMPLES    = 20
const RATE_WINDOW_MS = 60_000
export const QUEUE_STATES = [SessionState.Queuing, SessionState.AtEntrance, SessionState.WalkingToSeat]

interface TimingEntry { seatedAt?: number; waitingOrderAt?: number }

const timing       = new Map<string, TimingEntry>()
const seenSessions = new Set<string>()
let orderWaits:  number[] = []
let dishWaits:   number[] = []
let clientArrTs: number[] = []
let dishServedTs: number[] = []
let peakQueue    = 0

export interface StatsState {
    queue:          number
    seated:         number
    waiting:        number
    eating:         number
    leaving:        number
    total:          number
    usedSeats:      number
    totalSeats:     number
    isFull:         boolean
    waitersTotal:   number
    waitersOnFloor: number
    waitersIdle:    number
    chefsTotal:     number
    chefsCooking:   number
    chefsIdle:      number
    chefsUnreach:   number
    avgOrder:       number | null
    avgDish:        number | null
    cliPerMin:      string
    dshPerMin:      string
    peakQueue:      number
    kitchenLoad:      string
    ordersInKitchen:  number
    ordersReady:      number
}

function rollingAvg(samples: number[]): number | null {
    if (!samples.length) return null
    return Math.round(samples.reduce((a, b) => a + b, 0) / samples.length / 1000)
}

function ratePerMin(ts: number[], now: number): string {
    let i = 0
    while (i < ts.length && now - ts[i] > RATE_WINDOW_MS) i++
    if (i > 0) ts.splice(0, i)
    return (ts.length / (RATE_WINDOW_MS / 60_000)).toFixed(1)
}

export const useStatsStore = create<StatsState>(() => ({
    queue: 0, seated: 0, waiting: 0, eating: 0, leaving: 0, total: 0,
    usedSeats: 0, totalSeats: 0, isFull: false,
    waitersTotal: 0, waitersOnFloor: 0, waitersIdle: 0,
    chefsTotal: 0, chefsCooking: 0, chefsIdle: 0, chefsUnreach: 0,
    avgOrder: null, avgDish: null,
    cliPerMin: '0.0', dshPerMin: '0.0', peakQueue: 0,
    kitchenLoad: '0',
    ordersInKitchen: 0,
    ordersReady: 0,
}))

useRestaurantSyncStore.subscribe(state => {
    const sessions = state.lastState?.sessions ?? []
    const now = Date.now()

    for (const s of sessions) {
        if (!seenSessions.has(s.id)) {
            seenSessions.add(s.id)
            clientArrTs.push(now)
        }
        const t = timing.get(s.id) ?? {}
        if (s.state === SessionState.Seated && t.seatedAt == null)
            timing.set(s.id, { ...t, seatedAt: now })

        if (s.state === SessionState.WaitingOrder && t.waitingOrderAt == null) {
            if (t.seatedAt != null)
                orderWaits = [...orderWaits.slice(-(MAX_SAMPLES - 1)), now - t.seatedAt]
            timing.set(s.id, { ...t, waitingOrderAt: now })
        }
        if (s.state === SessionState.Eating && t.waitingOrderAt != null) {
            dishWaits = [...dishWaits.slice(-(MAX_SAMPLES - 1)), now - t.waitingOrderAt]
            dishServedTs.push(now)
            timing.set(s.id, { seatedAt: t.seatedAt, waitingOrderAt: undefined })
        }
    }

    const ids = new Set(sessions.map(s => s.id))
    for (const id of timing.keys())
        if (!ids.has(id)) { timing.delete(id); seenSessions.delete(id) }

    const queue   = sessions.filter(s => QUEUE_STATES.includes(s.state)).length
    const seated  = sessions.filter(s => s.state === SessionState.Seated).length
    const waiting = sessions.filter(s => s.state === SessionState.WaitingOrder).length
    const eating  = sessions.filter(s => s.state === SessionState.Eating).length
    const leaving = sessions.filter(s => s.state === SessionState.Leaving).length
    const total   = sessions.filter(s => s.state !== SessionState.Despawned).length

    peakQueue = Math.max(peakQueue, queue)

    const lastState  = state.lastState
    const totalSeats = lastState?.seats.length ?? 0
    const freeSeats  = lastState?.availableSeats ?? 0
    const isFull     = lastState?.full ?? false
    const waiters    = lastState?.waiters ?? []
    const waitersIdle    = waiters.filter(w => w.state === WaiterState.Idle).length
    const waitersOnFloor = waiters.length - waitersIdle

    const chefs           = useKitchenStore.getState().chefs
    const chefsCooking    = chefs.filter(c => c.status === KitchenState.Cooking).length
    const chefsIdle       = chefs.filter(c => c.status === KitchenState.Idle).length
    const chefsUnreach    = chefs.filter(c => c.status === KitchenState.Unreachable).length
    const kitchenLoad     = chefsCooking > 0
        ? (waiting / chefsCooking).toFixed(1)
        : waiting > 0 ? '∞' : '0'
    const ordersInKitchen = chefs.reduce((sum, c) => sum + c.waitingOrders, 0)
    const ordersReady     = waiters.filter(w => w.state === WaiterState.Delivering).length

    useStatsStore.setState({
        queue, seated, waiting, eating, leaving, total,
        usedSeats: totalSeats - freeSeats, totalSeats, isFull,
        waitersTotal: waiters.length, waitersOnFloor, waitersIdle,
        chefsTotal: chefs.length, chefsCooking, chefsIdle, chefsUnreach,
        avgOrder: rollingAvg(orderWaits),
        avgDish:  rollingAvg(dishWaits),
        cliPerMin: ratePerMin(clientArrTs, now),
        dshPerMin: ratePerMin(dishServedTs, now),
        peakQueue,
        kitchenLoad,
        ordersInKitchen,
        ordersReady,
    })
})

