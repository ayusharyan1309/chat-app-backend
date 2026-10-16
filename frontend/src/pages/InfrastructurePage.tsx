import { Activity, Cable, RotateCcw, Settings2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { connectorsApi, infraApi } from '../api/infra'
import { errorMessage } from '../api/http'
import { ConnectorEditor } from '../components/connectors/ConnectorEditor'
import { MESSAGE_STORE_SWITCH_WARNING } from '../components/connectors/warnings'
import { DetailsList, HealthLamp, TestResult } from '../components/connectors/Health'
import { sourceLabel } from '../components/connectors/labels'
import { MaturityBadge } from '../components/connectors/ProviderPicker'
import { Socket } from '../components/connectors/PatchBay'
import { SettingsSummary } from '../components/connectors/SettingsSummary'
import { PageHeader } from '../components/layout/PageHeader'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Badge, Callout, EmptyState, LoadError, SkeletonLines } from '../components/ui/Feedback'
import { Dialog } from '../components/ui/Dialog'
import { useApi } from '../hooks/useApi'
import { formatLatency, formatRelative } from '../lib/format'
import { useAuth } from '../state/auth'
import { useConfirm } from '../state/confirm'
import { useToast } from '../state/toast'
import type { ConnectorKindDescriptor, ConnectorState, HealthResult } from '../types'

function ConnectorPanel({
  descriptor,
  state,
  onConfigure,
  onStateChange,
  canWrite,
}: {
  descriptor: ConnectorKindDescriptor
  state: ConnectorState | undefined
  onConfigure: () => void
  onStateChange: (next: ConnectorState) => void
  canWrite: boolean
}) {
  const toast = useToast()
  const confirm = useConfirm()
  const [checking, setChecking] = useState(false)
  const [check, setCheck] = useState<HealthResult | null>(null)
  const [checkError, setCheckError] = useState<string | null>(null)
  const provider = descriptor.providers.find((p) => p.id === state?.provider)
  const health = state?.health ?? null

  const runCheck = async () => {
    if (!state) return
    setChecking(true)
    setCheck(null)
    setCheckError(null)
    try {
      // Masked secrets are sent back as "********", so the stored values are used.
      setCheck(await infraApi.test(descriptor.kind, { provider: state.provider, settings: state.settings }))
    } catch (err) {
      setCheckError(errorMessage(err))
    } finally {
      setChecking(false)
    }
  }

  const reset = async () => {
    await confirm({
      title: `Reset ${descriptor.displayName.toLowerCase()} to bootstrap config?`,
      body: (
        <>
          <p>
            The console setting is removed and every node reconnects using the configuration from application.yml or the
            environment.
          </p>
          {descriptor.kind === 'message-store' ? MESSAGE_STORE_SWITCH_WARNING.body : null}
        </>
      ),
      confirmLabel: 'Reset to bootstrap',
      tone: 'danger',
      action: async () => {
        const next = await infraApi.reset(descriptor.kind)
        onStateChange(next)
        toast.success(`${descriptor.displayName} reset to bootstrap config`)
      },
    })
  }

  return (
    <Card
      as="article"
      className="connector-panel"
      title={
        <span className="row" id={descriptor.kind}>
          {descriptor.displayName}
          <span className="mono muted connector-panel__kind">{descriptor.kind}</span>
        </span>
      }
      description={descriptor.description ?? undefined}
      actions={
        <>
          <Button size="sm" icon={<Activity size={14} aria-hidden />} onClick={() => void runCheck()} loading={checking} disabled={!state}>
            Check health
          </Button>
          {state?.source === 'database' && canWrite ? (
            <Button size="sm" variant="danger-ghost" icon={<RotateCcw size={14} aria-hidden />} onClick={() => void reset()}>
              Reset to bootstrap
            </Button>
          ) : null}
          <Button size="sm" variant="primary" icon={<Settings2 size={14} aria-hidden />} onClick={onConfigure} disabled={!canWrite || descriptor.providers.length === 0}>
            Change provider
          </Button>
        </>
      }
    >
      {!state ? (
        <SkeletonLines lines={3} />
      ) : (
        <>
          <div className="connector-panel__plugline">
            <Socket name={provider?.displayName ?? state.provider} title={state.provider} source={sourceLabel(state.source)} down={health ? !health.healthy : false} />
            {provider ? <MaturityBadge maturity={provider.maturity} /> : null}
            <span className="connector-panel__health">
              <HealthLamp health={health} />
              {health?.latencyMs !== null && health?.latencyMs !== undefined ? <span className="muted tabular">{formatLatency(health.latencyMs)}</span> : null}
              {health?.message ? <span className="connector-panel__health-msg">{health.message}</span> : null}
            </span>
          </div>

          {check ? (
            <div className="connector-panel__check">
              <TestResult result={check} title={check.healthy ? 'Health check passed' : 'Health check failed'} />
            </div>
          ) : null}
          {checkError ? (
            <div className="connector-panel__check">
              <Callout tone="danger" title="Health check request failed">
                {checkError}
              </Callout>
            </div>
          ) : null}
          {state.lastError ? (
            <div className="connector-panel__check">
              <Callout tone="danger" title="Last error">
                <span className="mono">{state.lastError}</span>
              </Callout>
            </div>
          ) : null}

          <div className="connector-panel__columns">
            <div>
              <p className="connector-panel__subhead">Active settings</p>
              <SettingsSummary settings={state.settings} fields={provider?.fields} />
            </div>
            <div className="stack stack--sm">
              <p className="connector-panel__subhead">Configuration</p>
              <dl className="details-list">
                <div className="details-list__row">
                  <dt>Source</dt>
                  <dd>
                    {state.source === 'database' ? 'Set in this console (control-plane database)' : 'Bootstrap (application.yml or environment)'}
                  </dd>
                </div>
                {state.version !== null && state.version !== undefined ? (
                  <div className="details-list__row">
                    <dt>Version</dt>
                    <dd className="tabular">{state.version}</dd>
                  </div>
                ) : null}
                {state.updatedAt ? (
                  <div className="details-list__row">
                    <dt>Updated</dt>
                    <dd title={state.updatedAt}>
                      {formatRelative(state.updatedAt)}
                      {state.updatedBy ? ` by ${state.updatedBy}` : ''}
                    </dd>
                  </div>
                ) : null}
                <div className="details-list__row">
                  <dt>Tenant override</dt>
                  <dd>{descriptor.tenantOverridable ? 'Tenants can bring their own' : 'Platform-wide only'}</dd>
                </div>
              </dl>
              {health?.details && Object.keys(health.details).length > 0 ? (
                <>
                  <p className="connector-panel__subhead">Health details</p>
                  <DetailsList details={health.details} />
                </>
              ) : null}
            </div>
          </div>
        </>
      )}
    </Card>
  )
}

export function InfrastructurePage() {
  const { canWrite } = useAuth()
  const toast = useToast()
  const location = useLocation()
  const catalogue = useApi(connectorsApi.catalogue, 'catalogue')
  const states = useApi(infraApi.list, 'infrastructure', { intervalMs: 30_000 })
  const [editingKind, setEditingKind] = useState<string | null>(null)

  const stateByKind = useMemo(() => new Map((states.data ?? []).map((s) => [s.kind, s])), [states.data])
  const editing = catalogue.data?.find((d) => d.kind === editingKind) ?? null
  const editingState = editing ? stateByKind.get(editing.kind) : undefined

  // Deep link from the overview patch bay: /infrastructure#message-store
  useEffect(() => {
    const id = decodeURIComponent(location.hash.replace(/^#/, ''))
    if (!id || !catalogue.data || !states.data) return
    const el = document.getElementById(id)
    el?.closest('.connector-panel')?.scrollIntoView({ block: 'start' })
  }, [location.hash, catalogue.data, states.data])

  const replaceState = (next: ConnectorState) => {
    states.setData((list) => {
      const rest = (list ?? []).filter((s) => s.kind !== next.kind)
      return [...rest, next]
    })
  }

  const loading = catalogue.loading || states.loading
  const error = catalogue.error ?? states.error

  return (
    <>
      <PageHeader
        title="Infrastructure"
        description="Every dependency is a pluggable connector. Changes are tested first, applied without a restart and picked up by every node."
      />
      {error ? (
        <LoadError
          error={error}
          onRetry={() => {
            void catalogue.reload()
            void states.reload()
          }}
        />
      ) : null}
      {loading && !catalogue.data ? (
        <div className="stack">
          {Array.from({ length: 3 }, (_, i) => (
            <Card key={i}>
              <SkeletonLines lines={4} />
            </Card>
          ))}
        </div>
      ) : catalogue.data && catalogue.data.length === 0 ? (
        <EmptyState icon={<Cable size={20} aria-hidden />} title="No connector slots reported" description="The backend returned an empty connector catalogue." />
      ) : (
        <div className="stack">
          {catalogue.data?.map((descriptor) => (
            <ConnectorPanel
              key={descriptor.kind}
              descriptor={descriptor}
              state={stateByKind.get(descriptor.kind)}
              onConfigure={() => setEditingKind(descriptor.kind)}
              onStateChange={replaceState}
              canWrite={canWrite}
            />
          ))}
        </div>
      )}

      <Dialog
        open={editing !== null}
        onClose={() => setEditingKind(null)}
        size="xl"
        title={editing ? `Configure ${editing.displayName.toLowerCase()}` : ''}
        description={
          editing ? (
            <>
              Pick a provider and fill in its settings. Apply connects first; if that fails nothing changes.
              {editingState ? (
                <>
                  {' '}
                  Active now: <Badge mono>{editingState.provider}</Badge>
                </>
              ) : null}
            </>
          ) : undefined
        }
      >
        {editing ? (
          <ConnectorEditor
            descriptor={editing}
            current={editingState ? { provider: editingState.provider, settings: editingState.settings } : null}
            onTest={(request) => infraApi.test(editing.kind, request)}
            onApply={async (request) => {
              const next = await infraApi.apply(editing.kind, request)
              replaceState(next)
              toast.success(`${editing.displayName} now uses ${editing.providers.find((p) => p.id === next.provider)?.displayName ?? next.provider}`)
              setEditingKind(null)
            }}
            onCancel={() => setEditingKind(null)}
            switchWarning={editing.kind === 'message-store' ? MESSAGE_STORE_SWITCH_WARNING : undefined}
            applyLabel="Apply to all nodes"
            readOnly={!canWrite}
          />
        ) : null}
      </Dialog>
    </>
  )
}
