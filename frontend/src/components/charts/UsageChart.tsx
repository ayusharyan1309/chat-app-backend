import { useMemo } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts'
import { formatBytes, formatCount, formatNumber, formatShortDay } from '../../lib/format'
import type { UsagePoint } from '../../types'
import { ChartTooltipBox } from './ChartTooltip'
import { USAGE_METRICS, type UsageMetric } from './metrics'
import { useChartTheme } from './useChartTheme'

interface Row {
  day: string
  label: string
  value: number
}

function formatMetric(metric: UsageMetric, value: number): string {
  return metric === 'mediaBytes' ? formatBytes(value) : formatNumber(value)
}

/** Daily usage for one metric (single series; the chart title names it, so no legend). */
export function UsageChart({ points, metric, height = 220 }: { points: UsagePoint[]; metric: UsageMetric; height?: number }) {
  const theme = useChartTheme()
  const rows = useMemo<Row[]>(() => points.map((p) => ({ day: p.day, label: formatShortDay(p.day), value: p[metric] })), [points, metric])
  const metricLabel = USAGE_METRICS.find((m) => m.value === metric)?.label ?? metric

  const renderTooltip = (props: TooltipContentProps) => {
    if (!props.active || !props.payload?.length) return null
    const row = props.payload[0]?.payload as Row | undefined
    if (!row) return null
    return <ChartTooltipBox title={row.label} rows={[{ key: 'v', label: metricLabel, value: formatMetric(metric, row.value), color: theme.series1 }]} />
  }

  return (
    <div className="chart" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barCategoryGap={2}>
          <CartesianGrid vertical={false} stroke={theme.grid} />
          <XAxis dataKey="label" tick={{ fill: theme.axis, fontSize: 11 }} tickLine={false} axisLine={{ stroke: theme.grid }} interval="preserveStartEnd" minTickGap={28} />
          <YAxis
            allowDecimals={false}
            tick={{ fill: theme.axis, fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            width={52}
            tickFormatter={(v: number) => (metric === 'mediaBytes' ? formatBytes(v) : formatCount(v))}
          />
          <Tooltip content={renderTooltip} cursor={{ fill: theme.grid, opacity: 0.6 }} isAnimationActive={false} />
          <Bar dataKey="value" name={metricLabel} fill={theme.series1} radius={[4, 4, 0, 0]} maxBarSize={28} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
