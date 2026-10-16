import { Check, Plus, Sparkles, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getTenantConfig } from '../../api/client'
import { errorMessage } from '../../api/http'
import { tenantsApi, tenantUsersApi } from '../../api/tenants'
import { PageHeader } from '../../components/layout/PageHeader'
import { Button } from '../../components/ui/Button'
import { buttonClass } from '../../components/ui/classes'
import { Callout, EmptyState, LoadError, Spinner } from '../../components/ui/Feedback'
import { Select } from '../../components/ui/Form'
import { useApi } from '../../hooks/useApi'
import { cx } from '../../lib/util'
import { useAuth } from '../../state/auth'
import { useToast } from '../../state/toast'
import { ChatPane } from './ChatPane'
import type { Suggestion } from './Inbox'
import { UserPicker } from './UserPicker'

const MAX_PANES = 4

const FEATURE_LABELS: ReadonlyArray<{ id: string; label: string }> = [
  { id: 'group-chat', label: 'Groups' },
  { id: 'media-sharing', label: 'Media' },
  { id: 'file-sharing', label: 'Files' },
  { id: 'message-reactions', label: 'Reactions' },
  { id: 'message-editing', label: 'Editing' },
  { id: 'message-deletion', label: 'Delete for everyone' },
  { id: 'presence', label: 'Presence' },
]

const DEMO_USERS = [
  { id: 'alice', displayName: 'Alice Martin' },
  { id: 'bob', displayName: 'Bob Okafor' },
]

function parseSlots(raw: string | null): string[] {
  if (raw === null) return ['', '']
  const slots = raw.split(',').map((s) => s.trim()).slice(0, MAX_PANES)
  return slots.length ? slots : ['']
}

export function PlaygroundPage() {
  const [params, setParams] = useSearchParams()
  const { canWrite } = useAuth()
  const toast = useToast()
  const tenant = params.get('tenant') ?? ''
  const slots = useMemo(() => parseSlots(params.get('users')), [params])
  const [identities, setIdentities] = useState<Record<string, Suggestion>>({})
  const [seeding, setSeeding] = useState(false)

  const tenants = useApi(() => tenantsApi.list(), 'tenants:all')
  const usable = useMemo(() => (tenants.data ?? []).filter((t) => t.status !== 'DELETED'), [tenants.data])
  const config = useApi(() => getTenantConfig(tenant), tenant ? `config:${tenant}` : null)
  const selectedTenant = usable.find((t) => t.slug === tenant)

  const update = useCallback(
    (next: { tenant?: string; slots?: string[] }) => {
      setParams(
        (prev) => {
          const p = new URLSearchParams(prev)
          if (next.tenant !== undefined) p.set('tenant', next.tenant)
          if (next.slots !== undefined) p.set('users', next.slots.join(','))
          return p
        },
        { replace: true },
      )
    },
    [setParams],
  )

  // Pick the first usable tenant when none is selected.
  useEffect(() => {
    if (!tenant && usable.length > 0) {
      const first = usable.find((t) => t.status === 'ACTIVE' || t.status === 'TRIAL') ?? usable[0]
      if (first) update({ tenant: first.slug })
    }
  }, [tenant, usable, update])

  const setSlot = (index: number, userId: string) => update({ slots: slots.map((s, i) => (i === index ? userId : s)) })
  const removeSlot = (index: number) => {
    const next = slots.filter((_, i) => i !== index)
    update({ slots: next.length ? next : [''] })
  }

  const onIdentity = useCallback((s: Suggestion) => {
    setIdentities((m) => (m[s.id]?.displayName === s.displayName ? m : { ...m, [s.id]: s }))
  }, [])

  const suggestions = useMemo<Suggestion[]>(
    () => [...new Set(slots.filter(Boolean))].map((id) => identities[id] ?? { id, displayName: id }),
    [slots, identities],
  )

  const seedDemo = async () => {
    setSeeding(true)
    try {
      for (const u of DEMO_USERS) await tenantUsersApi.upsert(tenant, u)
      update({ slots: DEMO_USERS.map((u) => u.id) })
      toast.success('Demo users ready', 'Alice and Bob are signed in side by side.')
    } catch (err) {
      toast.error(errorMessage(err), 'Could not create demo users')
    } finally {
      setSeeding(false)
    }
  }

  const cfg = config.data
  const enabled = new Set(cfg?.features ?? [])

  return (
    <div className="playground">
      <PageHeader
        title="Chat playground"
        description="Act as real end users of a tenant. Each pane signs in with an impersonation token and uses the client API and realtime socket exactly like an app would."
      />

      <div className="playground__toolbar">
        <Select aria-label="Tenant" value={tenant} onChange={(e) => update({ tenant: e.target.value, slots: ['', ''] })} className="playground__tenant">
          {!tenant ? <option value="">Choose a tenant</option> : null}
          {usable.map((t) => (
            <option key={t.slug} value={t.slug}>
              {t.name} ({t.slug}){t.status === 'SUSPENDED' ? ', suspended' : ''}
            </option>
          ))}
        </Select>
        {config.data ? (
          <ul className="feature-flags" aria-label="Features enabled for this tenant">
            {FEATURE_LABELS.map((f) => {
              const on = enabled.has(f.id)
              return (
                <li key={f.id} className={cx('feature-flag', on ? 'is-on' : 'is-off')} title={on ? `${f.label} enabled` : `${f.label} disabled`}>
                  {on ? <Check size={12} aria-hidden /> : <X size={12} aria-hidden />}
                  {f.label}
                  <span className="sr-only">{on ? ' enabled' : ' disabled'}</span>
                </li>
              )
            })}
          </ul>
        ) : config.loading ? (
          <Spinner size={14} label="Loading tenant config" />
        ) : null}
        <div className="spacer" />
        <Button icon={<Sparkles size={15} aria-hidden />} onClick={() => void seedDemo()} loading={seeding} disabled={!tenant || !canWrite}>
          Alice and Bob
        </Button>
        <Button variant="primary" icon={<Plus size={15} aria-hidden />} onClick={() => update({ slots: [...slots, ''] })} disabled={!tenant || slots.length >= MAX_PANES}>
          Add user pane
        </Button>
      </div>

      {tenants.error ? <LoadError error={tenants.error} onRetry={() => void tenants.reload()} /> : null}
      {tenants.data && usable.length === 0 ? (
        <EmptyState
          title="No tenants to play with"
          description="Create a tenant first, then come back to chat as its users."
          action={
            <Link to="/tenants" className={buttonClass('primary')}>
              Go to tenants
            </Link>
          }
        />
      ) : null}
      {config.error ? <LoadError error={config.error} onRetry={() => void config.reload()} title="Could not load the tenant's public config" /> : null}
      {selectedTenant?.status === 'SUSPENDED' ? (
        <Callout tone="warn" title="This tenant is suspended">
          Every API call and socket connection is refused until it is activated.
        </Callout>
      ) : null}

      {tenant && cfg ? (
        <div className="playground__panes" style={{ ['--panes' as string]: slots.length }}>
          {slots.map((userId, index) =>
            userId ? (
              <ChatPane
                key={`${tenant}:${userId}:${index}`}
                tenant={tenant}
                userId={userId}
                config={cfg}
                suggestions={suggestions}
                onClose={() => removeSlot(index)}
                onIdentity={onIdentity}
              />
            ) : (
              <UserPicker key={`picker:${tenant}:${index}`} tenant={tenant} inUse={slots.filter(Boolean)} onPick={(id) => setSlot(index, id)} onClose={() => removeSlot(index)} />
            ),
          )}
        </div>
      ) : null}
    </div>
  )
}
