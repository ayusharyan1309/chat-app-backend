import { Layers, Pencil, Plus, Trash2 } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { featuresApi, plansApi } from '../api/plans'
import { PageHeader } from '../components/layout/PageHeader'
import { Button, IconButton } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Dialog } from '../components/ui/Dialog'
import { Badge, Callout, Chip, EmptyState, LoadError, SkeletonLines } from '../components/ui/Feedback'
import { Checkbox, Field, FormGrid, Input, Textarea, Toggle } from '../components/ui/Form'
import { useAction } from '../hooks/useAction'
import { useApi } from '../hooks/useApi'
import { formatLimit, formatMoney, pluralize } from '../lib/format'
import { parseOptionalInt } from '../lib/util'
import { useAuth } from '../state/auth'
import { useConfirm } from '../state/confirm'
import { useToast } from '../state/toast'
import { EMPTY_LIMITS, PLAN_LIMIT_FIELDS, type Feature, type Plan, type PlanInput, type PlanLimitKey } from '../types'

const CODE_PATTERN = /^[a-z0-9](?:[a-z0-9-_]{0,38}[a-z0-9])?$/

interface Draft {
  code: string
  name: string
  description: string
  priceMonthly: string
  priceYearly: string
  currency: string
  sortOrder: string
  isDefault: boolean
  active: boolean
  limits: Record<PlanLimitKey, string>
  features: string[]
}

function toDraft(plan: Plan | null, nextSort: number): Draft {
  const limits = {} as Record<PlanLimitKey, string>
  for (const { key } of PLAN_LIMIT_FIELDS) {
    const v = plan?.limits[key]
    limits[key] = v === null || v === undefined ? '' : String(v)
  }
  return {
    code: plan?.code ?? '',
    name: plan?.name ?? '',
    description: plan?.description ?? '',
    priceMonthly: plan?.priceMonthly === null || plan?.priceMonthly === undefined ? '' : String(plan.priceMonthly),
    priceYearly: plan?.priceYearly === null || plan?.priceYearly === undefined ? '' : String(plan.priceYearly),
    currency: plan?.currency ?? 'USD',
    sortOrder: String(plan?.sortOrder ?? nextSort),
    isDefault: plan?.isDefault ?? false,
    active: plan?.active ?? true,
    limits,
    features: plan?.features ?? [],
  }
}

function parsePrice(raw: string): number | null {
  const t = raw.trim()
  if (!t) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : Number.NaN
}

function PlanDialog({ plan, features, nextSort, onClose, onSaved }: { plan: Plan | null; features: Feature[]; nextSort: number; onClose: () => void; onSaved: (p: Plan) => void }) {
  const [draft, setDraft] = useState<Draft>(() => toDraft(plan, nextSort))
  const [submitted, setSubmitted] = useState(false)
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))
  const save = useAction((body: PlanInput) => (plan ? plansApi.update(plan.code, body) : plansApi.create(body)), {
    toastError: false,
    success: (p) => `Plan ${p.name} saved`,
  })

  const available = features.filter((f) => f.status === 'AVAILABLE')
  const core = features.filter((f) => f.status === 'CORE')
  const byCategory = useMemo(() => {
    const map = new Map<string, Feature[]>()
    for (const f of available) map.set(f.category ?? 'Other', [...(map.get(f.category ?? 'Other') ?? []), f])
    return [...map.entries()]
  }, [available])

  const limitErrors = PLAN_LIMIT_FIELDS.filter(({ key }) => {
    const n = parseOptionalInt(draft.limits[key])
    return n !== null && (Number.isNaN(n) || n < 0)
  }).map((f) => f.key)
  const errors = {
    code: !plan && !CODE_PATTERN.test(draft.code) ? 'Use lowercase letters, numbers, - or _.' : undefined,
    name: !draft.name.trim() ? 'Enter a name.' : undefined,
    priceMonthly: Number.isNaN(parsePrice(draft.priceMonthly) ?? 0) ? 'Enter a number.' : undefined,
    priceYearly: Number.isNaN(parsePrice(draft.priceYearly) ?? 0) ? 'Enter a number.' : undefined,
    currency: !/^[A-Za-z]{3}$/.test(draft.currency) ? 'Use a 3-letter ISO code.' : undefined,
    sortOrder: !/^-?\d+$/.test(draft.sortOrder) ? 'Enter a whole number.' : undefined,
  }
  const invalid = Object.values(errors).some(Boolean) || limitErrors.length > 0

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (invalid) return
    const limits = { ...EMPTY_LIMITS }
    for (const { key } of PLAN_LIMIT_FIELDS) limits[key] = parseOptionalInt(draft.limits[key])
    const res = await save.run({
      code: plan?.code ?? draft.code,
      name: draft.name.trim(),
      description: draft.description.trim() || null,
      priceMonthly: parsePrice(draft.priceMonthly),
      priceYearly: parsePrice(draft.priceYearly),
      currency: draft.currency.toUpperCase(),
      limits,
      features: draft.features.filter((id) => available.some((f) => f.id === id)),
      isDefault: draft.isDefault,
      active: draft.active,
      sortOrder: Number(draft.sortOrder),
    })
    if (res) {
      onSaved(res)
      onClose()
    }
  }

  const toggleFeature = (id: string, on: boolean) => set({ features: on ? [...draft.features, id] : draft.features.filter((f) => f !== id) })

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!save.busy}
      size="lg"
      title={plan ? `Edit ${plan.name}` : 'New plan'}
      description="Plans bundle limits and features. Tenants on this plan pick up changes immediately."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={save.busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={save.busy}>
            {plan ? 'Save plan' : 'Create plan'}
          </Button>
        </>
      }
    >
      <FormGrid columns={3}>
        <Field label="Code" required hint={plan ? 'Permanent.' : 'Stable identifier, e.g. pro.'} error={submitted ? errors.code : undefined}>
          {(c) => <Input {...c} mono value={draft.code} onChange={(e) => set({ code: e.target.value.toLowerCase() })} readOnly={!!plan} autoFocus={!plan} />}
        </Field>
        <Field label="Name" required error={submitted ? errors.name : undefined}>
          {(c) => <Input {...c} value={draft.name} onChange={(e) => set({ name: e.target.value })} autoFocus={!!plan} />}
        </Field>
        <Field label="Sort order" error={errors.sortOrder}>
          {(c) => <Input {...c} type="number" value={draft.sortOrder} onChange={(e) => set({ sortOrder: e.target.value })} />}
        </Field>
        <Field label="Description" className="span-all">
          {(c) => <Textarea {...c} rows={2} value={draft.description} onChange={(e) => set({ description: e.target.value })} />}
        </Field>
        <Field label="Monthly price" error={errors.priceMonthly}>
          {(c) => <Input {...c} type="number" min={0} step="0.01" value={draft.priceMonthly} onChange={(e) => set({ priceMonthly: e.target.value })} placeholder="Free" />}
        </Field>
        <Field label="Yearly price" error={errors.priceYearly}>
          {(c) => <Input {...c} type="number" min={0} step="0.01" value={draft.priceYearly} onChange={(e) => set({ priceYearly: e.target.value })} placeholder="Free" />}
        </Field>
        <Field label="Currency" error={errors.currency}>
          {(c) => <Input {...c} mono maxLength={3} value={draft.currency} onChange={(e) => set({ currency: e.target.value.toUpperCase() })} />}
        </Field>
      </FormGrid>

      <div className="row row--wrap plan-dialog__toggles">
        <Toggle checked={draft.active} onChange={(v) => set({ active: v })} label="Active" description="Inactive plans cannot be assigned to new tenants." />
        <Toggle checked={draft.isDefault} onChange={(v) => set({ isDefault: v })} label="Default plan" description="Used when a tenant is created without a plan." />
      </div>

      <fieldset className="fieldset">
        <legend className="field__label">Limits</legend>
        <p className="field__hint">Leave empty for unlimited.</p>
        <FormGrid columns={3}>
          {PLAN_LIMIT_FIELDS.map((f) => (
            <Field key={f.key} label={f.unit ? `${f.label} (${f.unit})` : f.label} error={limitErrors.includes(f.key) ? 'Whole number, 0 or more.' : undefined}>
              {(c) => (
                <Input
                  {...c}
                  type="number"
                  min={0}
                  inputMode="numeric"
                  value={draft.limits[f.key]}
                  placeholder="Unlimited"
                  onChange={(e) => set({ limits: { ...draft.limits, [f.key]: e.target.value } })}
                />
              )}
            </Field>
          ))}
        </FormGrid>
      </fieldset>

      <fieldset className="fieldset">
        <legend className="field__label">Features</legend>
        <p className="field__hint">
          {draft.features.length} selected.{core.length ? ` Core features (${core.map((f) => f.name).join(', ')}) are always included.` : ''} Planned
          features cannot be bundled yet.
        </p>
        {byCategory.length === 0 ? <p className="muted">No optional features are available.</p> : null}
        {byCategory.map(([category, list]) => (
          <div key={category} className="feature-picker__group">
            <p className="feature-picker__category">{category}</p>
            <div className="checkbox-grid">
              {list.map((f) => (
                <Checkbox key={f.id} checked={draft.features.includes(f.id)} onChange={(on) => toggleFeature(f.id, on)} label={f.name} description={f.description ?? undefined} />
              ))}
            </div>
          </div>
        ))}
      </fieldset>
      {save.error ? <Callout tone="danger">{save.error.message}</Callout> : null}
    </Dialog>
  )
}

function PlanCard({ plan, featureNames, canWrite, onEdit, onDelete }: { plan: Plan; featureNames: Map<string, string>; canWrite: boolean; onEdit: () => void; onDelete: () => void }) {
  return (
    <article className={`plan-card${plan.active ? '' : ' is-inactive'}`}>
      <header className="plan-card__head">
        <div className="plan-card__title-row">
          <h2 className="plan-card__name">{plan.name}</h2>
          <span className="mono muted plan-card__code">{plan.code}</span>
        </div>
        <div className="row row--wrap">
          {plan.isDefault ? <Badge tone="accent">Default</Badge> : null}
          {!plan.active ? <Badge>Inactive</Badge> : null}
          <Badge>{pluralize(plan.tenantCount, 'tenant')}</Badge>
        </div>
      </header>
      <p className="plan-card__price">
        <span className="plan-card__amount">{plan.priceMonthly ? formatMoney(plan.priceMonthly, plan.currency) : 'Free'}</span>
        {plan.priceMonthly ? <span className="muted"> per month</span> : null}
        {plan.priceYearly ? <span className="muted plan-card__yearly">{formatMoney(plan.priceYearly, plan.currency)} per year</span> : null}
      </p>
      {plan.description ? <p className="plan-card__desc">{plan.description}</p> : null}
      <dl className="plan-card__limits">
        {PLAN_LIMIT_FIELDS.map((f) => (
          <div key={f.key} className="plan-card__limit">
            <dt>{f.label}</dt>
            <dd className={plan.limits[f.key] === null ? 'is-unlimited' : undefined}>{formatLimit(plan.limits[f.key], f.unit)}</dd>
          </div>
        ))}
      </dl>
      <div className="chip-list plan-card__features">
        {plan.features.length === 0 ? <span className="muted">Core features only</span> : null}
        {plan.features.map((id) => (
          <Chip key={id} title={id}>
            {featureNames.get(id) ?? id}
          </Chip>
        ))}
      </div>
      <footer className="plan-card__foot">
        <Button size="sm" icon={<Pencil size={13} aria-hidden />} onClick={onEdit} disabled={!canWrite}>
          Edit
        </Button>
        <IconButton size="sm" variant="danger-ghost" label={`Delete ${plan.name}`} icon={<Trash2 size={14} aria-hidden />} onClick={onDelete} disabled={!canWrite} />
      </footer>
    </article>
  )
}

export function PlansPage() {
  const { canWrite } = useAuth()
  const confirm = useConfirm()
  const toast = useToast()
  const plans = useApi(plansApi.list, 'plans')
  const features = useApi(featuresApi.list, 'features')
  const [editing, setEditing] = useState<Plan | 'new' | null>(null)

  const sorted = useMemo(() => [...(plans.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name)), [plans.data])
  const featureNames = useMemo(() => new Map((features.data ?? []).map((f) => [f.id, f.name])), [features.data])
  const nextSort = sorted.length ? Math.max(...sorted.map((p) => p.sortOrder)) + 1 : 1

  const remove = (plan: Plan) =>
    confirm({
      title: `Delete the ${plan.name} plan?`,
      body: (
        <>
          {plan.isDefault ? <p>This is the default plan. Make another plan the default first.</p> : null}
          {plan.tenantCount > 0 ? <p>{pluralize(plan.tenantCount, 'tenant')} still {plan.tenantCount === 1 ? 'uses' : 'use'} this plan. Move {plan.tenantCount === 1 ? 'it' : 'them'} to another plan first.</p> : null}
          {!plan.isDefault && plan.tenantCount === 0 ? <p>No tenants use this plan. It will be removed permanently.</p> : null}
        </>
      ),
      confirmLabel: 'Delete plan',
      tone: 'danger',
      action: async () => {
        await plansApi.remove(plan.code)
        toast.success(`Plan ${plan.name} deleted`)
        await plans.reload()
      },
    })

  return (
    <>
      <PageHeader
        title="Plans"
        description="Plans bundle limits and features. Assign them to tenants and fine-tune individual tenants with overrides."
        actions={
          <Button variant="primary" icon={<Plus size={16} aria-hidden />} onClick={() => setEditing('new')} disabled={!canWrite}>
            New plan
          </Button>
        }
      />
      {plans.error ? <LoadError error={plans.error} onRetry={() => void plans.reload()} /> : null}
      {plans.loading ? (
        <div className="plan-grid">
          {Array.from({ length: 3 }, (_, i) => (
            <Card key={i}>
              <SkeletonLines lines={6} />
            </Card>
          ))}
        </div>
      ) : sorted.length === 0 && plans.data ? (
        <EmptyState
          icon={<Layers size={20} aria-hidden />}
          title="No plans yet"
          description="Create a plan to define limits and feature bundles for tenants."
          action={
            <Button variant="primary" onClick={() => setEditing('new')} disabled={!canWrite}>
              New plan
            </Button>
          }
        />
      ) : (
        <div className="plan-grid">
          {sorted.map((plan) => (
            <PlanCard key={plan.code} plan={plan} featureNames={featureNames} canWrite={canWrite} onEdit={() => setEditing(plan)} onDelete={() => void remove(plan)} />
          ))}
        </div>
      )}
      {editing ? (
        <PlanDialog
          plan={editing === 'new' ? null : editing}
          features={features.data ?? []}
          nextSort={nextSort}
          onClose={() => setEditing(null)}
          onSaved={() => void plans.reload()}
        />
      ) : null}
    </>
  )
}
