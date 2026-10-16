import { Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { adminsApi } from '../api/admin'
import { PageHeader } from '../components/layout/PageHeader'
import { Button, IconButton } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Dialog } from '../components/ui/Dialog'
import { Badge, Callout, EmptyState, LoadError, type Tone } from '../components/ui/Feedback'
import { Field, FormGrid, Input, Select } from '../components/ui/Form'
import { Avatar } from '../components/ui/Misc'
import { DataTable, type Column } from '../components/ui/Table'
import { useAction } from '../hooks/useAction'
import { useApi } from '../hooks/useApi'
import { formatDate, formatRelative } from '../lib/format'
import { isEmail } from '../lib/validation'
import { useAuth } from '../state/auth'
import { useConfirm } from '../state/confirm'
import { useToast } from '../state/toast'
import { ADMIN_ROLES, type Admin, type AdminRole, type CreateAdminRequest } from '../types'

const ROLE_TONE: Record<AdminRole, Tone> = { OWNER: 'accent', ADMIN: 'info', VIEWER: 'neutral' }
const ROLE_HELP: Record<AdminRole, string> = {
  OWNER: 'Full access, including managing other admins.',
  ADMIN: 'Full write access to infrastructure, tenants and plans.',
  VIEWER: 'Read-only. Every change is rejected.',
}

function CreateAdminDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [form, setForm] = useState<CreateAdminRequest>({ email: '', name: '', password: '', role: 'ADMIN' })
  const [submitted, setSubmitted] = useState(false)
  const create = useAction(adminsApi.create, { toastError: false, success: (a) => `${a.name || a.email} can now sign in` })
  const errors = {
    email: !isEmail(form.email) ? 'Enter a valid email address.' : undefined,
    name: !form.name.trim() ? 'Enter a name.' : undefined,
    password: form.password.length < 8 ? 'Use at least 8 characters.' : undefined,
  }
  const invalid = Object.values(errors).some(Boolean)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (invalid) return
    const res = await create.run({ ...form, email: form.email.trim(), name: form.name.trim() })
    if (res) {
      onCreated()
      onClose()
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!create.busy}
      title="Add admin"
      description="Share the initial password over a secure channel and ask them to change it after signing in."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={create.busy}>
            Add admin
          </Button>
        </>
      }
    >
      <FormGrid>
        <Field label="Name" required error={submitted ? errors.name : undefined}>
          {(c) => <Input {...c} value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} autoFocus />}
        </Field>
        <Field label="Email" required error={submitted ? errors.email : undefined}>
          {(c) => <Input {...c} type="email" autoComplete="off" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} />}
        </Field>
        <Field label="Initial password" required error={submitted ? errors.password : undefined}>
          {(c) => <Input {...c} type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))} />}
        </Field>
        <Field label="Role" hint={ROLE_HELP[form.role]}>
          {(c) => (
            <Select {...c} value={form.role} onChange={(e) => setForm((f) => ({ ...f, role: e.target.value as AdminRole }))}>
              {ADMIN_ROLES.map((r) => (
                <option key={r} value={r}>
                  {r.charAt(0) + r.slice(1).toLowerCase()}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </FormGrid>
      {create.error ? <Callout tone="danger">{create.error.message}</Callout> : null}
    </Dialog>
  )
}

export function AdminsPage() {
  const { admin: me, canWrite } = useAuth()
  const confirm = useConfirm()
  const toast = useToast()
  const admins = useApi(adminsApi.list, 'admins')
  const [creating, setCreating] = useState(false)
  const owners = (admins.data ?? []).filter((a) => a.role === 'OWNER').length

  const remove = (a: Admin) =>
    confirm({
      title: `Remove ${a.name || a.email}?`,
      body: <p>They are signed out and can no longer use this console.</p>,
      confirmLabel: 'Remove admin',
      tone: 'danger',
      action: async () => {
        await adminsApi.remove(a.id)
        toast.success(`${a.name || a.email} removed`)
        await admins.reload()
      },
    })

  const columns: Column<Admin>[] = [
    {
      key: 'admin',
      header: 'Admin',
      cell: (a) => (
        <div className="row">
          <Avatar id={a.id} name={a.name || a.email} size={28} />
          <div className="cell-primary">
            <span className="cell-primary__title">
              {a.name || a.email}
              {a.id === me?.id ? <span className="muted"> (you)</span> : null}
            </span>
            <span className="cell-primary__sub">{a.email}</span>
          </div>
        </div>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      cell: (a) => (
        <Badge tone={ROLE_TONE[a.role] ?? 'neutral'} title={ROLE_HELP[a.role]}>
          {a.role.charAt(0) + a.role.slice(1).toLowerCase()}
        </Badge>
      ),
    },
    { key: 'last', header: 'Last sign-in', cell: (a) => (a.lastLoginAt ? <span title={a.lastLoginAt}>{formatRelative(a.lastLoginAt)}</span> : <span className="muted">Never</span>) },
    { key: 'created', header: 'Added', cell: (a) => <span className="secondary">{formatDate(a.createdAt)}</span>, hideBelow: 'md' },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      align: 'right',
      cell: (a) => {
        const self = a.id === me?.id
        const lastOwner = a.role === 'OWNER' && owners <= 1
        const reason = self ? 'You cannot remove yourself' : lastOwner ? 'The last owner cannot be removed' : `Remove ${a.name || a.email}`
        return <IconButton size="sm" variant="danger-ghost" label={reason} icon={<Trash2 size={14} aria-hidden />} onClick={() => void remove(a)} disabled={!canWrite || self || lastOwner} />
      },
    },
  ]

  return (
    <>
      <PageHeader
        title="Admins"
        description="People who can sign in to this console. Owners and admins can change everything; viewers can only look."
        actions={
          <Button variant="primary" icon={<Plus size={16} aria-hidden />} onClick={() => setCreating(true)} disabled={!canWrite}>
            Add admin
          </Button>
        }
      />
      {admins.error ? <LoadError error={admins.error} onRetry={() => void admins.reload()} /> : null}
      <Card flush>
        <DataTable
          columns={columns}
          rows={admins.data}
          rowKey={(a) => a.id}
          loading={admins.loading}
          caption="Admins"
          empty={<EmptyState compact icon={<ShieldCheck size={18} aria-hidden />} title="No admins" />}
        />
      </Card>
      {creating ? <CreateAdminDialog onClose={() => setCreating(false)} onCreated={() => void admins.reload()} /> : null}
    </>
  )
}
