import React from 'react'
import { FlowChart, KitchenChart, WaiterChart } from './ChartComponents'
import { useChartsHistory } from '../hooks/useChartsHistory'
import { StatsBody, SectionLabel, Divider } from './StatsWidgets'

// Import stores so they initialise (side-effects: subscriptions + Kafka polling)
import '../store/statsStore'

const panelCol: React.CSSProperties = {
    display: 'flex', flexDirection: 'column', gap: 8,
    background: 'rgba(255,255,255,0.04)',
    border: '1px solid rgba(255,255,255,0.08)',
    borderRadius: 12, padding: '14px 16px',
    overflowY: 'auto',
    minWidth: 0,
}

const CHART_LEGENDS = {
    flux:    [['Queue', '#a78bfa'], ['Waiting', '#34d399'], ['Eating', '#22d3ee']],
    cuisine: [['Backlog', '#f97316'], ['Cooking', '#34d399'], ['Load ×', '#fbbf24']],
    service: [['Idle', '#94a3b8'], ['Walking', '#fbbf24'], ['Serving', '#e879f9']],
} as const

function MiniLegend({ items }: { items: readonly [string, string][] }) {
    return (
        <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
            {items.map(([l, c]) => (
                <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
                    <div style={{ width: 14, height: 2, background: c, borderRadius: 1 }} />
                    <span style={{ fontSize: 9, opacity: 0.5 }}>{l}</span>
                </div>
            ))}
        </div>
    )
}

export default function StatsPanel() {
    const { flowHistory, kitchenHistory, waiterHistory } = useChartsHistory()

    return (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, minHeight: 0, overflow: 'hidden' }}>

            {/* ══ LEFT COLUMN: charts ══ */}
            <div style={panelCol}>
                <SectionLabel>Client flow · 60 s</SectionLabel>
                <FlowChart history={flowHistory} />
                <MiniLegend items={CHART_LEGENDS.flux} />
                <Divider />
                <SectionLabel>Kitchen · 60 s</SectionLabel>
                <KitchenChart history={kitchenHistory} />
                <MiniLegend items={CHART_LEGENDS.cuisine} />
                <Divider />
                <SectionLabel>Service · 60 s</SectionLabel>
                <WaiterChart history={waiterHistory} />
                <MiniLegend items={CHART_LEGENDS.service} />
            </div>

            {/* ══ COLONNE DROITE : chiffres ══ */}
            <div style={panelCol}>
                <StatsBody />
            </div>

        </div>
    )
}
