import React from 'react'
import { useChartsHistory } from '../hooks/useChartsHistory'
import { FlowChart, KitchenChart, WaiterChart } from './ChartComponents'
import { StatsBody, SectionLabel, Divider } from './StatsWidgets'

interface Props { onClose: () => void }

const CHART_LEGENDS = {
    flux:    [['Queue', '#a78bfa'], ['Waiting', '#34d399'], ['Eating', '#22d3ee']],
    cuisine: [['Backlog', '#f97316'], ['Cooking', '#34d399'], ['Load ×', '#fbbf24']],
    service: [['Idle', '#94a3b8'], ['Walking', '#fbbf24'], ['Serving', '#e879f9']],
} as const

function Legend({ items }: { items: readonly [string, string][] }) {
    return (
        <div style={{ display: 'flex', gap: 14, justifyContent: 'center', marginTop: 8 }}>
            {items.map(([l, c]) => (
                <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <div style={{ width: 18, height: 2, background: c, borderRadius: 1 }} />
                    <span style={{ fontSize: 11, opacity: 0.55, color: '#fff' }}>{l}</span>
                </div>
            ))}
        </div>
    )
}

function ChartCard({ title, legend, children }: {
    title: string
    legend: readonly [string, string][]
    children: React.ReactNode
}) {
    return (
        <div style={{
            background: 'rgba(255,255,255,0.04)',
            border: '1px solid rgba(255,255,255,0.1)',
            borderRadius: 14,
            padding: '16px 20px 14px',
            display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0,
        }}>
            <SectionLabel large>{title} · 60 s</SectionLabel>
            <div style={{ flex: 1, minHeight: 0 }}>{children}</div>
            <Legend items={legend} />
        </div>
    )
}

export default function ChartsModal({ onClose }: Props) {
    const { flowHistory, kitchenHistory, waiterHistory } = useChartsHistory()

    return (
        <div
            style={{
                position: 'fixed', inset: 0, zIndex: 100,
                background: 'rgba(10,5,20,0.94)',
                backdropFilter: 'blur(8px)',
                display: 'flex', flexDirection: 'column',
                padding: '20px 24px',
                gap: 16,
                color: '#fff',
            }}
            onClick={onClose}
        >
            {/* ── Header ── */}
            <div
                style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0 }}
                onClick={e => e.stopPropagation()}
            >
                <span style={{ fontSize: 15, fontWeight: 'bold', letterSpacing: 1, opacity: 0.8 }}>
                    Dashboard · live
                </span>
                <button
                    onClick={onClose}
                    style={{
                        background: 'rgba(255,255,255,0.08)',
                        border: '1px solid rgba(255,255,255,0.15)',
                        borderRadius: 8, color: '#fff',
                        padding: '6px 16px', fontSize: 13, cursor: 'pointer',
                    }}
                >
                    ✕ Close
                </button>
            </div>

            {/* ── Body : charts (gauche) + stats (droite) ── */}
            <div
                style={{ display: 'flex', gap: 20, flex: 1, minHeight: 0 }}
                onClick={e => e.stopPropagation()}
            >
                {/* Charts — 3 colonnes côte à côte */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: '0 0 62%', minHeight: 0 }}>
                    <ChartCard title="Client flow" legend={CHART_LEGENDS.flux}>
                        <FlowChart history={flowHistory} height="100%" />
                    </ChartCard>
                    <ChartCard title="Kitchen" legend={CHART_LEGENDS.cuisine}>
                        <KitchenChart history={kitchenHistory} height="100%" />
                    </ChartCard>
                    <ChartCard title="Service" legend={CHART_LEGENDS.service}>
                        <WaiterChart history={waiterHistory} height="100%" />
                    </ChartCard>
                </div>

                {/* Stats — colonne droite scrollable */}
                <div style={{
                    flex: 1, minWidth: 0,
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: 14,
                    padding: '20px 22px',
                    display: 'flex', flexDirection: 'column', gap: 8,
                    overflowY: 'auto',
                }}>
                    <StatsBody large />
                </div>
            </div>
        </div>
    )
}
