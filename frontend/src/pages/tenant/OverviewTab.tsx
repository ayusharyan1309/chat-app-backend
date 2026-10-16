import { Pause, Play, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { tenantsApi } from '../../api/tenants'
import { USAGE_METRICS, type UsageMetric } from '../../components/charts/metrics'
import { UsageChart } from '../../components/charts/UsageChart'
import { StoreBadge, TenantStatusBadge } from '../../components/domain/TenantBadges'
import { Button } from '../../components/ui/Button'
import { Card, MetaList } from '../../components/ui/Card'
import { Callout, EmptyState, LoadError, Skeleton } from '../../components/ui/Feedback'
import { Segmented } from '../../components/ui/Misc'
import { useApi } from '../../hooks/useApi'
import { formatBytes, formatCount, formatDate, formatDateTime, formatNumber, formatRelative } from '../../lib/format'
import { useConfirm } from '../../state/confirm'
import { useToast } from '../../state/toast'
import type { TenantTabProps } from './types'

export function OverviewTab({ tenant, onTenantChange, canWrite }: TenantTabProps) {
  const navigate = useNavigate()
  const confirm = useConfirm()
  const toast = useToast()
  const [metric, setMetric] = useState<UsageMetric>('messages')
  const [activating, setActivating] = useState(false)
  const usage = useApi(() => tenantsApi.usage(tenant.slug, 30), `usage:${tenant.slug}`)
  const stats = tenant.stats

  const totalForMetric = (usage.data ?? []).reduce((sum, p) => sum + p[metric], 0)

  const suspend = () =>
    confirm({
      title: `Suspend ${tenant.name}?`,
      body: (
        <p>
          Every API call for this tenant returns 403 and open sockets are closed immediately. Data is kept. You can activate
          the tenant again at any time.
        </p>
      ),
      confirmLabel: 'Suspend tenant',
      tone: 'danger',
      action: async () => {
        onTenantChange(await tenantsApi.suspend(tenant.slug))
        toast.success(`${tenant.name} suspended`)
      },
    })

  const activate = async () => {
    setActivating(true)
    try {
      onTenantChange(await tenantsApi.activate(tenant.slug))
      toast.success(`${tenant.name} activated`)
    } catch (err) {
      toast.error(err)
    } finally {
      setActivating(false)
    }
  }

  const remove = async () => {
    const ok = await confirm({
      title: `Delete ${tenant.name}?`,
      body: (
        <>
          <p>The tenant is soft-deleted: its API keys and user tokens stop working and it disappears from active lists.</p>
          <p>Stored conversations are not erased by this action.</p>
        </>
      ),
      requireText: tenant.slug,
      confirmLabel: 'Delete tenant',
      tone: 'danger',
      action: () => tenantsApi.remove(tenant.slug),
    })
    if (ok) {
      toast.success(`${tenant.name} deleted`)
      navigate('/tenants')
    }
  }

  return (
    <div className="tenant-overview">
      <section className="readout readout--compact" aria-label="Tenant totals">
        {[
          { key: 'users', label: 'Users', value: formatCount(stats.users) },
          { key: 'conversations', label: 'Conversations', value: formatCount(stats.conversations) },
          { key: 'messages', label: 'Messages stored', value: formatCount(stats.messages) },
          { key: 'today', label: 'Messages today', value: formatCount(stats.messagesToday) },
          { key: 'connections', label: 'Live connections', value: formatCount(stats.connections) },
          { key: 'storage', label: 'Media storage', value: formatBytes(stats.storageBytes) },
        ].map((item) => (
          <div className="readout__cell" key={item.key}>
            <span className="readout__value tabular">{item.value}</span>
            <span className="readout__label">{item.label}</span>
          </div>
        ))}
      </section>

      <div className="tenant-overview__grid">
        <Card
          title="Usage, last 30 days"
          description={usage.data ? `${USAGE_METRICS.find((m) => m.value === metric)?.label}: ${metric === 'mediaBytes' ? formatBytes(totalForMetric) : formatNumber(totalForMetric)} in total` : 'Daily totals, UTC'}
          actions={<Segmented size="sm" label="Usage metric" value={metric} onChange={setMetric} options={USAGE_METRICS} />}
        >
          {usage.error ? <LoadError error={usage.error} onRetry={() => void usage.reload()} /> : null}
          {!usage.data && !usage.error ? <Skeleton height={220} /> : null}
          {usage.data && usage.data.length === 0 ? <EmptyState compact title="No usage recorded yet" /> : null}
          {usage.data && usage.data.length > 0 ? <UsageChart points={usage.data} metric={metric} /> : null}
        </Card>

        <div className="stack">
          <Card title="Details">
            <MetaList
              columns={2}
              items={[
                { label: 'Slug', value: <span className="mono">{tenant.slug}</span> },
                { label: 'Status', value: <TenantStatusBadge status={tenant.status} trialEndsAt={tenant.trialEndsAt} /> },
                ...(tenant.trialEndsAt ? [{ label: 'Trial ends', value: `${formatDate(tenant.trialEndsAt)} (${formatRelative(tenant.trialEndsAt)})` }] : []),
                { label: 'Plan', value: tenant.planName ?? tenant.planCode ?? 'None' },
                { label: 'Message store', value: <StoreBadge provider={tenant.storeProvider} /> },
                { label: 'Contact', value: tenant.contactEmail ?? '—' },
                { label: 'Domain', value: tenant.domain ?? '—' },
                { label: 'Created', value: formatDateTime(tenant.createdAt) },
                { label: 'Last updated', value: tenant.updatedAt ? formatRelative(tenant.updatedAt) : '—' },
              ]}
            />
          </Card>

          <Card title="Lifecycle" description="Suspend access temporarily or remove the tenant.">
            <div className="stack stack--sm">
              {tenant.status === 'DELETED' ? <Callout tone="danger">This tenant is deleted. Its credentials no longer work.</Callout> : null}
              {tenant.status === 'SUSPENDED' ? (
                <Callout tone="warn" title="Suspended">
                  API calls return 403 and sockets are refused until the tenant is activated.
                </Callout>
              ) : null}
              <div className="row row--wrap">
                {tenant.status === 'SUSPENDED' ? (
                  <Button variant="primary" icon={<Play size={15} aria-hidden />} onClick={() => void activate()} loading={activating} disabled={!canWrite}>
                    Activate
                  </Button>
                ) : null}
                {tenant.status === 'ACTIVE' || tenant.status === 'TRIAL' ? (
                  <Button icon={<Pause size={15} aria-hidden />} onClick={() => void suspend()} disabled={!canWrite}>
                    Suspend
                  </Button>
                ) : null}
                {tenant.status !== 'DELETED' ? (
                  <Button variant="danger-ghost" icon={<Trash2 size={15} aria-hidden />} onClick={() => void remove()} disabled={!canWrite}>
                    Delete tenant
                  </Button>
                ) : null}
              </div>
            </div>
          </Card>
        </div>
      </div>
    </div>
  )
}
