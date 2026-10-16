import { FlaskConical, Plug } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { errorMessage } from '../../api/http'
import { cx } from '../../lib/util'
import { useConfirm } from '../../state/confirm'
import type { ConnectorConfigRequest, ConnectorKindDescriptor, ConnectorSettings, HealthResult, ProviderDescriptor } from '../../types'
import { Button } from '../ui/Button'
import { Callout, EmptyState } from '../ui/Feedback'
import { ConnectorForm } from './ConnectorForm'
import { TestResult } from './Health'
import { ProviderPicker } from './ProviderPicker'
import { initialValues, serializeValues, validateValues, valuesEqual, type FormErrors, type FormValues } from './schema'

export interface ProviderSwitchWarning {
  title: ReactNode
  body: ReactNode
  acknowledge: ReactNode
  confirmLabel: string
}

export interface ConnectorEditorProps {
  descriptor: ConnectorKindDescriptor
  /** What is configured right now (null = nothing / inherited). */
  current: { provider: string; settings: ConnectorSettings } | null
  /** Provider to preselect; defaults to the current one, then the first in the catalogue. */
  initialProvider?: string | null
  onTest: (request: ConnectorConfigRequest) => Promise<HealthResult>
  onApply: (request: ConnectorConfigRequest) => Promise<unknown>
  onCancel?: () => void
  /** Shown as a destructive confirmation before applying a provider different from `current`. */
  switchWarning?: ProviderSwitchWarning
  /** Also require the switch confirmation when nothing is configured yet (e.g. inherited -> dedicated). */
  warnWhenNoCurrent?: boolean
  applyLabel?: string
  readOnly?: boolean
  /** Extra content above the actions (e.g. a scope note). */
  note?: ReactNode
  idPrefix?: string
}

type Drafts = Record<string, FormValues>

function baselineFor(provider: ProviderDescriptor, current: ConnectorEditorProps['current']): FormValues {
  return initialValues(provider.fields, current && current.provider === provider.id ? current.settings : null)
}

/**
 * Provider picker + schema-driven form + test + apply. The same editor configures platform
 * infrastructure and tenant overrides.
 */
export function ConnectorEditor({
  descriptor,
  current,
  initialProvider,
  onTest,
  onApply,
  onCancel,
  switchWarning,
  warnWhenNoCurrent = false,
  applyLabel = 'Apply',
  readOnly,
  note,
  idPrefix = descriptor.kind,
}: ConnectorEditorProps) {
  const confirm = useConfirm()
  const providers = descriptor.providers
  const startId = initialProvider ?? current?.provider ?? providers[0]?.id ?? null

  const [providerId, setProviderId] = useState<string | null>(startId)
  // Per-provider drafts so switching back and forth keeps what was typed.
  const [drafts, setDrafts] = useState<Drafts>(() => {
    const start = providers.find((p) => p.id === startId)
    return start ? { [start.id]: baselineFor(start, current) } : {}
  })
  const [errors, setErrors] = useState<FormErrors>({})
  const [testing, setTesting] = useState(false)
  const [applying, setApplying] = useState(false)
  const [testResult, setTestResult] = useState<HealthResult | null>(null)
  const [testError, setTestError] = useState<string | null>(null)
  const [applyError, setApplyError] = useState<string | null>(null)

  const provider = useMemo(() => providers.find((p) => p.id === providerId) ?? null, [providers, providerId])
  const baseline = useMemo(() => (provider ? baselineFor(provider, current) : {}), [provider, current])
  const values = provider ? (drafts[provider.id] ?? baseline) : {}
  const isSwitch = !!provider && !!current && current.provider !== provider.id
  const needsWarning = !!switchWarning && !!provider && (isSwitch || (warnWhenNoCurrent && !current))
  const dirty = !!provider && (isSwitch || !current || !valuesEqual(provider.fields, values, baseline))

  const resetFeedback = () => {
    setTestResult(null)
    setTestError(null)
    setApplyError(null)
  }

  const selectProvider = (id: string) => {
    if (id === providerId) return
    const next = providers.find((p) => p.id === id)
    if (!next) return
    setProviderId(id)
    setDrafts((d) => (d[id] ? d : { ...d, [id]: baselineFor(next, current) }))
    setErrors({})
    resetFeedback()
  }

  const setValue = (key: string, value: string | boolean) => {
    if (!provider) return
    setDrafts((d) => ({ ...d, [provider.id]: { ...(d[provider.id] ?? baseline), [key]: value } }))
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next[key]
        return next
      })
    }
    resetFeedback()
  }

  const buildRequest = (): ConnectorConfigRequest | null => {
    if (!provider) return null
    const found = validateValues(provider.fields, values)
    setErrors(found)
    const firstInvalid = Object.keys(found)[0]
    if (firstInvalid) {
      const el = document.querySelector<HTMLElement>(`[data-field="${idPrefix}-${firstInvalid}"] input, [data-field="${idPrefix}-${firstInvalid}"] textarea, [data-field="${idPrefix}-${firstInvalid}"] select`)
      el?.focus()
      return null
    }
    return { provider: provider.id, settings: serializeValues(provider.fields, values) }
  }

  const test = async () => {
    const request = buildRequest()
    if (!request) return
    setTesting(true)
    resetFeedback()
    try {
      setTestResult(await onTest(request))
    } catch (err) {
      setTestError(errorMessage(err))
    } finally {
      setTesting(false)
    }
  }

  const apply = async () => {
    const request = buildRequest()
    if (!request) return
    if (needsWarning && switchWarning) {
      const ok = await confirm({
        title: switchWarning.title,
        body: switchWarning.body,
        acknowledge: switchWarning.acknowledge,
        confirmLabel: switchWarning.confirmLabel,
        tone: 'danger',
      })
      if (!ok) return
    }
    setApplying(true)
    setApplyError(null)
    try {
      await onApply(request)
    } catch (err) {
      setApplyError(errorMessage(err))
    } finally {
      setApplying(false)
    }
  }

  if (providers.length === 0) {
    return <EmptyState icon={<Plug size={20} aria-hidden />} title="No providers available" description="The backend did not report any providers for this slot." />
  }

  return (
    <div className="connector-editor">
      <div className="connector-editor__grid">
        <section className="connector-editor__providers" aria-labelledby={`${idPrefix}-providers-title`}>
          <h3 className="connector-editor__section-title" id={`${idPrefix}-providers-title`}>
            Provider
          </h3>
          <ProviderPicker
            providers={providers}
            value={providerId}
            onChange={selectProvider}
            activeProvider={current?.provider}
            label={`${descriptor.displayName} provider`}
            disabled={readOnly || applying}
          />
        </section>
        <section className="connector-editor__settings" aria-labelledby={`${idPrefix}-settings-title`}>
          <div className="connector-editor__settings-head">
            <h3 className="connector-editor__section-title" id={`${idPrefix}-settings-title`}>
              {provider ? `${provider.displayName} settings` : 'Settings'}
            </h3>
            {provider ? (
              <span className="muted connector-editor__count">
                {provider.fields.filter((f) => f.required).length} required, {provider.fields.filter((f) => !f.required).length} optional
              </span>
            ) : null}
          </div>
          {provider?.description ? <p className="connector-editor__provider-desc">{provider.description}</p> : null}
          {provider ? (
            <ConnectorForm
              key={provider.id}
              fields={provider.fields}
              values={values}
              baseline={current?.provider === provider.id ? baseline : undefined}
              onChange={setValue}
              errors={errors}
              disabled={readOnly || applying}
              idPrefix={idPrefix}
            />
          ) : (
            <p className="muted">Pick a provider to configure it.</p>
          )}
          <div className="connector-editor__feedback" aria-live="polite">
            {testResult ? <TestResult result={testResult} /> : null}
            {testError ? (
              <Callout tone="danger" title="Test request failed">
                {testError}
              </Callout>
            ) : null}
            {applyError ? (
              <Callout tone="danger" title="Not applied. Nothing changed.">
                {applyError}
              </Callout>
            ) : null}
            {needsWarning && switchWarning ? (
              <Callout tone="danger" title={switchWarning.title}>
                {switchWarning.body}
              </Callout>
            ) : null}
            {note}
          </div>
        </section>
      </div>
      <div className={cx('connector-editor__actions')}>
        <Button icon={<FlaskConical size={15} aria-hidden />} onClick={() => void test()} loading={testing} disabled={!provider || applying || readOnly}>
          Test connection
        </Button>
        <span className="connector-editor__hint muted">{dirty ? 'Test before applying. Apply connects first and changes nothing if it fails.' : 'No changes yet.'}</span>
        <div className="spacer" />
        {onCancel ? (
          <Button variant="ghost" onClick={onCancel} disabled={applying}>
            Cancel
          </Button>
        ) : null}
        <Button
          variant={needsWarning ? 'danger' : 'primary'}
          onClick={() => void apply()}
          loading={applying}
          disabled={!provider || !dirty || testing || readOnly}
        >
          {applyLabel}
        </Button>
      </div>
    </div>
  )
}
