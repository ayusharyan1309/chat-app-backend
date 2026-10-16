import { MessagesSquare } from 'lucide-react'
import { useCallback, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { tenantsApi } from '../../api/tenants'
import { StoreBadge, TenantStatusBadge } from '../../components/domain/TenantBadges'
import { PageHeader } from '../../components/layout/PageHeader'
import { buttonClass } from '../../components/ui/classes'
import { Badge, EmptyState, LoadError, SkeletonLines } from '../../components/ui/Feedback'
import { TabPanel, Tabs, type TabItem } from '../../components/ui/Tabs'
import { useApi } from '../../hooks/useApi'
import { useAuth } from '../../state/auth'
import type { TenantDetail } from '../../types'
import { ApiKeysTab } from './ApiKeysTab'
import { AuthTab } from './AuthTab'
import { ConnectorsTab } from './ConnectorsTab'
import { FeaturesTab } from './FeaturesTab'
import { OverviewTab } from './OverviewTab'
import { PlanLimitsTab } from './PlanLimitsTab'
import { SettingsTab } from './SettingsTab'
import { tenantPath, type TenantTabProps } from './types'
import { UsersTab } from './UsersTab'
import { WebhooksTab } from './WebhooksTab'

type TabId = 'overview' | 'plan' | 'features' | 'data' | 'auth' | 'keys' | 'webhooks' | 'users' | 'settings'

const TABS: ReadonlyArray<TabItem<TabId>> = [
  { id: 'overview', label: 'Overview' },
  { id: 'plan', label: 'Plan & limits' },
  { id: 'features', label: 'Features' },
  { id: 'data', label: 'Data & connectors' },
  { id: 'auth', label: 'Authentication' },
  { id: 'keys', label: 'API keys' },
  { id: 'webhooks', label: 'Webhooks' },
  { id: 'users', label: 'Users' },
  { id: 'settings', label: 'Branding & settings' },
]

const TAB_COMPONENTS: Record<TabId, (props: TenantTabProps) => ReactNode> = {
  overview: OverviewTab,
  plan: PlanLimitsTab,
  features: FeaturesTab,
  data: ConnectorsTab,
  auth: AuthTab,
  keys: ApiKeysTab,
  webhooks: WebhooksTab,
  users: UsersTab,
  settings: SettingsTab,
}

function isTab(value: string | undefined): value is TabId {
  return TABS.some((t) => t.id === value)
}

export function TenantDetailPage() {
  const params = useParams<{ slug: string; tab?: string }>()
  const slug = params.slug ?? ''
  const navigate = useNavigate()
  const { canWrite } = useAuth()
  const tenant = useApi(() => tenantsApi.get(slug), slug ? `tenant:${slug}` : null)
  const { setData } = tenant
  const onTenantChange = useCallback((next: TenantDetail) => setData(next), [setData])

  if (params.tab !== undefined && !isTab(params.tab)) return <Navigate to={tenantPath(slug)} replace />
  const tab: TabId = isTab(params.tab) ? params.tab : 'overview'
  const data = tenant.data

  if (tenant.error?.status === 404) {
    return (
      <EmptyState
        title="Tenant not found"
        description={
          <>
            No tenant with slug <span className="mono">{slug}</span> exists. It may have been deleted.
          </>
        }
        action={
          <Link to="/tenants" className={buttonClass('primary')}>
            Back to tenants
          </Link>
        }
      />
    )
  }

  const Active = TAB_COMPONENTS[tab]

  return (
    <>
      <PageHeader
        back={{ to: '/tenants', label: 'Tenants' }}
        title={data?.name ?? slug}
        documentTitle={data ? `${data.name} (tenant)` : 'Tenant'}
        meta={
          data ? (
            <>
              <TenantStatusBadge status={data.status} trialEndsAt={data.trialEndsAt} />
              {data.planName || data.planCode ? <Badge tone="accent">{data.planName ?? data.planCode}</Badge> : null}
              <StoreBadge provider={data.storeProvider} />
            </>
          ) : null
        }
        description={
          data ? (
            <>
              <span className="mono">{data.slug}</span>
              {data.contactEmail ? <> &nbsp;&nbsp;{data.contactEmail}</> : null}
              {data.domain ? <> &nbsp;&nbsp;{data.domain}</> : null}
            </>
          ) : null
        }
        actions={
          <Link to={`/playground?tenant=${encodeURIComponent(slug)}`} className={buttonClass('secondary')}>
            <MessagesSquare size={15} aria-hidden />
            <span className="btn__label">Open in playground</span>
          </Link>
        }
      />
      <Tabs idBase="tenant" label="Tenant sections" items={TABS} value={tab} onChange={(id) => navigate(tenantPath(slug, id === 'overview' ? undefined : id))} />
      <TabPanel idBase="tenant" id={tab}>
        {tenant.error && !data ? <LoadError error={tenant.error} onRetry={() => void tenant.reload()} /> : null}
        {!data && !tenant.error ? <SkeletonLines lines={6} /> : null}
        {data ? <Active key={tab} tenant={data} onTenantChange={onTenantChange} reload={tenant.reload} canWrite={canWrite} /> : null}
      </TabPanel>
    </>
  )
}
