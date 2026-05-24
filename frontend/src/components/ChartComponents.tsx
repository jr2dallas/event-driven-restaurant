import ReactECharts from 'echarts-for-react'

export function FlowChart({ history, height = 90 }: {
    history: { queue: number; waiting: number; eating: number }[]
    height?: number | string
}) {
    const option = {
        animation: false,
        grid: { top: 8, bottom: 16, left: 28, right: 8 },
        xAxis: {
            type: 'category',
            data: history.map((_, i) => i),
            axisLine: { lineStyle: { color: 'rgba(255,255,255,0.1)' } },
            axisLabel: { show: false },
            axisTick: { show: false },
        },
        yAxis: {
            type: 'value',
            axisLabel: { color: 'rgba(255,255,255,0.3)', fontSize: 9 },
            splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
            axisLine: { show: false },
            axisTick: { show: false },
            minInterval: 1,
        },
        series: [
            {
                name: 'File',
                type: 'line',
                data: history.map(h => h.queue),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#a78bfa', width: 1.5 },
                areaStyle: { color: '#a78bfa18' },
            },
            {
                name: 'Waiting',
                type: 'line',
                data: history.map(h => h.waiting),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#34d399', width: 1.5 },
                areaStyle: { color: '#34d39918' },
            },
            {
                name: 'Eating',
                type: 'line',
                data: history.map(h => h.eating),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#22d3ee', width: 1.5 },
                areaStyle: { color: '#22d3ee18' },
            },
        ],
        tooltip: {
            trigger: 'axis',
            backgroundColor: 'rgba(15,23,42,0.9)',
            borderColor: 'rgba(255,255,255,0.1)',
            textStyle: { color: '#fff', fontSize: 11 },
            axisPointer: { lineStyle: { color: 'rgba(255,255,255,0.2)' } },
        },
    }
    return <ReactECharts option={option} style={{ height }} opts={{ renderer: 'canvas' }} />
}

export function KitchenChart({ history, height = 90 }: {
    history: { waiting: number; cooking: number; load: number }[]
    height?: number | string
}) {
    const option = {
        animation: false,
        grid: { top: 8, bottom: 16, left: 28, right: 36 },
        xAxis: {
            type: 'category',
            data: history.map((_, i) => i),
            axisLine: { lineStyle: { color: 'rgba(255,255,255,0.1)' } },
            axisLabel: { show: false },
            axisTick: { show: false },
        },
        yAxis: [
            {
                type: 'value',
                axisLabel: { color: 'rgba(255,255,255,0.3)', fontSize: 9 },
                splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
                axisLine: { show: false }, axisTick: { show: false },
                minInterval: 1, min: 0,
            },
            {
                type: 'value',
                axisLabel: { color: 'rgba(255,255,255,0.2)', fontSize: 9 },
                splitLine: { show: false },
                axisLine: { show: false }, axisTick: { show: false },
                min: 0, max: 10,
            },
        ],
        series: [
            {
                name: 'Backlog',
                type: 'line', yAxisIndex: 0,
                data: history.map(h => h.waiting),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#f97316', width: 1.5 },
                areaStyle: { color: '#f9731618' },
            },
            {
                name: 'Cooking',
                type: 'line', yAxisIndex: 0,
                data: history.map(h => h.cooking),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#34d399', width: 1.5 },
                areaStyle: { color: '#34d39918' },
            },
            {
                name: 'Load',
                type: 'line', yAxisIndex: 1,
                data: history.map(h => h.load),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#fbbf24', width: 1.5, type: 'dashed' as const },
            },
        ],
        tooltip: {
            trigger: 'axis',
            backgroundColor: 'rgba(15,23,42,0.9)',
            borderColor: 'rgba(255,255,255,0.1)',
            textStyle: { color: '#fff', fontSize: 11 },
            axisPointer: { lineStyle: { color: 'rgba(255,255,255,0.2)' } },
            formatter: (params: any[]) => params
                .map(p => `${p.marker}${p.seriesName}: <b>${p.value}</b>${p.seriesIndex === 2 ? 'x' : ''}`)
                .join('<br/>'),
        },
    }
    return <ReactECharts option={option} style={{ height }} opts={{ renderer: 'canvas' }} />
}

export function WaiterChart({ history, height = 90 }: {
    history: { idle: number; walking: number; serving: number }[]
    height?: number | string
}) {
    const option = {
        animation: false,
        grid: { top: 8, bottom: 16, left: 28, right: 8 },
        xAxis: {
            type: 'category',
            data: history.map((_, i) => i),
            axisLine: { lineStyle: { color: 'rgba(255,255,255,0.1)' } },
            axisLabel: { show: false },
            axisTick: { show: false },
        },
        yAxis: {
            type: 'value',
            axisLabel: { color: 'rgba(255,255,255,0.3)', fontSize: 9 },
            splitLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
            axisLine: { show: false }, axisTick: { show: false },
            minInterval: 1, min: 0,
        },
        series: [
            {
                name: 'Idle',
                type: 'line',
                data: history.map(h => h.idle),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#94a3b8', width: 1.5 },
                areaStyle: { color: '#94a3b818' },
            },
            {
                name: 'Walking',
                type: 'line',
                data: history.map(h => h.walking),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#fbbf24', width: 1.5 },
                areaStyle: { color: '#fbbf2418' },
            },
            {
                name: 'Serving',
                type: 'line',
                data: history.map(h => h.serving),
                smooth: true, symbol: 'none',
                lineStyle: { color: '#e879f9', width: 1.5 },
                areaStyle: { color: '#e879f918' },
            },
        ],
        tooltip: {
            trigger: 'axis',
            backgroundColor: 'rgba(15,23,42,0.9)',
            borderColor: 'rgba(255,255,255,0.1)',
            textStyle: { color: '#fff', fontSize: 11 },
            axisPointer: { lineStyle: { color: 'rgba(255,255,255,0.2)' } },
        },
    }
    return <ReactECharts option={option} style={{ height }} opts={{ renderer: 'canvas' }} />
}
