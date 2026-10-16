import { UserPlus, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { errorMessage } from '../../api/http'
import { tenantUsersApi } from '../../api/tenants'
import { Button, IconButton } from '../../components/ui/Button'
import { Callout, EmptyState, Spinner } from '../../components/ui/Feedback'
import { Field, Input, SearchInput } from '../../components/ui/Form'
import { Avatar } from '../../components/ui/Misc'
import { useDebounced } from '../../hooks/misc'
import { useApi } from '../../hooks/useApi'
import { cx } from '../../lib/util'
import { useAuth } from '../../state/auth'

/** Empty pane: choose an existing end user of the tenant or create one on the fly. */
export function UserPicker({ tenant, inUse, onPick, onClose }: { tenant: string; inUse: string[]; onPick: (userId: string) => void; onClose: () => void }) {
  const { canWrite } = useAuth()
  const [q, setQ] = useState('')
  const debounced = useDebounced(q.trim(), 250)
  const users = useApi(() => tenantUsersApi.list(tenant, { q: debounced, limit: 20 }), `pg-users:${tenant}:${debounced}`, { keepPrevious: true })
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const create = async (e: FormEvent) => {
    e.preventDefault()
    const userId = id.trim()
    if (!userId) return
    setCreating(true)
    setError(null)
    try {
      const user = await tenantUsersApi.upsert(tenant, { id: userId, displayName: name.trim() || userId })
      onPick(user.id)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setCreating(false)
    }
  }

  return (
    <article className="pane pane--picker" aria-label="Choose a user">
      <header className="pane__head">
        <div className="pane__who">
          <span className="pane__name">Choose a user</span>
          <span className="pane__id">The pane signs in as this user with an impersonation token.</span>
        </div>
        <IconButton size="sm" label="Close pane" icon={<X size={15} aria-hidden />} onClick={onClose} />
      </header>
      <div className="picker">
        <SearchInput value={q} onChange={setQ} label="Search users" placeholder="Search ID, name or email" />
        <div className="user-pick-list user-pick-list--tall" role="list">
          {users.loading ? (
            <div className="user-pick-list__empty">
              <Spinner size={14} label="Loading users" />
            </div>
          ) : null}
          {users.error ? <Callout tone="danger">{users.error.message}</Callout> : null}
          {users.data && users.data.items.length === 0 ? (
            <EmptyState compact title={debounced ? 'No users match' : 'This tenant has no users yet'} description="Create one below." />
          ) : null}
          {users.data?.items.map((u) => (
            <button key={u.id} type="button" role="listitem" className={cx('user-pick', u.deactivated && 'is-disabled')} disabled={u.deactivated} onClick={() => onPick(u.id)}>
              <Avatar id={u.id} name={u.displayName} src={u.avatarUrl} size={30} online={u.online ?? undefined} />
              <span className="user-pick__text">
                <span className="user-pick__name">{u.displayName ?? u.id}</span>
                <span className="user-pick__id mono">{u.id}</span>
              </span>
              {inUse.includes(u.id) ? <span className="user-pick__tag">Open in another pane</span> : null}
              {u.deactivated ? <span className="user-pick__tag">Deactivated</span> : null}
            </button>
          ))}
        </div>
        <form className="picker__create" onSubmit={create}>
          <p className="picker__create-title">Create a user</p>
          <div className="picker__create-row">
            <Field label="User ID">
              {(c) => <Input {...c} mono value={id} onChange={(e) => setId(e.target.value.replace(/\s/g, ''))} placeholder="carol" disabled={!canWrite} />}
            </Field>
            <Field label="Display name">
              {(c) => <Input {...c} value={name} onChange={(e) => setName(e.target.value)} placeholder="Carol" disabled={!canWrite} />}
            </Field>
            <Button type="submit" variant="primary" icon={<UserPlus size={15} aria-hidden />} loading={creating} disabled={!id.trim() || !canWrite}>
              Create and use
            </Button>
          </div>
          {error ? <Callout tone="danger">{error}</Callout> : null}
        </form>
      </div>
    </article>
  )
}
