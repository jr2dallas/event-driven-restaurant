import ReactECharts from 'echarts-for-react'
import { useStatsStore } from '../store/statsStore'
import { useEffect, useState } from 'react'

const card: React.CSSProperties = {
    background: 'rgba(255,255,255,0.05)',
    border: '1px solid rgba(255,255,255,0.1)',
    borderRadius: '12px',
    padding: '16px',
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
}

const label: React.CSSProperties = {
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '1px',
    opacity: 0.5,
}

const bigNumber: React.CSSProperties = {
    fontSize: '42px',
    fontWeight: 'bold',
    lineHeight: 1,
}

const badge = (color: string): React.CSSProperties => ({
    padding: '2px 10px',
    borderRadius: '999px',
    fontSize: '11px',
    fontWeight: 600,
    background: `${color}33`,
    color,
    alignSelf: 'flex-start',
})

const MAX_HISTORY = 30

function gaugeOption(value: number, max: number, color: string) {
    return {
        series: [{
            type: 'gauge',
            startAngle: 200,
            endAngle: -20,
            min: 0,
            max,
            progress: { show: true, width: 10, itemStyle: { color } },
            axisLine: { lineStyle: { width: 10, color: [[1, 'rgba(255,255,255,0.08)']] } },
            axisTick: { show: false },
            splitLine: { show: false },
            axisLabel: { show: false },
            pointer: { show: false },
            detail: {
                valueAnimation: true,
                formatter: '{value}',
                color: 'white',
                fontSize: 28,
                fontWeight: 'bold',
                offsetCenter: [0, '10%'],
            },
            data: [{ value }],
        }],
    }
}

function sparklineOption(data: number[], color: string) {
    return {
        grid: { top: 4, bottom: 4, left: 4, right: 4 },
        xAxis: { show: false, type: 'category', data: data.map((_, i) => i) },
        yAxis: { show: false, type: 'value' },
        series: [{
            type: 'bar',
            data,
            itemStyle: { color },
            barMaxWidth: 8,
        }],
    }
}

export default function RightPanel() {
    const s = useStatsStore()
    const [orderHistory, setOrderHistory] = useState<number[]>(Array(MAX_HISTORY).fill(0))

    useEffect(() => {
        const tick = setInterval(() => {
            const { waiting } = useStatsStore.getState()
            setOrderHistory(prev => [...prev.slice(-MAX_HISTORY), waiting])
        }, 1000)
        return () => clearInterval(tick)
    }, [])

    const occupancyPct = s.totalSeats > 0
        ? Math.round((s.usedSeats / s.totalSeats) * 100)
        : 0

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', overflowY: 'auto' }}>

            {/* Kitchen backlog */}
            <div style={card}>
                <span style={label}>Kitchen Backlog</span>
                <span style={{ fontSize: '11px', opacity: 0.6 }}>Orders in kitchen</span>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ ...bigNumber, color: '#f97316' }}>{s.waiting}</span>
                    <span style={badge('#f97316')}>Orders</span>
                </div>
                <ReactECharts
                    option={sparklineOption(orderHistory, '#f97316')}
                    style={{ height: '60px' }}
                    opts={{ renderer: 'canvas' }}
                />
            </div>

            {/* Waiters gauge */}
            <div style={card}>
                <span style={label}>Waiters</span>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={badge('#22c55e')}>Active</span>
                    <span style={{ fontSize: '11px', opacity: 0.6 }}>{s.waitersOnFloor} floor · {s.waitersIdle} idle</span>
                </div>
                <ReactECharts
                    option={gaugeOption(s.waitersTotal, 10, '#22c55e')}
                    style={{ height: '140px' }}
                    opts={{ renderer: 'canvas' }}
                />
            </div>

            {/* Ready to deliver */}
            <div style={card}>
                <span style={label}>Ready to Deliver</span>
                <span style={{ ...bigNumber, color: '#a78bfa' }}>{s.ordersReady}</span>
                <span style={{ fontSize: '12px', opacity: 0.5 }}>dishes on the way to tables</span>
            </div>

            {/* Table occupancy */}
            <div style={{
                ...card,
                border: s.isFull ? '1px solid #ef444488' : '1px solid rgba(255,255,255,0.1)',
            }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={label}>Table Occupancy</span>
                    {s.isFull && <span style={badge('#ef4444')}>FULL</span>}
                </div>
                <span style={{ ...bigNumber, color: s.isFull ? '#ef4444' : '#22c55e' }}>
                    {s.usedSeats}/{s.totalSeats}
                </span>
                <ReactECharts
                    option={gaugeOption(occupancyPct, 100, s.isFull ? '#ef4444' : '#22c55e')}
                    style={{ height: '140px' }}
                    opts={{ renderer: 'canvas' }}
                />
            </div>

        </div>
    )
}
