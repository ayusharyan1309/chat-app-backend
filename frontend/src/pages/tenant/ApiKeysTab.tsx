import { KeyRound, Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { apiKeysApi } from '../../api/tenants'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { SecretReveal } from '../../components/ui/Copy'
import { Dialog } from '../../components/ui/Dialog'
import { Badge, Callout, EmptyState, LoadError } from '../../components/ui/Feedback'
import { Field, Input } from '../../components/ui/Form'
import { DataTable, type Column } from '../../components/ui/Table'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { formatDate, formatRelative } from '../../lib/format'
import { useConfirm } from '../../state/confirm'
import { useToast } from '../../state/toast'
import type { ApiKey, ApiKeyCreated } from '../../types'
import type { TenantTabProps } from './tenantTab'

function CreateKeyDialog({ slug, onClose, onCreated }: { slug: string; onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState('')
  const [created, setCreated] = useState<ApiKeyCreated | null>(null)
  const create = useAction((n: string) => apiKeysApi.create(slug, n), { toastError: false })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    const res = await create.run(name.trim())
    if (res) {
      setCreated(res)
      onCreated()
    }
  }

  if (created) {
    return (
      <Dialog
        open
        onClose={onClose}
        dismissible={false}
        title="API key created"
        description={`"${created.name}" can now call the server API for this tenant.`}
        footer={
          <Button variant="primary" onClick={onClose}>
            I have stored the key
          </Button>
        }
      >
        <SecretReveal value={created.key} label="Server API key" note="Send it as the X-API-Key header from your backend. Never ship it in a client app." />
      </Dialog>
    )
  }

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!create.busy}
      size="sm"
      title="Create API key"
      description="Server-to-server key for provisioning users, minting tokens and posting system messages."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={create.busy} disabled={!name.trim()}>
            Create key
          </Button>
        </>
      }
    >
      <Field label="Name" required hint="Describe where it is used, e.g. production backend.">
        {(c) => <Input {...c} value={name} onChange={(e) => setName(e.target.value)} placeholder="Production backend" autoFocus />}
      </Field>
      {create.error ? <Callout tone="danger">{create.error.message}</Callout> : null}
    </Dialog>
  )
}

export function ApiKeysTab({ tenant, canWrite }: TenantTabProps) {
  const keys = useApi(() => apiKeysApi.list(tenant.slug), `keys:${tenant.slug}`)
  const confirm = useConfirm()
  const toast = useToast()
  const [creating, setCreating] = useState(false)

  const revoke = (key: ApiKey) =>
    confirm({
      title: `Revoke "${key.name}"?`,
      body: <p>Requests using this key start failing with 401 right away. This cannot be undone.</p>,
      confirmLabel: 'Revoke key',
      tone: 'danger',
      action: async () => {
        await apiKeysApi.revoke(tenant.slug, key.id)
        toast.success(`Key "${key.name}" revoked`)
        await keys.reload()
      },
    })

  const rows = [...(keys.data ?? [])].sort((a, b) => Number(Boolean(a.revokedAt)) - Number(Boolean(b.revokedAt)) || b.createdAt.localeCompare(a.createdAt))

  const columns: Column<ApiKey>[] = [
    {
      key: 'name',
      header: 'Name',
      cell: (k) => (
        <div className="cell-primary">
          <span className="cell-primary__title">{k.name}</span>
          <span className="cell-primary__sub mono">{k.prefix}…</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (k) =>
        k.revokedAt ? (
          <Badge tone="danger" title={`Revoked ${formatDate(k.revokedAt)}`}>
            Revoked
          </Badge>
        ) : (
          <Badge tone="ok" dot>
            Active
          </Badge>
        ),
    },
    { key: 'used', header: 'Last used', cell: (k) => (k.lastUsedAt ? <span title={k.lastUsedAt}>{formatRelative(k.lastUsedAt)}</span> : <span className="muted">Never</span>) },
    {
      key: 'created',
      header: 'Created',
      hideBelow: 'md',
      cell: (k) => (
        <span className="secondary">
          {formatDate(k.createdAt)}
          {k.createdBy ? ` by ${k.createdBy}` : ''}
        </span>
      ),
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      align: 'right',
      cell: (k) =>
        k.revokedAt ? null : (
          <Button size="sm" variant="danger-ghost" onClick={() => void revoke(k)} disabled={!canWrite}>
            Revoke
          </Button>
        ),
    },
  ]

  return (
    <div className="stack">
      <Card
        title="Server API keys"
        description="Keys authenticate the tenant's backend (X-API-Key). The full key is shown once, when it is created."
        actions={
          <Button variant="primary" size="sm" icon={<Plus size={14} aria-hidden />} onClick={() => setCreating(true)} disabled={!canWrite}>
            Create API key
          </Button>
        }
        flush
      >
        {keys.error ? (
          <div className="card-pad">
            <LoadError error={keys.error} onRetry={() => void keys.reload()} />
          </div>
        ) : null}
        <DataTable
          columns={columns}
          rows={keys.data ? rows : undefined}
          rowKey={(k) => k.id}
          loading={keys.loading}
          rowClassName={(k) => (k.revokedAt ? 'is-dimmed' : undefined)}
          caption="API keys"
          empty={<EmptyState compact icon={<KeyRound size={18} aria-hidden />} title="No API keys" description="Create one for the tenant's backend." />}
        />
      </Card>
      {creating ? <CreateKeyDialog slug={tenant.slug} onClose={() => setCreating(false)} onCreated={() => void keys.reload()} /> : null}
    </div>
  )
}
