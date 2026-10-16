import { Check, X } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { errorMessage } from '../../api/http'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Callout, Spinner } from '../../components/ui/Feedback'
import { Field, Input, SearchInput } from '../../components/ui/Form'
import { Avatar, Segmented } from '../../components/ui/Misc'
import { useDebounced } from '../../hooks/misc'
import { cx } from '../../lib/util'
import type { User } from '../../types'
import type { Suggestion } from './Inbox'
import type { ChatActions } from './useChatSession'

type Mode = 'direct' | 'group'

interface Candidate {
  id: string
  displayName: string
  avatarUrl?: string | null
}

export function NewChatDialog({
  me,
  actions,
  allowGroups,
  allowSearch,
  suggestions,
  onClose,
}: {
  me: string
  actions: ChatActions
  allowGroups: boolean
  allowSearch: boolean
  suggestions: Suggestion[]
  onClose: () => void
}) {
  const [mode, setMode] = useState<Mode>('direct')
  const [q, setQ] = useState('')
  const [manualId, setManualId] = useState('')
  const [results, setResults] = useState<Candidate[]>([])
  const [searching, setSearching] = useState(false)
  const [members, setMembers] = useState<Candidate[]>([])
  const [title, setTitle] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const debounced = useDebounced(q.trim(), 250)

  useEffect(() => {
    if (!allowSearch) return
    let cancelled = false
    // oxlint-disable-next-line react/set-state-in-effect
    setSearching(true)
    actions
      .searchUsers(debounced)
      .then((users: User[]) => {
        if (!cancelled) setResults(users.filter((u) => u.id !== me && !u.deactivated).map((u) => ({ id: u.id, displayName: u.displayName || u.id, avatarUrl: u.avatarUrl })))
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
      .finally(() => {
        if (!cancelled) setSearching(false)
      })
    return () => {
      cancelled = true
    }
  }, [debounced, allowSearch, actions, me])

  const candidates: Candidate[] = [...suggestions.filter((s) => s.id !== me && !results.some((r) => r.id === s.id)), ...results]

  const startDirect = async (userId: string) => {
    if (!userId.trim()) return
    setBusy(true)
    setError(null)
    try {
      await actions.startDirect(userId.trim())
      onClose()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const createGroup = async (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim() || members.length === 0) return
    setBusy(true)
    setError(null)
    try {
      await actions.createGroup(title.trim(), members.map((m) => m.id))
      onClose()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const toggleMember = (c: Candidate) =>
    setMembers((list) => (list.some((m) => m.id === c.id) ? list.filter((m) => m.id !== c.id) : [...list, c]))

  const addManual = () => {
    const id = manualId.trim()
    if (!id || id === me) return
    if (mode === 'direct') void startDirect(id)
    else {
      setMembers((list) => (list.some((m) => m.id === id) ? list : [...list, { id, displayName: id }]))
      setManualId('')
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      dismissible={!busy}
      size="sm"
      title="New chat"
      onSubmit={mode === 'group' ? createGroup : undefined}
      footer={
        mode === 'group' ? (
          <>
            <Button variant="ghost" onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" loading={busy} disabled={!title.trim() || members.length === 0}>
              Create group
            </Button>
          </>
        ) : undefined
      }
    >
      {allowGroups ? (
        <Segmented
          label="Chat type"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'direct', label: 'Direct message' },
            { value: 'group', label: 'Group' },
          ]}
        />
      ) : null}

      {mode === 'group' ? (
        <Field label="Group name" required>
          {(c) => <Input {...c} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Launch crew" autoFocus />}
        </Field>
      ) : null}

      {mode === 'group' && members.length > 0 ? (
        <div className="chip-list" aria-label="Selected members">
          {members.map((m) => (
            <button key={m.id} type="button" className="chip chip--removable" onClick={() => toggleMember(m)} aria-label={`Remove ${m.displayName}`}>
              {m.displayName}
              <X size={12} aria-hidden />
            </button>
          ))}
        </div>
      ) : null}

      {allowSearch ? (
        <SearchInput value={q} onChange={setQ} label="Search users" placeholder="Search by name or ID" autoFocus={mode === 'direct'} />
      ) : (
        <p className="muted">User search is turned off for this tenant. Enter a user ID instead.</p>
      )}

      <div className="row">
        <Input mono value={manualId} onChange={(e) => setManualId(e.target.value)} placeholder="User ID" aria-label="User ID" onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            addManual()
          }
        }} />
        <Button onClick={addManual} disabled={!manualId.trim() || busy}>
          {mode === 'direct' ? 'Start chat' : 'Add'}
        </Button>
      </div>

      <div className="user-pick-list" role="list">
        {searching && candidates.length === 0 ? (
          <div className="user-pick-list__empty">
            <Spinner size={14} label="Searching" />
          </div>
        ) : null}
        {!searching && candidates.length === 0 ? <p className="user-pick-list__empty muted">No users found.</p> : null}
        {candidates.map((c) => {
          const selected = members.some((m) => m.id === c.id)
          return (
            <button
              key={c.id}
              type="button"
              role="listitem"
              className={cx('user-pick', selected && 'is-selected')}
              disabled={busy}
              onClick={() => (mode === 'direct' ? void startDirect(c.id) : toggleMember(c))}
              aria-pressed={mode === 'group' ? selected : undefined}
            >
              <Avatar id={c.id} name={c.displayName} src={c.avatarUrl} size={28} />
              <span className="user-pick__text">
                <span className="user-pick__name">{c.displayName}</span>
                <span className="user-pick__id mono">{c.id}</span>
              </span>
              {mode === 'group' && selected ? <Check size={16} aria-hidden className="user-pick__check" /> : null}
            </button>
          )
        })}
      </div>
      {error ? <Callout tone="danger">{error}</Callout> : null}
    </Dialog>
  )
}
