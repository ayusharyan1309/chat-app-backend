import { BellOff, MessageSquarePlus, Pin, Users } from 'lucide-react'
import { Spinner } from '../../components/ui/Feedback'
import { Avatar } from '../../components/ui/Misc'
import { formatInboxTime } from '../../lib/format'
import { cx } from '../../lib/util'
import type { Conversation } from '../../types'
import { conversationTitle, type PresenceInfo } from './chatState'

export interface Suggestion {
  id: string
  displayName: string
}

export interface InboxProps {
  conversations: Conversation[]
  archived: Conversation[]
  onUnarchive: (id: string) => void
  loaded: boolean
  hasMore: boolean
  me: string
  activeId: string | null
  typing: Record<string, Record<string, number>>
  presence: Record<string, PresenceInfo>
  showPresence: boolean
  suggestions: Suggestion[]
  onOpen: (id: string) => void
  onNew: () => void
  onQuickChat: (userId: string) => void
  onLoadMore: () => void
  /** Display name for a user id (falls back to the id). */
  nameOf: (userId: string) => string
}

export function Inbox({ conversations, archived, onUnarchive, loaded, hasMore, me, activeId, typing, presence, showPresence, suggestions, onOpen, onNew, onQuickChat, onLoadMore, nameOf }: InboxProps) {
  const quick = suggestions.filter((s) => s.id !== me && !conversations.some((c) => c.type === 'DIRECT' && c.peer?.id === s.id))
  return (
    <section className="inbox" aria-label="Chats">
      <header className="inbox__head">
        <h3 className="inbox__title">Chats</h3>
        <button type="button" className="inbox__new" onClick={onNew} aria-label="New chat" title="New chat">
          <MessageSquarePlus size={17} aria-hidden />
        </button>
      </header>
      <div className="inbox__list" role="list">
        {!loaded ? (
          <div className="inbox__loading">
            <Spinner size={14} label="Loading chats" />
          </div>
        ) : null}
        {loaded && conversations.length === 0 ? (
          <div className="inbox__empty">
            <p className="inbox__empty-title">No chats yet</p>
            <p className="inbox__empty-desc">Start one with another user of this tenant.</p>
          </div>
        ) : null}
        {conversations.map((c) => {
          const title = conversationTitle(c, me)
          const typers = Object.keys(typing[c.id] ?? {})
          const last = c.lastMessage
          const fromMe = last?.senderId === me
          const senderLabel = c.type === 'GROUP' && last && !fromMe ? `${nameOf(last.senderId)}: ` : ''
          const peerPresence = c.peer ? presence[c.peer.id] : undefined
          return (
            <button
              key={c.id}
              type="button"
              role="listitem"
              className={cx('inbox-row', c.id === activeId && 'is-active', c.unreadCount > 0 && 'is-unread')}
              onClick={() => onOpen(c.id)}
              aria-current={c.id === activeId ? 'true' : undefined}
            >
              {c.type === 'GROUP' ? (
                <span className="inbox-row__group" aria-hidden>
                  <Users size={15} />
                </span>
              ) : (
                <Avatar id={c.peer?.id ?? c.id} name={title} src={c.peer?.avatarUrl} size={38} online={showPresence ? (peerPresence?.online ?? null) : null} />
              )}
              <span className="inbox-row__body">
                <span className="inbox-row__top">
                  <span className="inbox-row__title">{title}</span>
                  <span className="inbox-row__time">{formatInboxTime(last?.at ?? c.createdAt)}</span>
                </span>
                <span className="inbox-row__bottom">
                  <span className={cx('inbox-row__preview', typers.length > 0 && 'is-typing')}>
                    {typers.length > 0 ? 'typing…' : last ? `${fromMe ? 'You: ' : senderLabel}${last.preview ?? ''}` : 'No messages yet'}
                  </span>
                  <span className="inbox-row__flags">
                    {c.muted ? <BellOff size={13} aria-label="Muted" /> : null}
                    {c.pinned ? <Pin size={13} aria-label="Pinned" /> : null}
                    {c.unreadCount > 0 ? (
                      <span className={cx('unread', c.muted && 'is-muted')} aria-label={`${c.unreadCount} unread`}>
                        {c.unreadCount > 99 ? '99+' : c.unreadCount}
                      </span>
                    ) : null}
                  </span>
                </span>
              </span>
            </button>
          )
        })}
        {archived.length > 0 ? (
          <details className="inbox__archived">
            <summary>Archived chats ({archived.length})</summary>
            {archived.map((c) => (
              <div key={c.id} className="inbox__archived-row">
                <span className="truncate">{conversationTitle(c, me)}</span>
                <button type="button" className="inbox__unarchive" onClick={() => onUnarchive(c.id)}>
                  Unarchive
                </button>
              </div>
            ))}
          </details>
        ) : null}
        {hasMore ? (
          <button type="button" className="inbox__more" onClick={onLoadMore}>
            Load older chats
          </button>
        ) : null}
        {loaded && quick.length > 0 ? (
          <div className="inbox__quick">
            <p className="inbox__quick-label">Other users in this playground</p>
            {quick.map((s) => (
              <button key={s.id} type="button" className="inbox__quick-btn" onClick={() => onQuickChat(s.id)}>
                <Avatar id={s.id} name={s.displayName} size={22} />
                Message {s.displayName}
              </button>
            ))}
          </div>
        ) : null}
      </div>
    </section>
  )
}
