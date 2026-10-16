import { Pencil, Plus, RefreshCw, Send, Trash2, Webhook as WebhookIcon } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { webhooksApi } from '../../api/tenants'
import { Button, IconButton } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { SecretReveal } from '../../components/ui/Copy'
import { Dialog } from '../../components/ui/Dialog'
import { Badge, Callout, EmptyState, LoadError, type Tone } from '../../components/ui/Feedback'
import { Checkbox, Field, Input, Textarea, Toggle } from '../../components/ui/Form'
import { DataTable, type Column } from '../../components/ui/Table'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { formatDateTime, formatLatency, formatRelative } from '../../lib/format'
import { cx } from '../../lib/util'
import { useConfirm } from '../../state/confirm'
import { useToast } from '../../state/toast'
import { WEBHOOK_EVENTS, type DeliveryStatus, type Webhook, type WebhookDelivery, type WebhookInput } from '../../types'
import { featureEnabled, tenantPath, type TenantTabProps } from './types'

const DELIVERY_TONE: Record<DeliveryStatus, Tone> = { SUCCESS: 'ok', FAILED: 'danger', PENDING: 'warn' }
const DELIVERY_LABEL: Record<DeliveryStatus, string> = { SUCCESS: 'Delivered', FAILED: 'Failed', PENDING: 'Retrying' }

function DeliveryBadge({ status }: { status: DeliveryStatus }) {
  return (
    <Badge tone={DELIVERY_TONE[status] ?? 'neutral'} dot>
      {DELIVERY_LABEL[status] ?? status}
    </Badge>
  )
}

function WebhookDialog({ slug, webhook, onClose, onSaved }: { slug: string; webhook: Webhook | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<WebhookInput>(() => ({
    url: webhook?.url ?? '',
    events: webhook?.events ?? WEBHOOK_EVENTS.map((e) => e.id),
    description: webhook?.description ?? '',
    active: webhook?.active ?? true,
  }))
  const [submitted, setSubmitted] = useState(false)
  const [createdSecret, setCreatedSecret] = useState<string | null>(null)
  const save = useAction(
    (body: WebhookInput) => (webhook ? webhooksApi.update(slug, webhook.id, body) : webhooksApi.create(slug, body)),
    { toastError: false },
  )

  const urlError = !/^https?:\/\/\S+$/i.test(form.url.trim()) ? 'Enter an http(s) URL.' : undefined
  const eventsError = form.events.length === 0 ? 'Pick at least one event.' : undefined
  const allSelected = form.events.length === WEBHOOK_EVENTS.length

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (urlError || eventsError) return
    const res = await save.run({ ...form, url: form.url.trim(), description: form.description?.trim() || null })
    if (!res) return
    onSaved()
    if (!webhook && res.secret && !/^\*+$/.test(res.secret)) setCreatedSecret(res.secret)
    else onClose()
  }

  if (createdSecret) {
    return (
      <Dialog
        open
        onClose={onClose}
        dismissible={false}
        title="Webhook created"
        description="Verify deliveries with this signing secret: X-Chat-Signature is sha256=HMAC(secret, timestamp + '.' + body)."
        footer={
          <Button variant="primary" onClick={onClose}>
            I have stored the secret
          </Button>
        }
      >
        <SecretReveal value={createdSecret} label="Signing secret" />
      </Dialog>
    )
  }

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!save.busy}
      size="md"
      title={webhook ? 'Edit webhook' : 'Add webhook'}
      description="Events are POSTed as JSON, signed, and retried up to 5 times with backoff. Respond with 2xx within 10 seconds."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={save.busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={save.busy}>
            {webhook ? 'Save webhook' : 'Add webhook'}
          </Button>
        </>
      }
    >
      <Field label="Endpoint URL" required error={submitted ? urlError : undefined}>
        {(c) => <Input {...c} mono value={form.url} onChange={(e) => setForm((f) => ({ ...f, url: e.target.value }))} placeholder="https://api.acme.io/chat/webhooks" autoFocus />}
      </Field>
      <fieldset className="fieldset">
        <legend className="field__label">
          Events <span className="field__required" aria-hidden>*</span>
        </legend>
        <div className="row row--between fieldset__head">
          <span className="field__hint">{form.events.length} of {WEBHOOK_EVENTS.length} selected</span>
          <Button size="sm" variant="ghost" onClick={() => setForm((f) => ({ ...f, events: allSelected ? [] : WEBHOOK_EVENTS.map((ev) => ev.id) }))}>
            {allSelected ? 'Clear all' : 'Select all'}
          </Button>
        </div>
        <div className="checkbox-grid">
          {WEBHOOK_EVENTS.map((ev) => (
            <Checkbox
              key={ev.id}
              checked={form.events.includes(ev.id)}
              onChange={(checked) =>
                setForm((f) => ({ ...f, events: checked ? [...f.events, ev.id] : f.events.filter((x) => x !== ev.id) }))
              }
              label={ev.label}
              description={<span className="mono">{ev.id}</span>}
            />
          ))}
        </div>
        {submitted && eventsError ? <p className="field__error">{eventsError}</p> : null}
      </fieldset>
      <Field label="Description">
        {(c) => <Textarea {...c} rows={2} value={form.description ?? ''} onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} placeholder="What consumes these events" />}
      </Field>
      <Toggle checked={form.active} onChange={(v) => setForm((f) => ({ ...f, active: v }))} label="Active" description="Paused webhooks keep their configuration but receive nothing." />
      {save.error ? <Callout tone="danger">{save.error.message}</Callout> : null}
    </Dialog>
  )
}

function Deliveries({ slug, webhook }: { slug: string; webhook: Webhook }) {
  const deliveries = useApi(() => webhooksApi.deliveries(slug, webhook.id), `deliveries:${slug}:${webhook.id}`, { intervalMs: 15_000 })
  const columns: Column<WebhookDelivery>[] = [
    { key: 'at', header: 'Time', cell: (d) => <span title={formatDateTime(d.createdAt)}>{formatRelative(d.createdAt)}</span> },
    { key: 'event', header: 'Event', cell: (d) => <span className="mono">{d.eventType}</span> },
    { key: 'status', header: 'Status', cell: (d) => <DeliveryBadge status={d.status} /> },
    { key: 'code', header: 'Response', align: 'right', cell: (d) => (d.responseCode ? <span className="mono">{d.responseCode}</span> : <span className="muted">—</span>) },
    { key: 'attempts', header: 'Attempts', align: 'right', cell: (d) => d.attempts, hideBelow: 'md' },
    { key: 'duration', header: 'Duration', align: 'right', cell: (d) => formatLatency(d.durationMs), hideBelow: 'md' },
    { key: 'error', header: 'Error', cell: (d) => (d.error ? <span className="delivery-error" title={d.error}>{d.error}</span> : null), hideBelow: 'lg' },
  ]
  return (
    <Card
      title="Recent deliveries"
      description={<span className="mono">{webhook.url}</span>}
      actions={<IconButton label="Refresh deliveries" icon={<RefreshCw size={15} aria-hidden />} onClick={() => void deliveries.reload()} />}
      flush
    >
      {deliveries.error ? (
        <div className="card-pad">
          <LoadError error={deliveries.error} onRetry={() => void deliveries.reload()} />
        </div>
      ) : null}
      <DataTable
        dense
        columns={columns}
        rows={deliveries.data}
        rowKey={(d) => d.id}
        loading={deliveries.loading}
        caption="Webhook deliveries"
        empty={<EmptyState compact title="No deliveries yet" description="Send a test event or wait for real traffic." />}
      />
    </Card>
  )
}

export function WebhooksTab({ tenant, canWrite }: TenantTabProps) {
  const enabled = featureEnabled(tenant, 'webhooks')
  const hooks = useApi(() => webhooksApi.list(tenant.slug), enabled ? `webhooks:${tenant.slug}` : null)
  const confirm = useConfirm()
  const toast = useToast()
  const [editing, setEditing] = useState<Webhook | 'new' | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [deliveriesKey, setDeliveriesKey] = useState(0)
  const [testing, setTesting] = useState<string | null>(null)

  if (!enabled) {
    return (
      <EmptyState
        icon={<WebhookIcon size={20} aria-hidden />}
        title="Webhooks are not enabled for this tenant"
        description="Turn on the webhooks feature to push message and conversation events to the tenant's backend."
        action={
          <Link to={tenantPath(tenant.slug, 'features')} className="btn btn--primary">
            <span className="btn__label">Go to features</span>
          </Link>
        }
      />
    )
  }

  const selected = hooks.data?.find((h) => h.id === selectedId) ?? hooks.data?.[0] ?? null

  const sendTest = async (hook: Webhook) => {
    setTesting(hook.id)
    try {
      const delivery = await webhooksApi.test(tenant.slug, hook.id)
      if (delivery.status === 'SUCCESS') toast.success('Test event delivered', `${hook.url} answered ${delivery.responseCode ?? ''} in ${formatLatency(delivery.durationMs)}`)
      else toast.error(delivery.error ?? `Status ${delivery.status}${delivery.responseCode ? `, HTTP ${delivery.responseCode}` : ''}`, 'Test event was not delivered')
      setSelectedId(hook.id)
      setDeliveriesKey((k) => k + 1)
    } catch (err) {
      toast.error(err, 'Could not send the test event')
    } finally {
      setTesting(null)
    }
  }

  const remove = (hook: Webhook) =>
    confirm({
      title: 'Delete this webhook?',
      body: (
        <p>
          <span className="mono">{hook.url}</span> stops receiving events. Pending retries are dropped.
        </p>
      ),
      confirmLabel: 'Delete webhook',
      tone: 'danger',
      action: async () => {
        await webhooksApi.remove(tenant.slug, hook.id)
        toast.success('Webhook deleted')
        await hooks.reload()
      },
    })

  return (
    <div className="stack">
      <Card
        title="Webhooks"
        description="Endpoints on the tenant's backend that receive signed event notifications."
        actions={
          <Button variant="primary" size="sm" icon={<Plus size={14} aria-hidden />} onClick={() => setEditing('new')} disabled={!canWrite}>
            Add webhook
          </Button>
        }
        flush
      >
        {hooks.error ? (
          <div className="card-pad">
            <LoadError error={hooks.error} onRetry={() => void hooks.reload()} />
          </div>
        ) : null}
        {hooks.data && hooks.data.length === 0 ? (
          <EmptyState compact icon={<WebhookIcon size={18} aria-hidden />} title="No webhooks yet" description="Add an endpoint to receive message and conversation events." />
        ) : null}
        <ul className="webhook-list">
          {(hooks.data ?? []).map((hook) => (
            <li key={hook.id} className={cx('webhook-row', selected?.id === hook.id && 'is-selected')}>
              <button type="button" className="webhook-row__main" onClick={() => setSelectedId(hook.id)} aria-pressed={selected?.id === hook.id}>
                <span className="webhook-row__url mono">{hook.url}</span>
                <span className="webhook-row__meta">
                  {hook.active ? (
                    <Badge tone="ok" dot>
                      Active
                    </Badge>
                  ) : (
                    <Badge>Paused</Badge>
                  )}
                  <span className="muted">
                    {hook.events.length} {hook.events.length === 1 ? 'event' : 'events'}
                  </span>
                  {hook.description ? <span className="muted truncate">{hook.description}</span> : null}
                </span>
              </button>
              <div className="webhook-row__actions">
                <Button size="sm" icon={<Send size={13} aria-hidden />} onClick={() => void sendTest(hook)} loading={testing === hook.id} disabled={!canWrite}>
                  Send test
                </Button>
                <IconButton size="sm" label="Edit webhook" icon={<Pencil size={14} aria-hidden />} onClick={() => setEditing(hook)} disabled={!canWrite} />
                <IconButton size="sm" variant="danger-ghost" label="Delete webhook" icon={<Trash2 size={14} aria-hidden />} onClick={() => void remove(hook)} disabled={!canWrite} />
              </div>
            </li>
          ))}
        </ul>
      </Card>
      {selected ? <Deliveries key={`${selected.id}:${deliveriesKey}`} slug={tenant.slug} webhook={selected} /> : null}
      {editing ? (
        <WebhookDialog slug={tenant.slug} webhook={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => void hooks.reload()} />
      ) : null}
    </div>
  )
}
