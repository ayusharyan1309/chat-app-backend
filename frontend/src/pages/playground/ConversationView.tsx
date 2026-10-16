import { Archive, ArrowDown, BellOff, BellRing, ChevronLeft, EllipsisVertical, Pin, PinOff, Users } from 'lucide-react'
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Spinner } from '../../components/ui/Feedback'
import { Avatar, Menu } from '../../components/ui/Misc'
import { useNow } from '../../hooks/misc'
import { formatDaySeparator, formatRelative, isSameDay } from '../../lib/format'
import { useConfirm } from '../../state/confirm'
import type { Conversation, Message, Participant, TenantSettings } from '../../types'
import { conversationTitle, tickFor, type PendingMessage, type PresenceInfo, type Thread } from './chatState'
import { Composer } from './Composer'
import { MessageBubble, PendingBubble } from './MessageBubble'
import type { ChatActions } from './useChatSession'

const RUN_GAP_MS = 5 * 60_000

type Item =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'message'; key: string; message: Message; firstInRun: boolean }
  | { kind: 'pending'; key: string; pending: PendingMessage }

function buildItems(messages: Message[], pending: PendingMessage[]): Item[] {
  const items: Item[] = []
  let prevDate: Date | null = null
  let prevSender: string | null = null
  let prevAt = 0
  const push = (at: string, sender: string, make: (firstInRun: boolean) => Item) => {
    const date = new Date(at)
    if (!prevDate || !isSameDay(prevDate, date)) {
      items.push({ kind: 'day', key: `day-${date.toDateString()}`, label: formatDaySeparator(at) })
      prevSender = null
    }
    const first = sender !== prevSender || date.getTime() - prevAt > RUN_GAP_MS
    items.push(make(first))
    prevDate = date
    prevSender = sender
    prevAt = date.getTime()
  }
  for (const m of messages) {
    push(m.createdAt, m.type === 'SYSTEM' ? '__system' : m.senderId, (first) => ({ kind: 'message', key: m.id, message: m, firstInRun: first }))
  }
  for (const p of pending) push(p.createdAt, '__me', () => ({ kind: 'pending', key: p.clientMsgId, pending: p }))
  return items
}

export interface ConversationViewProps {
  conversation: Conversation
  thread: Thread | undefined
  pending: PendingMessage[]
  me: string
  typingUserIds: string[]
  presence: Record<string, PresenceInfo>
  features: ReadonlySet<string>
  settings: TenantSettings | null
  connected: boolean
  actions: ChatActions
  onBack?: () => void
  onNotice: (message: string) => void
}

export function ConversationView({ conversation, thread, pending, me, typingUserIds, presence, features, settings, connected, actions, onBack, onNotice }: ConversationViewProps) {
  const confirm = useConfirm()
  const now = useNow(30_000)
  const scrollRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const atBottomRef = useRef(true)
  const anchorRef = useRef<{ id: string; offset: number } | null>(null)
  const lastKeyRef = useRef<string | null>(null)
  const firstKeyRef = useRef<string | null>(null)
  const [unseen, setUnseen] = useState(0)
  const [replyTo, setReplyTo] = useState<Message | null>(null)
  const [editing, setEditing] = useState<Message | null>(null)
  const [highlight, setHighlight] = useState<string | null>(null)

  const messages = useMemo(() => thread?.messages ?? [], [thread?.messages])
  const participants: Participant[] = useMemo(() => thread?.participants ?? conversation.participants ?? [], [thread?.participants, conversation.participants])
  const byId = useMemo(() => new Map(messages.map((m) => [m.id, m])), [messages])
  const names = useMemo(() => {
    const map = new Map<string, string>()
    for (const p of participants) map.set(p.userId, p.displayName || p.userId)
    if (conversation.peer) map.set(conversation.peer.id, conversation.peer.displayName || conversation.peer.id)
    map.set(me, 'You')
    return map
  }, [participants, conversation.peer, me])
  const nameOf = useCallback((id: string) => names.get(id) ?? id, [names])
  const items = useMemo(() => buildItems(messages, pending), [messages, pending])

  const isGroup = conversation.type === 'GROUP'
  const isAdmin = conversation.role === 'ADMIN' || conversation.role === 'OWNER'
  const editWindowMs = (settings?.editWindowMinutes ?? 0) * 60_000
  const title = conversationTitle(conversation, me)

  /* ------------------------------------------------------------ Scrolling ---- */

  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    el.scrollTop = el.scrollHeight
    atBottomRef.current = true
    setUnseen(0)
  }, [])

  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!el) return
    const first = items.find((i) => i.kind !== 'day')?.key ?? null
    const last = items.length ? (items[items.length - 1]?.key ?? null) : null
    const prevFirst = firstKeyRef.current
    const prevLast = lastKeyRef.current
    firstKeyRef.current = first
    lastKeyRef.current = last

    // Older history was prepended: keep the previously first visible message in place.
    if (anchorRef.current && first !== prevFirst) {
      const anchor = document.getElementById(`msg-${anchorRef.current.id}`)
      if (anchor) el.scrollTop = anchor.offsetTop - anchorRef.current.offset
      anchorRef.current = null
      return
    }
    if (last !== prevLast) {
      const lastItem = items[items.length - 1]
      const mine = lastItem?.kind === 'pending' || (lastItem?.kind === 'message' && lastItem.message.senderId === me)
      if (prevLast === null || atBottomRef.current || mine) {
        el.scrollTop = el.scrollHeight
        atBottomRef.current = true
      } else if (lastItem?.kind === 'message') {
        // oxlint-disable-next-line react/set-state-in-effect
        setUnseen((n) => n + 1)
      }
    }
  }, [items, me])

  // Stay pinned to the bottom while content grows in place (typing bubble, images loading,
  // reactions, a failed-send row) as long as the reader was already at the bottom.
  useEffect(() => {
    const content = contentRef.current
    const el = scrollRef.current
    if (!content || !el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      if (atBottomRef.current && !anchorRef.current) el.scrollTop = el.scrollHeight
    })
    observer.observe(content)
    return () => observer.disconnect()
  }, [])

  const requestOlder = useCallback(() => {
    const el = scrollRef.current
    if (!el || !thread?.hasMoreBefore || thread.loadingOlder) return
    const firstMsg = messages[0]
    if (firstMsg) {
      const node = document.getElementById(`msg-${firstMsg.id}`)
      if (node) anchorRef.current = { id: firstMsg.id, offset: node.offsetTop - el.scrollTop }
    }
    actions.loadOlder(conversation.id)
  }, [actions, conversation.id, messages, thread])

  // If the first page does not fill the viewport, keep loading history.
  useEffect(() => {
    const el = scrollRef.current
    if (el && thread?.loaded && thread.hasMoreBefore && !thread.loadingOlder && el.scrollHeight <= el.clientHeight + 40) requestOlder()
  }, [thread?.loaded, thread?.hasMoreBefore, thread?.loadingOlder, messages.length, requestOlder])

  const onScroll = () => {
    const el = scrollRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    atBottomRef.current = distance < 80
    if (atBottomRef.current && unseen) setUnseen(0)
    if (el.scrollTop < 120) requestOlder()
  }

  const jumpTo = (id: string) => {
    const node = document.getElementById(`msg-${id}`)
    if (!node) return
    node.scrollIntoView({ block: 'center', behavior: 'smooth' })
    setHighlight(id)
    window.setTimeout(() => setHighlight((h) => (h === id ? null : h)), 1600)
  }

  /* -------------------------------------------------------------- Header ---- */

  let subtitle: ReactNode = null
  if (typingUserIds.length > 0) {
    subtitle = <span className="conv__typing-text">{isGroup ? `${typingUserIds.map(nameOf).join(', ')} typing…` : 'typing…'}</span>
  } else if (!isGroup && conversation.peer) {
    const info = presence[conversation.peer.id]
    if (features.has('presence') && info) subtitle = info.online ? 'online' : info.lastSeenAt ? `last seen ${formatRelative(info.lastSeenAt, now)}` : 'offline'
    else subtitle = <span className="mono">{conversation.peer.id}</span>
  } else if (isGroup) {
    subtitle = participants.length ? `${participants.length} members: ${participants.map((p) => (p.userId === me ? 'You' : p.displayName || p.userId)).join(', ')}` : 'Group'
  }

  const peerPresence = conversation.peer ? presence[conversation.peer.id] : undefined

  const deleteMessage = async (m: Message) => {
    const ok = await confirm({
      title: 'Delete for everyone?',
      body: <p>The message is replaced with “This message was deleted” for every participant.</p>,
      confirmLabel: 'Delete for everyone',
      tone: 'danger',
    })
    if (ok) actions.remove(m)
  }

  const typingNames = typingUserIds.map(nameOf)
  const replySender = replyTo ? nameOf(replyTo.senderId) : ''

  return (
    <section className="conv" aria-label={`Conversation with ${title}`}>
      <header className="conv__head">
        {onBack ? (
          <button type="button" className="conv__back" onClick={onBack} aria-label="Back to chats">
            <ChevronLeft size={18} aria-hidden />
          </button>
        ) : null}
        {isGroup ? (
          <span className="conv__group-avatar" aria-hidden>
            <Users size={16} />
          </span>
        ) : (
          <Avatar id={conversation.peer?.id ?? conversation.id} name={title} src={conversation.peer?.avatarUrl} size={34} online={features.has('presence') ? (peerPresence?.online ?? null) : null} />
        )}
        <div className="conv__title-block">
          <span className="conv__title">{title}</span>
          <span className="conv__subtitle">{subtitle}</span>
        </div>
        <Menu
          label="Conversation options"
          trigger={(props) => (
            <button type="button" className="conv__menu" aria-label="Conversation options" {...props}>
              <EllipsisVertical size={17} aria-hidden />
            </button>
          )}
          items={[
            {
              key: 'pin',
              label: conversation.pinned ? 'Unpin chat' : 'Pin chat',
              icon: conversation.pinned ? <PinOff size={15} aria-hidden /> : <Pin size={15} aria-hidden />,
              onSelect: () => actions.updateSettings(conversation.id, { pinned: !conversation.pinned }),
            },
            {
              key: 'mute',
              label: conversation.muted ? 'Unmute notifications' : 'Mute notifications',
              icon: conversation.muted ? <BellRing size={15} aria-hidden /> : <BellOff size={15} aria-hidden />,
              onSelect: () => actions.updateSettings(conversation.id, { muted: !conversation.muted }),
            },
            {
              key: 'archive',
              label: conversation.archived ? 'Unarchive chat' : 'Archive chat',
              icon: <Archive size={15} aria-hidden />,
              onSelect: () => {
                actions.updateSettings(conversation.id, { archived: !conversation.archived })
                if (!conversation.archived) actions.open(null)
              },
            },
          ]}
        />
      </header>

      <div className="conv__scroll" ref={scrollRef} onScroll={onScroll} role="log" aria-live="polite" aria-relevant="additions" aria-label="Messages">
        <div className="conv__content" ref={contentRef}>
        <div className="conv__history-edge">
          {thread?.loadingOlder ? (
            <span className="conv__loading-older">
              <Spinner size={13} /> Loading older messages
            </span>
          ) : thread?.loaded && !thread.hasMoreBefore ? (
            <span className="conv__start">{messages.length ? 'Start of the conversation' : 'No messages yet. Say hello.'}</span>
          ) : null}
        </div>
        {!thread?.loaded ? (
          <div className="conv__initial">
            <Spinner size={16} label="Loading messages" />
          </div>
        ) : null}
        {items.map((item) => {
          if (item.kind === 'day') {
            return (
              <div className="day-sep" key={item.key} role="separator">
                <span>{item.label}</span>
              </div>
            )
          }
          if (item.kind === 'pending') {
            const p = item.pending
            const quoted = p.replyToId ? (byId.get(p.replyToId) ?? null) : null
            return (
              <PendingBubble
                key={item.key}
                pending={p}
                quoted={quoted}
                quotedSenderName={quoted ? nameOf(quoted.senderId) : null}
                onRetry={() => actions.retry(p.clientMsgId)}
                onDiscard={() => actions.discard(p.clientMsgId)}
              />
            )
          }
          const m = item.message
          const own = m.senderId === me
          const quoted = m.replyToId ? (byId.get(m.replyToId) ?? null) : null
          const withinWindow = editWindowMs > 0 && now - Date.parse(m.createdAt) < editWindowMs
          return (
            <Fragment key={item.key}>
              <MessageBubble
                message={m}
                own={own}
                me={me}
                senderName={nameOf(m.senderId)}
                firstInRun={item.firstInRun}
                showSender={isGroup && !own && item.firstInRun}
                tick={own ? tickFor(m.seq, participants, me) : null}
                quoted={quoted}
                quotedSenderName={quoted ? nameOf(quoted.senderId) : null}
                highlighted={highlight === m.id}
                onJumpTo={jumpTo}
                capabilities={{
                  reply: true,
                  react: features.has('message-reactions'),
                  edit: own && m.type === 'TEXT' && features.has('message-editing') && withinWindow,
                  delete: features.has('message-deletion') && (own || (isGroup && isAdmin)),
                }}
                onReply={() => {
                  setEditing(null)
                  setReplyTo(m)
                }}
                onReact={(emoji) => actions.react(m, emoji)}
                onEdit={() => {
                  setReplyTo(null)
                  setEditing(m)
                }}
                onDelete={() => void deleteMessage(m)}
              />
            </Fragment>
          )
        })}
        {typingUserIds.length > 0 ? (
          <div className="msg msg--in is-first typing-bubble" aria-label={`${typingNames.join(', ')} typing`}>
            <div className="msg__bubble">
              {isGroup ? <span className="msg__sender">{typingNames.join(', ')}</span> : null}
              <span className="typing-dots" aria-hidden>
                <span />
                <span />
                <span />
              </span>
            </div>
          </div>
        ) : null}
        </div>
      </div>

      {unseen > 0 ? (
        <button type="button" className="conv__jump" onClick={scrollToBottom}>
          <ArrowDown size={14} aria-hidden />
          {unseen} new {unseen === 1 ? 'message' : 'messages'}
        </button>
      ) : null}

      <Composer
        key={conversation.id}
        disabled={!thread?.loaded}
        maxLength={settings?.maxMessageLength ?? 4096}
        allowMedia={features.has('media-sharing')}
        allowFiles={features.has('file-sharing')}
        replyTo={replyTo ? { message: replyTo, senderName: replySender } : null}
        editing={editing}
        onCancelReply={() => setReplyTo(null)}
        onCancelEdit={() => setEditing(null)}
        onSend={(text) => {
          actions.send(conversation.id, text, replyTo?.id ?? null)
          setReplyTo(null)
        }}
        onSendFile={(file, caption) => {
          actions.sendFile(conversation.id, file, caption, replyTo?.id ?? null)
          setReplyTo(null)
        }}
        onEdit={(m, content) => actions.edit(m, content)}
        onTyping={() => actions.notifyTyping(conversation.id)}
        onStopTyping={() => actions.stopTyping(conversation.id)}
        onReject={onNotice}
      />
      {!connected ? <p className="conv__offline">Realtime connection lost. Messages are sent over HTTP until it reconnects.</p> : null}
    </section>
  )
}
