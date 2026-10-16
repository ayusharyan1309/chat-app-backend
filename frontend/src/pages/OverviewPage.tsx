import { RefreshCw } from 'lucide-react'
import { useEffect, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { systemApi } from '../api/admin'
import { ThroughputChart } from '../components/charts/ThroughputChart'
import { useChartTheme } from '../components/charts/useChartTheme'
import { PatchBay } from '../components/connectors/PatchBay'
import { PageHeader } from '../components/layout/PageHeader'
import { IconButton } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Badge, EmptyState, LoadError, Skeleton, Spinner } from '../components/ui/Feedback'
import { useNow } from '../hooks/misc'
import { useApi } from '../hooks/useApi'
import { formatCount, formatDateTime, formatDuration, formatNumber, formatRelative, formatTime } from '../lib/format'
import { useSystem } from '../state/system'
import type { SystemOverview, ThroughputPoint } from '../types'

const REFRESH_MS = 10_000

function Totals({ overview }: { overview: SystemOverview | undefined }) {
  const t = overview?.totals
  const items: Array<{ key: string; label: string; value: string; sub?: string; to?: string }> = [
    {
      key: 'tenants',
      label: 'Tenants',
      value: t ? formatNumber(t.tenants) : '',
      sub: t ? `(${formatNumber(t.activeTenants)} active)` : undefined,
      to: '/tenants',
    },
    { key: 'users', label: 'Users', value: t ? formatCount(t.users) : '' },
    { key: 'conversations', label: 'Conversations', value: t ? formatCount(t.conversations) : '' },
    { key: 'messages', label: 'Messages stored', value: t ? formatCount(t.messages) : '' },
    { key: 'connections', label: 'Live connections', value: t ? formatCount(t.connections) : '' },
    { key: 'today', label: 'Messages today', value: t ? formatCount(t.messagesToday) : '', sub: '(UTC)' },
  ]
  return (
    <section className="readout" aria-label="Platform totals">
      {items.map((item) => (
        <div className="readout__cell" key={item.key}>
          <span className="readout__value tabular">{t ? item.value : <Skeleton width={56} height={22} />}</span>
          <span className="readout__label">
            {item.to ? <Link to={item.to}>{item.label}</Link> : item.label}
            {item.sub ? <span className="readout__sub"> {item.sub}</span> : null}
          </span>
        </div>
      ))}
    </section>
  )
}

function ThroughputLegend({ points }: { points: ThroughputPoint[] }) {
  const theme = useChartTheme()
  const totals = useMemo(
    () => points.reduce((acc, p) => ({ messages: acc.messages + p.messages, deliveries: acc.deliveries + p.deliveries }), { messages: 0, deliveries: 0 }),
    [points],
  )
  const peak = useMemo(() => points.reduce((max, p) => Math.max(max, p.messages), 0), [points])
  return (
    <div className="chart-legend" role="list" aria-label="Series">
      <span className="chart-legend__item" role="listitem">
        <span className="chart-legend__swatch" style={{ background: theme.series1 }} aria-hidden />
        Messages <strong className="tabular">{formatNumber(totals.messages)}</strong>
      </span>
      <span className="chart-legend__item" role="listitem">
        <span className="chart-legend__swatch" style={{ background: theme.series2 }} aria-hidden />
        Deliveries <strong className="tabular">{formatNumber(totals.deliveries)}</strong>
      </span>
      <span className="chart-legend__item chart-legend__item--plain" role="listitem">
        Peak <strong className="tabular">{formatNumber(peak)}</strong> msg/min
      </span>
    </div>
  )
}

function ThroughputTable({ points }: { points: ThroughputPoint[] }) {
  return (
    <details className="data-details">
      <summary>Show as table</summary>
      <div className="table-wrap data-details__table">
        <table className="table table--dense">
          <thead>
            <tr>
              <th scope="col">Minute</th>
              <th scope="col" className="is-right">
                Messages
              </th>
              <th scope="col" className="is-right">
                Deliveries
              </th>
            </tr>
          </thead>
          <tbody>
            {[...points].reverse().map((p) => (
              <tr key={p.minute}>
                <td>{formatTime(p.minute)}</td>
                <td className="is-right">{formatNumber(p.messages)}</td>
                <td className="is-right">{formatNumber(p.deliveries)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}

export function OverviewPage() {
  const { publish, markUnreachable } = useSystem()
  const overview = useApi(systemApi.overview, 'overview', { intervalMs: REFRESH_MS })
  const now = useNow(5_000)

  useEffect(() => {
    if (overview.data) publish(overview.data)
  }, [overview.data, publish])

  useEffect(() => {
    if (overview.error?.isNetwork) markUnreachable()
  }, [overview.error, markUnreachable])

  const data = overview.data
  const unhealthy = data?.components.filter((c) => !c.healthy) ?? []
  const updatedAt = useMemo(() => (data ? new Date().toISOString() : null), [data])

  return (
    <>
      <PageHeader
        title="Overview"
        description="Health of every infrastructure slot, platform totals and live message throughput. Refreshes every 10 seconds."
        meta={
          data ? (
            <>
              <Badge tone={unhealthy.length ? 'warn' : 'ok'} dot>
                {unhealthy.length ? `${unhealthy.length} of ${data.components.length} slots down` : 'All slots healthy'}
              </Badge>
            </>
          ) : null
        }
        actions={
          <>
            <span className="overview__updated muted" aria-live="off">
              {overview.refreshing ? <Spinner size={12} /> : null}
              {updatedAt ? `Updated ${formatRelative(updatedAt, now)}` : null}
            </span>
            <IconButton label="Refresh now" icon={<RefreshCw size={16} aria-hidden />} variant="secondary" onClick={() => void overview.reload()} />
          </>
        }
      />

      {overview.error && !data ? <LoadError error={overview.error} onRetry={() => void overview.reload()} title="Could not load the system overview" /> : null}
      {overview.error && data ? (
        <div className="overview__stale">
          <LoadError error={overview.error} onRetry={() => void overview.reload()} title="Showing the last known state" />
        </div>
      ) : null}

      <div className="stack stack--lg">
        <section aria-labelledby="slots-title">
          <div className="section-head">
            <h2 className="section-head__title" id="slots-title">
              Infrastructure
            </h2>
            <p className="section-head__desc">
              {data ? (
                <>
                  Node <span className="mono">{data.nodeId}</span>, version {data.version}, up {formatDuration(data.uptimeSeconds)} (since {formatDateTime(data.startedAt)})
                </>
              ) : (
                'Each slot is a pluggable connector. Select a row to configure it.'
              )}
            </p>
          </div>
          <PatchBay components={data?.components} loading={overview.loading} />
        </section>

        <Totals overview={data} />

        <Card
          title="Throughput"
          description="Messages accepted and deliveries fanned out per minute, last 60 minutes"
          actions={data ? <ThroughputLegend points={data.throughput} /> : null}
        >
          {!data ? (
            <Skeleton height={240} width="100%" />
          ) : data.throughput.length === 0 ? (
            <EmptyState compact title="No traffic yet" description="Send a few messages from the chat playground to see throughput here." />
          ) : (
            <>
              <ThroughputChart points={data.throughput} />
              <ThroughputTable points={data.throughput} />
            </>
          )}
        </Card>
      </div>
    </>
  )
}
