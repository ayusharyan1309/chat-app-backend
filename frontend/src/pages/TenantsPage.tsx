import { Building2, Plus } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { plansApi } from '../api/plans'
import { tenantsApi } from '../api/tenants'
import { StoreBadge, TenantStatusBadge } from '../components/domain/TenantBadges'
import { PageHeader } from '../components/layout/PageHeader'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { SecretReveal } from '../components/ui/Copy'
import { Dialog } from '../components/ui/Dialog'
import { Callout, EmptyState, LoadError } from '../components/ui/Feedback'
import { Field, FormGrid, Input, SearchInput, Select } from '../components/ui/Form'
import { DataTable, type Column } from '../components/ui/Table'
import { useDebounced } from '../hooks/misc'
import { useApi } from '../hooks/useApi'
import { formatCount, formatDate, pluralize, slugify } from '../lib/format'
import { useAuth } from '../state/auth'
import { TENANT_STATUSES, type CreateTenantResponse, type Plan, type TenantStatus, type TenantSummary } from '../types'
import { toApiError } from '../api/http'

const SLUG_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

interface FormState {
  name: string
  slug: string
  slugTouched: boolean
  contactEmail: string
  domain: string
  planCode: string
  trialDays: string
}

function NewTenantDialog({ open, onClose, plans, onCreated }: { open: boolean; onClose: () => void; plans: Plan[]; onCreated: () => void }) {
  const navigate = useNavigate()
  const defaultPlan = plans.find((p) => p.isDefault)?.code ?? ''
  const [form, setForm] = useState<FormState>({ name: '', slug: '', slugTouched: false, contactEmail: '', domain: '', planCode: defaultPlan, trialDays: '' })
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<CreateTenantResponse | null>(null)

  const slug = form.slugTouched ? form.slug : slugify(form.name)
  const errors = {
    name: !form.name.trim() ? 'Enter a name.' : undefined,
    slug: slug && !SLUG_PATTERN.test(slug) ? 'Use lowercase letters, numbers and hyphens (max 40).' : undefined,
    contactEmail: !EMAIL_PATTERN.test(form.contactEmail.trim()) ? 'Enter a valid email address.' : undefined,
    trialDays: form.trialDays && !/^\d+$/.test(form.trialDays) ? 'Enter a whole number of days.' : undefined,
  }
  const invalid = Object.values(errors).some(Boolean)
  const update = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }))

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (invalid) return
    setBusy(true)
    setError(null)
    try {
      const res = await tenantsApi.create({
        name: form.name.trim(),
        slug: slug || undefined,
        contactEmail: form.contactEmail.trim(),
        domain: form.domain.trim() || undefined,
        planCode: form.planCode || undefined,
        trialDays: form.trialDays ? Number(form.trialDays) : undefined,
      })
      setCreated(res)
      onCreated()
    } catch (err) {
      setError(toApiError(err).message)
    } finally {
      setBusy(false)
    }
  }

  if (created) {
    return (
      <Dialog
        open={open}
        onClose={onClose}
        dismissible={false}
        size="md"
        title={`${created.tenant.name} is ready`}
        description={
          <>
            Tenant <span className="mono">{created.tenant.slug}</span> was created with an initial server API key.
          </>
        }
        footer={
          <>
            <Button variant="ghost" onClick={onClose}>
              Done
            </Button>
            <Button variant="primary" onClick={() => navigate(`/tenants/${encodeURIComponent(created.tenant.slug)}`)}>
              Open tenant
            </Button>
          </>
        }
      >
        <SecretReveal
          value={created.apiKey.key}
          label={`Server API key "${created.apiKey.name}"`}
          note="The tenant backend sends this key as X-API-Key to provision users and mint user tokens. Store it in a secret manager; create a new key if it is lost."
        />
      </Dialog>
    )
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      dismissible={!busy}
      size="md"
      title="New tenant"
      description="Each tenant is an isolated customer with its own users, conversations, keys and settings."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy}>
            Create tenant
          </Button>
        </>
      }
    >
      <FormGrid>
        <Field label="Name" required error={submitted ? errors.name : undefined} className="span-all">
          {(c) => <Input {...c} value={form.name} onChange={(e) => update({ name: e.target.value })} placeholder="Acme Inc" autoFocus />}
        </Field>
        <Field
          label="Slug"
          hint="Identifies the tenant in URLs and tokens. Cannot be changed later."
          error={errors.slug}
          aside={form.slugTouched ? null : 'From name'}
        >
          {(c) => (
            <Input {...c} mono value={slug} placeholder="acme" spellCheck={false} onChange={(e) => update({ slug: e.target.value.toLowerCase(), slugTouched: true })} />
          )}
        </Field>
        <Field label="Contact email" required error={submitted ? errors.contactEmail : undefined}>
          {(c) => <Input {...c} type="email" value={form.contactEmail} onChange={(e) => update({ contactEmail: e.target.value })} placeholder="ops@acme.io" />}
        </Field>
        <Field label="Domain" hint="Optional, for your reference.">
          {(c) => <Input {...c} value={form.domain} onChange={(e) => update({ domain: e.target.value })} placeholder="acme.io" />}
        </Field>
        <Field label="Plan">
          {(c) => (
            <Select {...c} value={form.planCode} onChange={(e) => update({ planCode: e.target.value })}>
              <option value="">Platform default</option>
              {plans
                .filter((p) => p.active)
                .map((p) => (
                  <option key={p.code} value={p.code}>
                    {p.name}
                    {p.isDefault ? ' (default)' : ''}
                  </option>
                ))}
            </Select>
          )}
        </Field>
        <Field label="Trial length" hint="Days. Leave empty to start as an active tenant." error={errors.trialDays}>
          {(c) => <Input {...c} type="number" min={0} inputMode="numeric" value={form.trialDays} onChange={(e) => update({ trialDays: e.target.value })} placeholder="No trial" />}
        </Field>
      </FormGrid>
      {error ? (
        <Callout tone="danger" title="The tenant was not created">
          {error}
        </Callout>
      ) : null}
    </Dialog>
  )
}

export function TenantsPage() {
  const navigate = useNavigate()
  const { canWrite } = useAuth()
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<TenantStatus | ''>('')
  const [plan, setPlan] = useState('')
  const [creating, setCreating] = useState(false)
  const debouncedQ = useDebounced(q.trim(), 250)

  const tenants = useApi(() => tenantsApi.list({ q: debouncedQ, status }), `tenants:${debouncedQ}:${status}`, { keepPrevious: true, intervalMs: 30_000 })
  const plans = useApi(plansApi.list, 'plans')

  const rows = useMemo(() => (tenants.data ?? []).filter((t) => !plan || t.planCode === plan), [tenants.data, plan])
  const filtered = Boolean(debouncedQ || status || plan)

  const columns: Column<TenantSummary>[] = [
    {
      key: 'tenant',
      header: 'Tenant',
      cell: (t) => (
        <div className="cell-primary">
          <Link className="cell-primary__title" to={`/tenants/${encodeURIComponent(t.slug)}`}>
            {t.name}
          </Link>
          <span className="cell-primary__sub">
            <span className="mono">{t.slug}</span>
            {t.domain ? <> &nbsp;{t.domain}</> : null}
          </span>
        </div>
      ),
    },
    { key: 'status', header: 'Status', cell: (t) => <TenantStatusBadge status={t.status} trialEndsAt={t.trialEndsAt} /> },
    { key: 'plan', header: 'Plan', cell: (t) => t.planName ?? t.planCode ?? <span className="muted">None</span> },
    { key: 'store', header: 'Message store', cell: (t) => <StoreBadge provider={t.storeProvider} />, hideBelow: 'md' },
    { key: 'today', header: 'Messages today', align: 'right', cell: (t) => formatCount(t.messagesToday) },
    { key: 'conn', header: 'Connections', align: 'right', cell: (t) => formatCount(t.connections), hideBelow: 'md' },
    { key: 'created', header: 'Created', cell: (t) => <span className="secondary nowrap">{formatDate(t.createdAt)}</span>, hideBelow: 'lg' },
  ]

  return (
    <>
      <PageHeader
        title="Tenants"
        description="Customers on the platform. Open a tenant to manage its plan, features, data store, auth, keys, webhooks and users."
        actions={
          <Button variant="primary" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)} disabled={!canWrite}>
            New tenant
          </Button>
        }
      />
      <div className="toolbar">
        <SearchInput value={q} onChange={setQ} label="Search tenants" placeholder="Search name, slug or domain" />
        <Select aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value as TenantStatus | '')}>
          <option value="">All statuses</option>
          {TENANT_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.charAt(0) + s.slice(1).toLowerCase()}
            </option>
          ))}
        </Select>
        <Select aria-label="Filter by plan" value={plan} onChange={(e) => setPlan(e.target.value)}>
          <option value="">All plans</option>
          {(plans.data ?? []).map((p) => (
            <option key={p.code} value={p.code}>
              {p.name}
            </option>
          ))}
        </Select>
        <div className="spacer" />
        {tenants.data ? <span className="muted toolbar__count">{pluralize(rows.length, 'tenant')}</span> : null}
      </div>
      {tenants.error ? <LoadError error={tenants.error} onRetry={() => void tenants.reload()} /> : null}
      <Card flush>
        <DataTable
          columns={columns}
          rows={tenants.data ? rows : undefined}
          rowKey={(t) => t.slug}
          loading={tenants.loading}
          onRowClick={(t) => navigate(`/tenants/${encodeURIComponent(t.slug)}`)}
          caption="Tenants"
          empty={
            filtered ? (
              <EmptyState compact title="No tenants match these filters" description="Clear the search or pick a different status or plan." />
            ) : (
              <EmptyState
                icon={<Building2 size={20} aria-hidden />}
                title="No tenants yet"
                description="Create a tenant to get an API key, then use the chat playground to try it end to end."
                action={
                  <Button variant="primary" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)} disabled={!canWrite}>
                    New tenant
                  </Button>
                }
              />
            )
          }
        />
      </Card>
      {creating ? <NewTenantDialog open onClose={() => setCreating(false)} plans={plans.data ?? []} onCreated={() => void tenants.reload()} /> : null}
    </>
  )
}
