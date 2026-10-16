import { Lock, RotateCcw } from 'lucide-react'
import { useMemo, useState } from 'react'
import { featuresApi, plansApi } from '../../api/plans'
import { tenantsApi } from '../../api/tenants'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Badge } from '../../components/ui/Feedback'
import { SearchInput, Toggle } from '../../components/ui/Form'
import { SaveBar } from '../../components/ui/SaveBar'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import type { TenantFeature } from '../../types'
import type { TenantTabProps } from './types'

type Overrides = Record<string, boolean>

function overridesOf(features: TenantFeature[]): Overrides {
  const out: Overrides = {}
  for (const f of features) if (f.source === 'override') out[f.id] = f.enabled
  return out
}

function sameOverrides(a: Overrides, b: Overrides): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const key of keys) if (a[key] !== b[key]) return false
  return true
}

export function FeaturesTab({ tenant, onTenantChange, canWrite }: TenantTabProps) {
  const catalogue = useApi(featuresApi.list, 'features')
  const plans = useApi(plansApi.list, 'plans')
  const initial = useMemo(() => overridesOf(tenant.features), [tenant.features])
  const [draft, setDraft] = useState<Overrides>(initial)
  const [query, setQuery] = useState('')

  const plan = plans.data?.find((p) => p.code === tenant.planCode)
  const descriptions = useMemo(() => new Map((catalogue.data ?? []).map((f) => [f.id, f.description])), [catalogue.data])

  /** What the plan alone would give this feature. */
  const planValue = (f: TenantFeature): boolean => {
    if (plan) return plan.features.includes(f.id)
    // Without the plan list, a non-overridden feature's current state is the plan value.
    return f.source === 'override' ? !f.enabled : f.enabled
  }

  const setFeature = (f: TenantFeature, enabled: boolean) => {
    setDraft((d) => {
      const next = { ...d }
      if (enabled === planValue(f)) delete next[f.id]
      else next[f.id] = enabled
      return next
    })
  }

  const resetFeature = (id: string) =>
    setDraft((d) => {
      const next = { ...d }
      delete next[id]
      return next
    })

  const dirty = !sameOverrides(draft, initial)
  const changes = new Set([...Object.keys(draft), ...Object.keys(initial)].filter((k) => draft[k] !== initial[k])).size

  const save = useAction(
    async () => {
      const features = await tenantsApi.setFeatures(tenant.slug, draft)
      onTenantChange({ ...tenant, features })
      setDraft(overridesOf(features))
      return features
    },
    { success: 'Features saved' },
  )

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase()
    const map = new Map<string, TenantFeature[]>()
    for (const f of tenant.features) {
      if (q && !`${f.name} ${f.id} ${f.category ?? ''}`.toLowerCase().includes(q)) continue
      const key = f.category ?? 'Other'
      map.set(key, [...(map.get(key) ?? []), f])
    }
    // Keep the backend's catalogue order.
    return [...map.entries()]
  }, [tenant.features, query])

  const overrideCount = Object.keys(draft).length

  return (
    <div className="stack">
      <div className="toolbar toolbar--flush">
        <SearchInput value={query} onChange={setQuery} label="Filter features" />
        <div className="spacer" />
        <span className="muted toolbar__count">
          Plan {plan?.name ?? tenant.planName ?? tenant.planCode ?? 'none'}, {overrideCount} {overrideCount === 1 ? 'override' : 'overrides'}
        </span>
      </div>
      {groups.length === 0 ? <p className="muted">No features match.</p> : null}
      {groups.map(([category, features]) => (
        <Card key={category} title={category} flush>
          <ul className="feature-list">
            {features.map((f) => {
              const core = f.status === 'CORE' || f.source === 'core'
              const planned = f.status === 'PLANNED' || f.source === 'planned'
              const overridden = Object.prototype.hasOwnProperty.call(draft, f.id)
              const enabled = core ? true : planned ? false : overridden ? draft[f.id] === true : planValue(f)
              const desc = f.description ?? descriptions.get(f.id)
              return (
                <li key={f.id} className={`feature-row${planned ? ' is-planned' : ''}`}>
                  <Toggle
                    checked={enabled}
                    onChange={(v) => setFeature(f, v)}
                    disabled={core || planned || !canWrite}
                    aria-label={`${f.name} ${enabled ? 'enabled' : 'disabled'}`}
                  />
                  <div className="feature-row__text">
                    <span className="feature-row__name">
                      {f.name}
                      <span className="mono feature-row__id">{f.id}</span>
                    </span>
                    {desc ? <span className="feature-row__desc">{desc}</span> : null}
                  </div>
                  <div className="feature-row__source">
                    {core ? (
                      <Badge>
                        <Lock size={11} aria-hidden />
                        Core, always on
                      </Badge>
                    ) : planned ? (
                      <Badge tone="info">Coming soon</Badge>
                    ) : overridden ? (
                      <>
                        <Badge tone="accent">Override</Badge>
                        <Button size="sm" variant="ghost" icon={<RotateCcw size={13} aria-hidden />} onClick={() => resetFeature(f.id)} disabled={!canWrite}>
                          Reset to plan
                        </Button>
                      </>
                    ) : (
                      <Badge>{planValue(f) ? 'Included in plan' : 'Not in plan'}</Badge>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        </Card>
      ))}
      <SaveBar
        dirty={dirty}
        saving={save.busy}
        onSave={() => void save.run()}
        onDiscard={() => setDraft(initial)}
        message={
          <>
            <strong>{changes}</strong> {changes === 1 ? 'feature' : 'features'} changed. Saving replaces this tenant's overrides.
          </>
        }
        disabled={!canWrite}
      />
    </div>
  )
}
