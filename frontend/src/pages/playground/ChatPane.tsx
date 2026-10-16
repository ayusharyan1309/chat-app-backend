import { RefreshCw, X } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '../../api/http'
import { Button, IconButton } from '../../components/ui/Button'
import { Callout, Lamp, Spinner, type LampState } from '../../components/ui/Feedback'
import { Avatar } from '../../components/ui/Misc'
import { cx } from '../../lib/util'
import type { TenantPublicConfig } from '../../types'
import type { SessionStatus } from './chatEngine'
import { sortedConversations } from './chatState'
import { ConversationView } from './ConversationView'
import { Inbox, type Suggestion } from './Inbox'
import { NewChatDialog } from './NewChatDialog'
import { useChatSession } from './useChatSession'

const STATUS: Record<SessionStatus, { lamp: LampState; label: string }> = {
  authenticating: { lamp: 'idle', label: 'Signing in' },
  connecting: { lamp: 'idle', label: 'Connecting' },
  open: { lamp: 'ok', label: 'Live' },
  reconnecting: { lamp: 'warn', label: 'Reconnecting' },
  error: { lamp: 'down', label: 'Error' },
  closed: { lamp: 'idle', label: 'Offline' },
}

interface PaneProps {
  tenant: string
  userId: string
  config: TenantPublicConfig
  suggestions: Suggestion[]
  onClose: () => void
  onIdentity: (s: Suggestion) => void
}

function PaneSession({ tenant, userId, config, suggestions, onClose, onIdentity, onRetry }: PaneProps & { onRetry: () => void }) {
  const features = useMemo(() => new Set(config.features), [config.features])
  const session = useChatSession(tenant, userId, config.features, config.settings)
  const { state, me, status, fatal, notices, actions } = session
  const [newChat, setNewChat] = useState(false)
  const meId = me?.id ?? userId
  const conversations = useMemo(() => sortedConversations(state), [state])
  const archived = useMemo(() => Object.values(state.conversations).filter((c) => c.archived), [state.conversations])
  const active = state.activeId ? state.conversations[state.activeId] : undefined
  const pending = useMemo(
    () => Object.values(state.pending).filter((p) => p.conversationId === state.activeId).sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
    [state.pending, state.activeId],
  )
  // Names known to this session: direct-chat peers, loaded participants and the other panes.
  const names = useMemo(() => {
    const map = new Map<string, string>()
    for (const s of suggestions) map.set(s.id, s.displayName)
    for (const c of Object.values(state.conversations)) {
      if (c.peer) map.set(c.peer.id, c.peer.displayName || c.peer.id)
      for (const p of c.participants ?? []) if (p.displayName) map.set(p.userId, p.displayName)
    }
    for (const t of Object.values(state.threads)) for (const p of t.participants ?? []) if (p.displayName) map.set(p.userId, p.displayName)
    return map
  }, [state.conversations, state.threads, suggestions])
  const typingUsers = useMemo(() => Object.keys((state.activeId && state.typing[state.activeId]) || {}), [state.typing, state.activeId])

  useEffect(() => {
    if (me) onIdentity({ id: me.id, displayName: me.displayName || me.id })
  }, [me, onIdentity])

  const meta = STATUS[status]
  const others = suggestions.filter((s) => s.id !== meId)

  return (
    <article className="pane" aria-label={`Chat as ${me?.displayName ?? userId}`}>
      <header className="pane__head">
        <Avatar id={meId} name={me?.displayName ?? userId} src={me?.avatarUrl} size={30} />
        <div className="pane__who">
          <span className="pane__name">{me?.displayName ?? userId}</span>
          <span className="pane__id mono">{meId}</span>
        </div>
        <Lamp state={meta.lamp} label={meta.label} />
        {status === 'reconnecting' ? <IconButton size="sm" label="Reconnect now" icon={<RefreshCw size={14} aria-hidden />} onClick={actions.reconnect} /> : null}
        <IconButton size="sm" label="Close pane" icon={<X size={15} aria-hidden />} onClick={onClose} />
      </header>

      {notices.length > 0 ? (
        <div className="pane__notices" aria-live="polite">
          {notices.map((n) => (
            <div key={n.id} className="pane__notice">
              <span>{n.message}</span>
              <button type="button" onClick={() => actions.dismissNotice(n.id)} aria-label="Dismiss">
                <X size={12} aria-hidden />
              </button>
            </div>
          ))}
        </div>
      ) : null}

      {fatal ? (
        <div className="pane__fatal">
          <Callout tone="danger" title="Could not sign in as this user" actions={<Button size="sm" onClick={onRetry}>Retry</Button>}>
            {fatal}
          </Callout>
        </div>
      ) : status === 'authenticating' && !me ? (
        <div className="pane__fatal">
          <Spinner size={16} label="Requesting a user token" />
        </div>
      ) : (
        <div className={cx('pane__body', active && 'has-active')}>
          <Inbox
            conversations={conversations}
            archived={archived}
            onUnarchive={(id) => actions.updateSettings(id, { archived: false })}
            loaded={state.inboxLoaded}
            hasMore={state.inboxHasMore}
            me={meId}
            activeId={state.activeId}
            typing={state.typing}
            presence={state.presence}
            showPresence={features.has('presence')}
            suggestions={others}
            onOpen={(id) => actions.open(id)}
            onNew={() => setNewChat(true)}
            onQuickChat={(id) => void actions.startDirect(id).catch((err: unknown) => actions.notify(errorMessage(err)))}
            onLoadMore={actions.loadMoreInbox}
            nameOf={(id) => names.get(id) ?? id}
          />
          {active ? (
            <ConversationView
              key={active.id}
              conversation={active}
              thread={state.threads[active.id]}
              pending={pending}
              me={meId}
              typingUserIds={typingUsers}
              presence={state.presence}
              features={features}
              settings={config.settings}
              connected={status === 'open'}
              actions={actions}
              onBack={() => actions.open(null)}
              onNotice={actions.notify}
            />
          ) : (
            <div className="pane__placeholder">
              <p>Select a chat or start a new one.</p>
              <Button size="sm" onClick={() => setNewChat(true)}>
                New chat
              </Button>
            </div>
          )}
        </div>
      )}

      {newChat ? (
        <NewChatDialog
          me={meId}
          actions={actions}
          allowGroups={features.has('group-chat')}
          allowSearch={config.settings?.allowUserSearch ?? true}
          suggestions={others}
          onClose={() => setNewChat(false)}
        />
      ) : null}
    </article>
  )
}

/** One impersonated user. Retrying after a sign-in failure remounts the session. */
export function ChatPane(props: PaneProps) {
  const [attempt, setAttempt] = useState(0)
  return <PaneSession key={attempt} {...props} onRetry={() => setAttempt((a) => a + 1)} />
}
