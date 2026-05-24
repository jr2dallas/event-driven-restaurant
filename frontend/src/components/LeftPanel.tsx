import { useChartsHistory } from '../hooks/useChartsHistory'
import { FlowChart, KitchenChart, WaiterChart } from './ChartComponents'

const card: React.CSSProperties = {
    background: 'rgba(255,255,255,0.05)',
    border: '1px solid rgba(255,255,255,0.1)',
    borderRadius: '12px',
    padding: '12px 14px',
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
    flex: 1,
    minHeight: 0,
}

const sectionLabel: React.CSSProperties = {
    fontSize: '10px',
    textTransform: 'uppercase',
    letterSpacing: '1px',
    opacity: 0.45,
    flexShrink: 0,
}

function MiniLegend({ items }: { items: [string, string][] }) {
    return (
        <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexShrink: 0 }}>
            {items.map(([l, c]) => (
                <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
                    <div style={{ width: 14, height: 2, background: c, borderRadius: 1 }} />
                    <span style={{ fontSize: 10, opacity: 0.5, color: '#fff' }}>{l}</span>
                </div>
            ))}
        </div>
    )
}

export default function LeftPanel() {
    const { flowHistory, kitchenHistory, waiterHistory } = useChartsHistory()

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: '10px' }}>

            <div style={card}>
                <span style={sectionLabel}>Client Flow · 60 s</span>
                <div style={{ flex: 1, minHeight: 0 }}>
                    <FlowChart history={flowHistory} height="100%" />
                </div>
                <MiniLegend items={[['Queue', '#a78bfa'], ['Waiting', '#34d399'], ['Eating', '#22d3ee']]} />
            </div>

            <div style={card}>
                <span style={sectionLabel}>Kitchen · 60 s</span>
                <div style={{ flex: 1, minHeight: 0 }}>
                    <KitchenChart history={kitchenHistory} height="100%" />
                </div>
                <MiniLegend items={[['Backlog', '#f97316'], ['Cooking', '#34d399'], ['Load ×', '#fbbf24']]} />
            </div>

            <div style={card}>
                <span style={sectionLabel}>Waiters · 60 s</span>
                <div style={{ flex: 1, minHeight: 0 }}>
                    <WaiterChart history={waiterHistory} height="100%" />
                </div>
                <MiniLegend items={[['Idle', '#94a3b8'], ['Walking', '#fbbf24'], ['Serving', '#e879f9']]} />
            </div>

        </div>
    )
}
