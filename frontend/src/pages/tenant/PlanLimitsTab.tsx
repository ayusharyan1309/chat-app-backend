import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { plansApi } from '../../api/plans'
import { tenantsApi } from '../../api/tenants'
import { Card } from '../../components/ui/Card'
import { Badge, Callout, LoadError, SkeletonLines } from '../../components/ui/Feedback'
import { Field, Input, Select } from '../../components/ui/Form'
import { SaveBar } from '../../components/ui/SaveBar'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { formatLimit, formatMoney, pluralize } from '../../lib/format'
import { parseOptionalInt } from '../../lib/util'
import { EMPTY_LIMITS, PLAN_LIMIT_FIELDS, type PlanLimitKey, type PlanLimits } from '../../types'
import type { TenantTabProps } from './types'

type Drafts = Record<PlanLimitKey, string>

function toDrafts(overrides: PlanLimits | null | undefined): Drafts {
  const out = {} as Drafts
  for (const { key } of PLAN_LIMIT_FIELDS) {
    const v = overrides?.[key]
    out[key] = v === null || v === undefined ? '' : String(v)
  }
  return out
}

export function PlanLimitsTab({ tenant, onTenantChange, canWrite }: TenantTabProps) {
  const plans = useApi(plansApi.list, 'plans')
  const initialDrafts = useMemo(() => toDrafts(tenant.limitOverrides), [tenant.limitOverrides])
  const [planCode, setPlanCode] = useState(tenant.planCode ?? '')
  const [drafts, setDrafts] = useState<Drafts>(initialDrafts)

  const plan = plans.data?.find((p) => p.code === planCode)
  const planLimits: PlanLimits = plan?.limits ?? EMPTY_LIMITS
  const invalid = PLAN_LIMIT_FIELDS.filter(({ key }) => {
    const n = parseOptionalInt(drafts[key])
    return n !== null && (Number.isNaN(n) || n < 0)
  }).map((f) => f.key)

  const dirty = planCode !== (tenant.planCode ?? '') || PLAN_LIMIT_FIELDS.some(({ key }) => drafts[key] !== initialDrafts[key])

  const save = useAction(
    async () => {
      const limitOverrides = { ...EMPTY_LIMITS }
      for (const { key } of PLAN_LIMIT_FIELDS) limitOverrides[key] = parseOptionalInt(drafts[key])
      const next = await tenantsApi.update(tenant.slug, { planCode: planCode || undefined, limitOverrides })
      onTenantChange(next)
      setPlanCode(next.planCode ?? '')
      setDrafts(toDrafts(next.limitOverrides))
      return next
    },
    { success: 'Plan and limits saved' },
  )

  const discard = () => {
    setPlanCode(tenant.planCode ?? '')
    setDrafts(initialDrafts)
  }

  if (plans.loading) return <SkeletonLines lines={6} />

  return (
    <div className="stack">
      {plans.error ? <LoadError error={plans.error} onRetry={() => void plans.reload()} /> : null}
      <Card title="Plan" description="The plan sets the default limits and the bundle of features. Changing it applies immediately after saving.">
        <div className="plan-picker-row">
          <Field label="Plan">
            {(c) => (
              <Select {...c} value={planCode} onChange={(e) => setPlanCode(e.target.value)} disabled={!canWrite}>
                {!planCode ? <option value="">No plan</option> : null}
                {(plans.data ?? []).map((p) => (
                  <option key={p.code} value={p.code} disabled={!p.active && p.code !== tenant.planCode}>
                    {p.name}
                    {p.active ? '' : ' (inactive)'}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          {plan ? (
            <div className="plan-picker-row__summary">
              <span className="plan-picker-row__price">
                {formatMoney(plan.priceMonthly, plan.currency)}
                <span className="muted"> per month</span>
              </span>
              <span className="muted">
                {pluralize(plan.features.length, 'feature')}, {pluralize(plan.tenantCount, 'tenant')}. <Link to="/plans">Manage plans</Link>
              </span>
            </div>
          ) : null}
        </div>
        {planCode !== (tenant.planCode ?? '') ? (
          <Callout tone="info">Feature defaults change with the plan. Overrides set on the Features tab are kept.</Callout>
        ) : null}
      </Card>

      <Card
        title="Limits"
        description="Leave an override empty to inherit the plan value. Empty plan values mean unlimited."
        flush
      >
        <div className="table-wrap">
          <table className="table limits-table">
            <thead>
              <tr>
                <th scope="col">Limit</th>
                <th scope="col" className="is-right">
                  Plan value
                </th>
                <th scope="col">Override</th>
                <th scope="col" className="is-right">
                  Effective
                </th>
              </tr>
            </thead>
            <tbody>
              {PLAN_LIMIT_FIELDS.map((field) => {
                const planValue = planLimits[field.key]
                const parsed = parseOptionalInt(drafts[field.key])
                const overridden = parsed !== null && !Number.isNaN(parsed)
                const effective = overridden ? parsed : planValue
                const bad = invalid.includes(field.key)
                return (
                  <tr key={field.key}>
                    <td>
                      <div className="cell-primary">
                        <span className="cell-primary__title">{field.label}</span>
                        <span className="cell-primary__sub">{field.hint}</span>
                      </div>
                    </td>
                    <td className="is-right secondary">{formatLimit(planValue, field.unit)}</td>
                    <td className="limits-table__input">
                      <Input
                        type="number"
                        min={0}
                        inputMode="numeric"
                        aria-label={`${field.label} override`}
                        aria-invalid={bad || undefined}
                        value={drafts[field.key]}
                        placeholder={planValue === null ? 'Unlimited (plan)' : `${planValue} (plan)`}
                        onChange={(e) => setDrafts((d) => ({ ...d, [field.key]: e.target.value }))}
                        disabled={!canWrite}
                        className={bad ? 'is-invalid' : undefined}
                      />
                    </td>
                    <td className="is-right">
                      <span className="limits-table__effective">
                        {formatLimit(effective, field.unit)}
                        {overridden ? <Badge tone="accent">Override</Badge> : null}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>
      {invalid.length > 0 ? <Callout tone="danger">Limits must be whole numbers of zero or more.</Callout> : null}
      <SaveBar dirty={dirty} saving={save.busy} onSave={() => void save.run()} onDiscard={discard} disabled={invalid.length > 0 || !canWrite} />
    </div>
  )
}
