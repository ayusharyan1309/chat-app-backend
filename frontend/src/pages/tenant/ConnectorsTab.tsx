import { Undo2 } from 'lucide-react'
import { useState } from 'react'
import { connectorsApi, infraApi } from '../../api/infra'
import { tenantConnectorsApi } from '../../api/tenants'
import { ConnectorEditor } from '../../components/connectors/ConnectorEditor'
import { HealthLamp } from '../../components/connectors/Health'
import { Socket } from '../../components/connectors/PatchBay'
import { SettingsSummary } from '../../components/connectors/SettingsSummary'
import { TENANT_STORE_SWITCH_WARNING } from '../../components/connectors/warnings'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Callout, LoadError, SkeletonLines } from '../../components/ui/Feedback'
import { Segmented } from '../../components/ui/Misc'
import { useApi } from '../../hooks/useApi'
import { formatLatency } from '../../lib/format'
import { useConfirm } from '../../state/confirm'
import { useToast } from '../../state/toast'
import type { ConnectorKindDescriptor, TenantConnectorKind, TenantConnectorState } from '../../types'
import type { TenantTabProps } from './types'

const OVERRIDABLE: ReadonlyArray<{ kind: TenantConnectorKind; title: string; description: string }> = [
  {
    kind: 'message-store',
    title: 'Message store',
    description: 'Where this tenant’s users, conversations and messages live. Dedicated stores isolate data and load.',
  },
  {
    kind: 'push',
    title: 'Push notifications',
    description: 'How offline users of this tenant are notified, for example with the tenant’s own FCM project.',
  },
]

type Mode = 'inherit' | 'dedicated'

function TenantConnectorCard({
  slug,
  descriptor,
  title,
  description,
  canWrite,
  onStoreChange,
}: {
  slug: string
  descriptor: ConnectorKindDescriptor
  title: string
  description: string
  canWrite: boolean
  onStoreChange: () => void
}) {
  const kind = descriptor.kind as TenantConnectorKind
  const toast = useToast()
  const confirm = useConfirm()
  const state = useApi(() => tenantConnectorsApi.get(slug, kind), `tenant-connector:${slug}:${kind}`)
  const platform = useApi(() => infraApi.get(kind), `infra:${kind}`)
  const [modeChoice, setModeChoice] = useState<Mode | null>(null)

  const current = state.data
  const dedicated = current && !current.inherited ? current : null
  const mode: Mode = modeChoice ?? (dedicated ? 'dedicated' : 'inherit')
  const platformProvider = descriptor.providers.find((p) => p.id === platform.data?.provider)
  const dedicatedProvider = dedicated ? descriptor.providers.find((p) => p.id === dedicated.provider) : undefined

  const applyState = (next: TenantConnectorState) => {
    state.setData(next)
    setModeChoice(null)
    if (kind === 'message-store') onStoreChange()
  }

  const revert = async () => {
    await confirm({
      title: `Revert ${title.toLowerCase()} to the platform default?`,
      body: (
        <>
          <p>
            The tenant switches back to the shared {platformProvider?.displayName ?? 'platform'} {title.toLowerCase()} and the dedicated settings are
            removed.
          </p>
          {kind === 'message-store' ? <p>Data in the dedicated store is not moved to the shared store.</p> : null}
        </>
      ),
      acknowledge: kind === 'message-store' ? 'I understand the dedicated store’s data will not be visible.' : undefined,
      confirmLabel: 'Revert to platform default',
      tone: 'danger',
      action: async () => {
        applyState(await tenantConnectorsApi.revert(slug, kind))
        toast.success(`${title} reverted to the platform default`)
      },
    })
  }

  return (
    <Card title={title} description={description}>
      {state.error ? <LoadError error={state.error} onRetry={() => void state.reload()} /> : null}
      {!current && !state.error ? <SkeletonLines lines={3} /> : null}
      {current ? (
        <div className="stack">
          <div className="row row--wrap row--between">
            <Segmented
              label={`${title} source`}
              value={mode}
              onChange={(m) => setModeChoice(m)}
              options={[
                { value: 'inherit', label: 'Inherit platform default' },
                { value: 'dedicated', label: 'Dedicated', disabled: !canWrite && !dedicated },
              ]}
            />
            <span className="connector-panel__health">
              {dedicated ? (
                <>
                  <span className="muted">Dedicated:</span>
                  <Socket name={dedicatedProvider?.displayName ?? dedicated.provider} title={dedicated.provider} down={dedicated.health ? !dedicated.health.healthy : false} />
                  <HealthLamp health={dedicated.health} />
                  {dedicated.health?.latencyMs !== null && dedicated.health?.latencyMs !== undefined ? (
                    <span className="muted tabular">{formatLatency(dedicated.health.latencyMs)}</span>
                  ) : null}
                </>
              ) : (
                <>
                  <span className="muted">Using platform default:</span>
                  <Socket name={platformProvider?.displayName ?? platform.data?.provider ?? '…'} title={platform.data?.provider} />
                </>
              )}
            </span>
          </div>

          {mode === 'inherit' && dedicated ? (
            <Callout
              tone="warn"
              title="This tenant currently has a dedicated configuration"
              actions={
                <Button size="sm" variant="danger" icon={<Undo2 size={14} aria-hidden />} onClick={() => void revert()} disabled={!canWrite}>
                  Revert to platform default
                </Button>
              }
            >
              Reverting removes the dedicated {dedicatedProvider?.displayName ?? dedicated.provider} settings.
            </Callout>
          ) : null}

          {mode === 'inherit' && !dedicated ? (
            <p className="secondary">
              The tenant shares the platform {title.toLowerCase()}
              {platformProvider ? ` (${platformProvider.displayName})` : ''}. Changes made on the Infrastructure page apply to it.
            </p>
          ) : null}

          {mode === 'dedicated' && dedicated && dedicated.settings ? (
            <details className="data-details">
              <summary>Active dedicated settings</summary>
              <div className="data-details__table">
                <SettingsSummary settings={dedicated.settings} fields={dedicatedProvider?.fields} />
              </div>
            </details>
          ) : null}

          {mode === 'dedicated' ? (
            <ConnectorEditor
              descriptor={descriptor}
              idPrefix={`tenant-${kind}`}
              current={dedicated ? { provider: dedicated.provider, settings: dedicated.settings } : null}
              initialProvider={dedicated?.provider ?? platform.data?.provider ?? null}
              onTest={(request) => tenantConnectorsApi.test(slug, kind, request)}
              onApply={async (request) => {
                const next = await tenantConnectorsApi.apply(slug, kind, request)
                applyState(next)
                toast.success(`${title} now uses a dedicated ${descriptor.providers.find((p) => p.id === request.provider)?.displayName ?? request.provider}`)
              }}
              onCancel={dedicated ? undefined : () => setModeChoice('inherit')}
              switchWarning={kind === 'message-store' ? TENANT_STORE_SWITCH_WARNING : undefined}
              warnWhenNoCurrent={kind === 'message-store'}
              applyLabel={dedicated ? 'Apply changes' : 'Use dedicated'}
              readOnly={!canWrite}
            />
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}

export function ConnectorsTab({ tenant, canWrite, reload }: TenantTabProps) {
  const catalogue = useApi(connectorsApi.catalogue, 'catalogue')
  if (catalogue.error) return <LoadError error={catalogue.error} onRetry={() => void catalogue.reload()} />
  if (!catalogue.data) return <SkeletonLines lines={6} />

  const slots = OVERRIDABLE.map((slot) => ({ ...slot, descriptor: catalogue.data?.find((d) => d.kind === slot.kind) })).filter(
    (slot): slot is typeof slot & { descriptor: ConnectorKindDescriptor } => !!slot.descriptor && slot.descriptor.tenantOverridable,
  )

  if (slots.length === 0) {
    return <Callout tone="info">The backend does not allow tenant overrides for any connector slot.</Callout>
  }

  return (
    <div className="stack">
      {slots.map((slot) => (
        <TenantConnectorCard
          key={slot.kind}
          slug={tenant.slug}
          descriptor={slot.descriptor}
          title={slot.title}
          description={slot.descriptor.description ?? slot.description}
          canWrite={canWrite}
          onStoreChange={() => void reload()}
        />
      ))}
    </div>
  )
}
