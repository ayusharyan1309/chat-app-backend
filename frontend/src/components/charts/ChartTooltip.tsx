export interface TooltipRow {
  key: string
  label: string
  value: string
  color: string
}

/** Tooltip body shared by charts. Text stays in ink tokens; the swatch carries series identity. */
export function ChartTooltipBox({ title, rows }: { title: string; rows: TooltipRow[] }) {
  return (
    <div className="chart-tooltip">
      <p className="chart-tooltip__title">{title}</p>
      {rows.map((row) => (
        <p className="chart-tooltip__row" key={row.key}>
          <span className="chart-tooltip__swatch" style={{ background: row.color }} aria-hidden />
          <span className="chart-tooltip__label">{row.label}</span>
          <span className="chart-tooltip__value">{row.value}</span>
        </p>
      ))}
    </div>
  )
}
