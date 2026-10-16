import { Check, Plus, Sparkles, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getTenantConfig } from '../../api/client'
import { tenantsApi, tenantUsersApi } from '../../api/tenants'
import { PageHeader } from '../../components/layout/PageHeader'
import { Button } from '../../components/ui/Button'
import { buttonClass } from '../../components/ui/classes'
import { Callout, EmptyState, LoadError, Spinner } from '../../components/ui/Feedback'
import { Select } from '../../components/ui/Form'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { cx } from '../../lib/util'
import { useAuth } from '../../state/auth'
import { useToast } from '../../state/toast'
import type { FeatureId } from '../../types'
import { ChatPane } from './ChatPane'
import type { Suggestion } from './Inbox'
import { MAX_PANES, parseSlots, slotKeys } from './slots'
import { UserPicker } from './UserPicker'

const FEATURE_LABELS: ReadonlyArray<{ id: FeatureId; label: string }> = [
  { id: 'group-chat', label: 'Groups' },
  { id: 'media-sharing', label: 'Media' },
  { id: 'file-sharing', label: 'Files' },
  { id: 'message-reactions', label: 'Reactions' },
  { id: 'message-editing', label: 'Editing' },
  { id: 'message-deletion', label: 'Delete for everyone' },
  { id: 'read-receipts', label: 'Read receipts' },
  { id: 'typing-indicators', label: 'Typing' },
  { id: 'presence', label: 'Presence' },
]

const DEMO_USERS = [
  { id: 'alice', displayName: 'Alice Martin' },
  { id: 'bob', displayName: 'Bob Okafor' },
]

export function PlaygroundPage() {
  const [params, setParams] = useSearchParams()
  const { canWrite } = useAuth()
  const toast = useToast()
  const tenant = params.get('tenant') ?? ''
  const slots = useMemo(() => parseSlots(params.get('users')), [params])
  const keys = useMemo(() => slotKeys(tenant, slots), [tenant, slots])
  const [identities, setIdentities] = useState<Record<string, Suggestion>>({})

  const tenants = useApi((signal) => tenantsApi.list({}, { signal }), 'tenants:all')
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

  const setSlot = (index: number, userId: string) => {
    if (slots.some((s, i) => s === userId && i !== index)) {
      toast.info(`${userId} is already open`, 'Each user can be signed in in one pane at a time.')
      return
    }
    update({ slots: slots.map((s, i) => (i === index ? userId : s)) })
  }
  const removeAt = (index: number) => {
    const next = slots.filter((_, i) => i !== index)
    update({ slots: next.length ? next : [''] })
  }
  const removeUser = useCallback(
    (userId: string) => {
      const next = slots.filter((s) => s !== userId)
      update({ slots: next.length ? next : [''] })
    },
    [slots, update],
  )

  const onIdentity = useCallback((s: Suggestion) => {
    setIdentities((m) => (m[s.id]?.displayName === s.displayName ? m : { ...m, [s.id]: s }))
  }, [])

  const suggestions = useMemo<Suggestion[]>(() => slots.filter(Boolean).map((id) => identities[id] ?? { id, displayName: id }), [slots, identities])

  const seedDemo = useAction(
    async () => {
      for (const u of DEMO_USERS) await tenantUsersApi.upsert(tenant, u)
      update({ slots: DEMO_USERS.map((u) => u.id) })
      toast.success('Demo users ready', 'Alice and Bob are signed in side by side.')
      return true
    },
    { errorTitle: 'Could not create demo users' },
  )

  const cfg = config.data
  const enabled = new Set(cfg?.features ?? [])
  const inUse = slots.filter(Boolean)

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
        <Button icon={<Sparkles size={15} aria-hidden />} onClick={() => void seedDemo.run()} loading={seedDemo.busy} disabled={!tenant || !canWrite}>
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
              <ChatPane key={keys[index]} tenant={tenant} userId={userId} config={cfg} suggestions={suggestions} onClose={removeUser} onIdentity={onIdentity} />
            ) : (
              <UserPicker key={keys[index]} tenant={tenant} inUse={inUse} onPick={(id) => setSlot(index, id)} onClose={() => removeAt(index)} />
            ),
          )}
        </div>
      ) : null}
    </div>
  )
}
