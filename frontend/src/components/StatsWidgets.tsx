import React from 'react'
import { useStatsStore } from '../store/statsStore'

// ── Atoms ──────────────────────────────────────────────────────────────────

export function StatRow({ label, count, color, large }: { label: string; count: number; color: string; large?: boolean }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <div style={{ width: 8, height: 8, borderRadius: '50%', background: color, flexShrink: 0 }} />
                <span style={{ fontSize: large ? 13 : 12, opacity: 0.75 }}>{label}</span>
            </div>
            <span style={{ fontSize: large ? 22 : 18, fontWeight: 'bold', color, minWidth: 28, textAlign: 'right', lineHeight: 1 }}>
                {count}
            </span>
        </div>
    )
}

export function AvgRow({ label, value, unit = 's', large }: { label: string; value: string | number | null; unit?: string; large?: boolean }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
            <span style={{ fontSize: large ? 12 : 11, opacity: 0.6 }}>{label}</span>
            <span style={{ fontSize: large ? 15 : 13, fontWeight: 'bold', color: '#facc15' }}>
                {value === null ? '—' : `${value}${unit}`}
            </span>
        </div>
    )
}

export function LagRow({ topic, lag, large }: { topic: string; lag: number | undefined; large?: boolean }) {
    const color = lag === undefined ? '#6b7280'
                : lag === 0        ? '#34d399'
                : lag < 3          ? '#facc15'
                :                    '#f87171'
    return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
            <span style={{ fontSize: large ? 11 : 10, opacity: 0.55, fontFamily: 'monospace' }}>{topic}</span>
            <span style={{ fontSize: large ? 15 : 13, fontWeight: 'bold', color }}>
                {lag === undefined ? '—' : lag}
            </span>
        </div>
    )
}

export function Divider() {
    return <div style={{ height: 1, background: 'rgba(255,255,255,0.08)', margin: '6px 0' }} />
}

export function SectionLabel({ children, large }: { children: string; large?: boolean }) {
    return (
        <div style={{ fontSize: large ? 11 : 10, textTransform: 'uppercase', letterSpacing: 1, opacity: 0.4, marginBottom: 2 }}>
            {children}
        </div>
    )
}

export function OccupancyBar({ used, total, large }: { used: number; total: number; large?: boolean }) {
    const pct   = total > 0 ? used / total : 0
    const color = pct >= 1 ? '#f87171' : pct >= 0.8 ? '#facc15' : '#34d399'
    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: large ? 12 : 11, opacity: 0.6 }}>Occupation</span>
                <span style={{ fontSize: large ? 15 : 13, fontWeight: 'bold', color }}>
                    {used}/{total} <span style={{ fontSize: large ? 11 : 10, opacity: 0.7 }}>({Math.round(pct * 100)}%)</span>
                </span>
            </div>
            <div style={{ height: large ? 6 : 4, borderRadius: 2, background: 'rgba(255,255,255,0.1)', overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${pct * 100}%`, background: color, borderRadius: 2, transition: 'width 0.4s' }} />
            </div>
        </div>
    )
}

export function StaffRow({ label, total, sub, large }: { label: string; total: number; sub: string; large?: boolean }) {
    return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
            <span style={{ fontSize: large ? 12 : 11, opacity: 0.6 }}>{label}</span>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
                <span style={{ fontSize: large ? 20 : 16, fontWeight: 'bold' }}>{total}</span>
                <span style={{ fontSize: large ? 11 : 10, opacity: 0.5 }}>{sub}</span>
            </div>
        </div>
    )
}

// ── Composite: all stat numbers ────────────────────────────────────────────

const MAX_SAMPLES = 20

export function StatsBody({ large }: { large?: boolean }) {
    const s = useStatsStore()

    return (
        <>
            <SectionLabel large={large}>Active clients</SectionLabel>
            <div style={{ fontSize: large ? 38 : 28, fontWeight: 'bold', lineHeight: 1 }}>{s.total}</div>

            <Divider />

            <StatRow large={large} label="Queue"            count={s.queue}             color="#a78bfa" />
            <StatRow large={large} label="Awaiting service" count={s.seated + s.waiting} color="#34d399" />
            <StatRow large={large} label="Eating"           count={s.eating}             color="#22d3ee" />
            <StatRow large={large} label="Leaving"          count={s.leaving}            color="#94a3b8" />

            <Divider />

            <SectionLabel large={large}>Capacity</SectionLabel>
            <OccupancyBar large={large} used={s.usedSeats} total={s.totalSeats} />
            {s.isFull && (
                <div style={{
                    textAlign: 'center', fontSize: large ? 11 : 10, fontWeight: 'bold',
                    background: 'rgba(248,113,113,0.15)', color: '#f87171',
                    borderRadius: 4, padding: '2px 0', letterSpacing: 1,
                }}>
                    FULL
                </div>
            )}

            <Divider />

            <SectionLabel large={large}>Staff</SectionLabel>
            <StaffRow large={large}
                label="Waiters"
                total={s.waitersTotal}
                sub={`${s.waitersOnFloor} floor · ${s.waitersIdle} idle`}
            />
            <StaffRow large={large}
                label="Chefs"
                total={s.chefsTotal}
                sub={`${s.chefsCooking} cooking · ${s.chefsIdle} idle${s.chefsUnreach > 0 ? ` · ${s.chefsUnreach} ✕` : ''}`}
            />
            <AvgRow large={large} label="Kitchen pressure" value={s.kitchenLoad} unit="x" />

            <Divider />

            <SectionLabel large={large}>Throughput (60 s)</SectionLabel>
            <AvgRow large={large} label="Clients / min" value={s.cliPerMin} unit="/min" />
            <AvgRow large={large} label="Dishes / min"  value={s.dshPerMin} unit="/min" />
            <AvgRow large={large} label="Peak queue"    value={s.peakQueue} unit=""     />

            <Divider />

            <SectionLabel large={large}>Avg time (last {MAX_SAMPLES})</SectionLabel>
            <AvgRow large={large} label="Order wait" value={s.avgOrder} />
            <AvgRow large={large} label="Dish wait"  value={s.avgDish}  />

            <Divider />

            <SectionLabel large={large}>Kitchen queue</SectionLabel>
            <LagRow large={large} topic="In kitchen"       lag={s.ordersInKitchen} />
            <LagRow large={large} topic="Ready / delivery" lag={s.ordersReady}     />
        </>
    )
}
