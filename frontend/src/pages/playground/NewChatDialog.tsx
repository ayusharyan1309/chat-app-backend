import { Check, X } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Callout, Spinner } from '../../components/ui/Feedback'
import { Field, Input, SearchInput } from '../../components/ui/Form'
import { Avatar, Segmented } from '../../components/ui/Misc'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import { useDebounced } from '../../hooks/useDebounced'
import { cx } from '../../lib/util'
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
  const [members, setMembers] = useState<Candidate[]>([])
  const [title, setTitle] = useState('')
  const debounced = useDebounced(q.trim(), 250)
  // Responses for an older query are discarded by useApi's key guard.
  const search = useApi(() => actions.searchUsers(debounced), allowSearch ? `user-search:${debounced}` : null, { keepPrevious: true })
  const direct = useAction((userId: string) => actions.startDirect(userId), { toastError: false })
  const group = useAction((groupTitle: string, memberIds: string[]) => actions.createGroup(groupTitle, memberIds), { toastError: false })
  const busy = direct.busy || group.busy
  const error = direct.error ?? group.error ?? search.error

  const results = useMemo<Candidate[]>(
    () => (search.data ?? []).filter((u) => u.id !== me && !u.deactivated).map((u) => ({ id: u.id, displayName: u.displayName || u.id, avatarUrl: u.avatarUrl })),
    [search.data, me],
  )
  const candidates: Candidate[] = [...suggestions.filter((s) => s.id !== me && !results.some((r) => r.id === s.id)), ...results]
  const searching = search.loading || search.refreshing

  const startDirect = async (userId: string) => {
    const id = userId.trim()
    if (!id) return
    if (await direct.run(id)) onClose()
  }

  const createGroup = async (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim() || members.length === 0) return
    if (
      await group.run(
        title.trim(),
        members.map((m) => m.id),
      )
    )
      onClose()
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
        <Input
          mono
          value={manualId}
          onChange={(e) => setManualId(e.target.value)}
          placeholder="User ID"
          aria-label="User ID"
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              addManual()
            }
          }}
        />
        <Button onClick={addManual} disabled={!manualId.trim() || busy}>
          {mode === 'direct' ? 'Start chat' : 'Add'}
        </Button>
      </div>

      <div className="user-pick-list">
        {searching && candidates.length === 0 ? (
          <div className="user-pick-list__empty">
            <Spinner size={14} label="Searching" />
          </div>
        ) : null}
        {!searching && candidates.length === 0 ? <p className="user-pick-list__empty muted">No users found.</p> : null}
        {candidates.length > 0 ? (
          <ul className="user-pick-list__items" aria-label={mode === 'direct' ? 'Start a chat with' : 'Members to add'}>
            {candidates.map((c) => {
              const selected = members.some((m) => m.id === c.id)
              return (
                <li key={c.id}>
                  <button
                    type="button"
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
                </li>
              )
            })}
          </ul>
        ) : null}
      </div>
      {error ? <Callout tone="danger">{error.message}</Callout> : null}
    </Dialog>
  )
}
