import { MessagesSquare, Plus, Users } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { tenantUsersApi } from '../../api/tenants'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { buttonClass } from '../../components/ui/classes'
import { Dialog } from '../../components/ui/Dialog'
import { Badge, Callout, EmptyState, LoadError } from '../../components/ui/Feedback'
import { Field, FormGrid, Input, SearchInput } from '../../components/ui/Form'
import { Avatar, Pagination } from '../../components/ui/Misc'
import { DataTable, type Column } from '../../components/ui/Table'
import { useDebounced } from '../../hooks/useDebounced'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { formatDate, formatRelative } from '../../lib/format'
import type { UpsertUserRequest, User } from '../../types'
import { playgroundLink, type TenantTabProps } from './tenantTab'

const PAGE = 50

function CreateUserDialog({ slug, onClose, onCreated }: { slug: string; onClose: () => void; onCreated: (u: User) => void }) {
  const [form, setForm] = useState<UpsertUserRequest>({ id: '', displayName: '', email: '', phone: '', avatarUrl: '' })
  const [submitted, setSubmitted] = useState(false)
  const create = useAction((body: UpsertUserRequest) => tenantUsersApi.upsert(slug, body), { toastError: false, success: (u) => `User ${u.displayName ?? u.id} saved` })
  const idError = !form.id.trim() ? 'Enter a user ID.' : /\s/.test(form.id) ? 'User IDs cannot contain spaces.' : undefined
  const nameError = !form.displayName.trim() ? 'Enter a display name.' : undefined

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    if (idError || nameError) return
    const res = await create.run({
      id: form.id.trim(),
      displayName: form.displayName.trim(),
      email: form.email?.trim() || undefined,
      phone: form.phone?.trim() || undefined,
      avatarUrl: form.avatarUrl?.trim() || undefined,
    })
    if (res) {
      onCreated(res)
      onClose()
    }
  }

  const set = (patch: Partial<UpsertUserRequest>) => setForm((f) => ({ ...f, ...patch }))

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!create.busy}
      title="Create user"
      description="Creates the user, or updates it if the ID already exists. Counts toward the tenant's user limit."
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.busy}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={create.busy}>
            Save user
          </Button>
        </>
      }
    >
      <FormGrid>
        <Field label="User ID" required hint="Your system's ID for this user." error={submitted ? idError : undefined}>
          {(c) => <Input {...c} mono value={form.id} onChange={(e) => set({ id: e.target.value })} placeholder="alice" autoFocus />}
        </Field>
        <Field label="Display name" required error={submitted ? nameError : undefined}>
          {(c) => <Input {...c} value={form.displayName} onChange={(e) => set({ displayName: e.target.value })} placeholder="Alice Martin" />}
        </Field>
        <Field label="Email">
          {(c) => <Input {...c} type="email" value={form.email} onChange={(e) => set({ email: e.target.value })} />}
        </Field>
        <Field label="Phone">
          {(c) => <Input {...c} type="tel" value={form.phone} onChange={(e) => set({ phone: e.target.value })} />}
        </Field>
        <Field label="Avatar URL" className="span-all">
          {(c) => <Input {...c} mono value={form.avatarUrl} onChange={(e) => set({ avatarUrl: e.target.value })} placeholder="https://" />}
        </Field>
      </FormGrid>
      {create.error ? <Callout tone="danger">{create.error.message}</Callout> : null}
    </Dialog>
  )
}

export function UsersTab({ tenant, canWrite }: TenantTabProps) {
  const [q, setQ] = useState('')
  const [offset, setOffset] = useState(0)
  const [creating, setCreating] = useState(false)
  const debounced = useDebounced(q.trim(), 300)
  const users = useApi(() => tenantUsersApi.list(tenant.slug, { q: debounced, offset, limit: PAGE }), `users:${tenant.slug}:${debounced}:${offset}`, { keepPrevious: true })

  const columns: Column<User>[] = [
    {
      key: 'user',
      header: 'User',
      cell: (u) => (
        <div className="row">
          <Avatar id={u.id} name={u.displayName} src={u.avatarUrl} size={28} online={u.online ?? undefined} />
          <div className="cell-primary">
            <span className="cell-primary__title">{u.displayName ?? u.id}</span>
            <span className="cell-primary__sub mono">{u.id}</span>
          </div>
        </div>
      ),
    },
    { key: 'email', header: 'Email', cell: (u) => u.email ?? <span className="muted">—</span>, hideBelow: 'md' },
    { key: 'phone', header: 'Phone', cell: (u) => u.phone ?? <span className="muted">—</span>, hideBelow: 'lg' },
    {
      key: 'status',
      header: 'Status',
      cell: (u) =>
        u.deactivated ? (
          <Badge tone="danger">Deactivated</Badge>
        ) : u.online ? (
          <Badge tone="ok" dot>
            Online
          </Badge>
        ) : u.lastSeenAt ? (
          <span className="secondary">Seen {formatRelative(u.lastSeenAt)}</span>
        ) : (
          <span className="muted">—</span>
        ),
    },
    { key: 'created', header: 'Created', cell: (u) => <span className="secondary nowrap">{formatDate(u.createdAt)}</span>, hideBelow: 'lg' },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      align: 'right',
      cell: (u) =>
        u.deactivated ? null : (
          <Link to={playgroundLink(tenant.slug, [u.id])} className={buttonClass('ghost', 'sm')}>
            <MessagesSquare size={14} aria-hidden />
            <span className="btn__label">Open in playground</span>
          </Link>
        ),
    },
  ]

  return (
    <div className="stack">
      <div className="toolbar toolbar--flush">
        <SearchInput
          value={q}
          onChange={(v) => {
            setQ(v)
            setOffset(0)
          }}
          label="Search users"
          placeholder="Search ID, name, email or phone"
        />
        <div className="spacer" />
        <Button variant="primary" icon={<Plus size={15} aria-hidden />} onClick={() => setCreating(true)} disabled={!canWrite}>
          Create user
        </Button>
      </div>
      {users.error ? <LoadError error={users.error} onRetry={() => void users.reload()} /> : null}
      <Card flush>
        <DataTable
          columns={columns}
          rows={users.data?.items}
          rowKey={(u) => u.id}
          loading={users.loading}
          caption="Users"
          empty={
            debounced ? (
              <EmptyState compact title="No users match" description="Try a different search." />
            ) : (
              <EmptyState
                compact
                icon={<Users size={18} aria-hidden />}
                title="No users yet"
                description="Users are created by the tenant's backend or on first sign-in. Create a couple here to try the playground."
              />
            )
          }
          footer={users.data && users.data.total > 0 ? <Pagination offset={offset} limit={PAGE} total={users.data.total} onChange={setOffset} itemLabel="users" /> : undefined}
        />
      </Card>
      {creating ? <CreateUserDialog slug={tenant.slug} onClose={() => setCreating(false)} onCreated={() => void users.reload()} /> : null}
    </div>
  )
}
