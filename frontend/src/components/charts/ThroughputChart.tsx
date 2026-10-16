import { useMemo } from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts'
import { formatNumber, formatTime } from '../../lib/format'
import type { ThroughputPoint } from '../../types'
import { ChartTooltipBox } from './ChartTooltip'
import { useChartTheme } from './useChartTheme'

interface Row {
  t: number
  label: string
  messages: number
  deliveries: number
}

function toThroughputRows(points: ThroughputPoint[]): Row[] {
  return points.map((p) => {
    const t = Date.parse(p.minute)
    return { t, label: Number.isNaN(t) ? p.minute : formatTime(t), messages: p.messages, deliveries: p.deliveries }
  })
}

/** Messages and deliveries per minute. Two series, one axis (same unit). */
export function ThroughputChart({ points, height = 240 }: { points: ThroughputPoint[]; height?: number }) {
  const theme = useChartTheme()
  const rows = useMemo(() => toThroughputRows(points), [points])

  const renderTooltip = (props: TooltipContentProps) => {
    if (!props.active || !props.payload?.length) return null
    const row = props.payload[0]?.payload as Row | undefined
    if (!row) return null
    return (
      <ChartTooltipBox
        title={row.label}
        rows={[
          { key: 'm', label: 'Messages', value: formatNumber(row.messages), color: theme.series1 },
          { key: 'd', label: 'Deliveries', value: formatNumber(row.deliveries), color: theme.series2 },
        ]}
      />
    )
  }

  return (
    <div className="chart" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke={theme.grid} strokeWidth={1} />
          <XAxis
            dataKey="label"
            tick={{ fill: theme.axis, fontSize: 11 }}
            tickLine={false}
            axisLine={{ stroke: theme.grid }}
            interval="preserveStartEnd"
            minTickGap={36}
          />
          <YAxis
            allowDecimals={false}
            tick={{ fill: theme.axis, fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            width={44}
            tickFormatter={(v: number) => formatNumber(v)}
          />
          <Tooltip content={renderTooltip} cursor={{ stroke: theme.axis, strokeWidth: 1, strokeDasharray: '3 3' }} isAnimationActive={false} />
          <Line
            type="monotone"
            dataKey="deliveries"
            name="Deliveries"
            stroke={theme.series2}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, strokeWidth: 2, stroke: theme.surface, fill: theme.series2 }}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="messages"
            name="Messages"
            stroke={theme.series1}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, strokeWidth: 2, stroke: theme.surface, fill: theme.series1 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
