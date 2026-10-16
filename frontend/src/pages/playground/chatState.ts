import type { Attachment, Conversation, Message, MessageType, Participant } from '../../types'

/* ------------------------------------------------------------------ Types ---- */

export type PendingStatus = 'uploading' | 'sending' | 'failed'

/** Optimistic message shown before the server acknowledges it. */
export interface PendingMessage {
  clientMsgId: string
  conversationId: string
  type: MessageType
  content: string | null
  attachment: Attachment | null
  localPreviewUrl: string | null
  fileName: string | null
  fileSize: number | null
  replyToId: string | null
  createdAt: string
  status: PendingStatus
  /** Upload progress 0..1 while uploading. */
  progress: number | null
  error: string | null
  /** When the frame was last transmitted (ms), for ack timeouts. */
  sentAt: number | null
}

export interface Thread {
  /** Ascending by seq, unique by id. */
  messages: Message[]
  loaded: boolean
  loadingOlder: boolean
  hasMoreBefore: boolean
  participants: Participant[] | null
}

export interface PresenceInfo {
  online: boolean
  lastSeenAt: string | null
}

export interface ChatState {
  conversations: Record<string, Conversation>
  inboxLoaded: boolean
  inboxHasMore: boolean
  activeId: string | null
  threads: Record<string, Thread>
  pending: Record<string, PendingMessage>
  /** conversationId -> userId -> typing expiry (ms epoch). */
  typing: Record<string, Record<string, number>>
  presence: Record<string, PresenceInfo>
}

export const initialChatState: ChatState = {
  conversations: {},
  inboxLoaded: false,
  inboxHasMore: false,
  activeId: null,
  threads: {},
  pending: {},
  typing: {},
  presence: {},
}

export type ChatAction =
  | { type: 'inbox/loaded'; conversations: Conversation[]; hasMore: boolean }
  | { type: 'conv/upsert'; conversation: Conversation }
  | { type: 'conv/remove'; id: string }
  | { type: 'active/set'; id: string | null }
  | { type: 'thread/loaded'; id: string; messages: Message[]; hasMoreBefore: boolean }
  | { type: 'thread/older'; id: string; messages: Message[]; hasMoreBefore: boolean }
  | { type: 'thread/loadingOlder'; id: string; value: boolean }
  | { type: 'thread/merge'; id: string; messages: Message[] }
  | { type: 'thread/participants'; id: string; participants: Participant[] }
  | { type: 'receipt'; id: string; userId: string; deliveredSeq: number; readSeq: number; me: string }
  | { type: 'pending/add'; pending: PendingMessage }
  | { type: 'pending/update'; clientMsgId: string; patch: Partial<PendingMessage> }
  | { type: 'pending/remove'; clientMsgId: string }
  /** Own message durably stored (message.ack, or message.new from our own session). */
  | { type: 'message/confirmed'; message: Message }
  /** Somebody else's message. `countUnread` is false when the conversation is open and visible. */
  | { type: 'message/incoming'; message: Message; countUnread: boolean }
  | { type: 'message/updated'; message: Message }
  | { type: 'typing'; id: string; userId: string; typing: boolean; now: number }
  | { type: 'typing/sweep'; now: number }
  | { type: 'presence'; userId: string; online: boolean; lastSeenAt: string | null }
  | { type: 'read/local'; id: string }

/* ---------------------------------------------------------------- Helpers ---- */

const TYPING_TTL_MS = 6_000

export function mergeMessages(existing: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return existing
  const byId = new Map<string, Message>()
  for (const m of existing) byId.set(m.id, m)
  for (const m of incoming) byId.set(m.id, m)
  return [...byId.values()].sort((a, b) => a.seq - b.seq)
}

export function lastSeqOf(messages: Message[]): number {
  return messages.length ? (messages[messages.length - 1]?.seq ?? 0) : 0
}

export function previewOf(message: Pick<Message, 'type' | 'content' | 'deleted' | 'attachment'>): string {
  if (message.deleted) return 'Message deleted'
  const caption = message.content?.trim()
  switch (message.type) {
    case 'IMAGE':
      return caption ? `Photo: ${caption}` : 'Photo'
    case 'VIDEO':
      return caption ? `Video: ${caption}` : 'Video'
    case 'AUDIO':
      return 'Audio'
    case 'FILE':
      return `File: ${message.attachment?.fileName ?? caption ?? 'attachment'}`
    case 'LOCATION':
      return 'Location'
    case 'CONTACT':
      return 'Contact'
    case 'STICKER':
      return 'Sticker'
    default:
      return caption ?? ''
  }
}

function applyMessageToConversation(conv: Conversation, message: Message, countUnread: boolean): Conversation {
  if (message.seq < conv.lastSeq) return conv
  const isNewer = message.seq > conv.lastSeq
  return {
    ...conv,
    lastSeq: Math.max(conv.lastSeq, message.seq),
    lastMessage: { id: message.id, preview: previewOf(message), senderId: message.senderId, at: message.createdAt },
    unreadCount: countUnread && isNewer ? conv.unreadCount + 1 : conv.unreadCount,
  }
}

function updateThread(state: ChatState, id: string, fn: (t: Thread) => Thread): Record<string, Thread> {
  const thread = state.threads[id]
  if (!thread) return state.threads
  return { ...state.threads, [id]: fn(thread) }
}

function emptyThread(): Thread {
  return { messages: [], loaded: false, loadingOlder: false, hasMoreBefore: false, participants: null }
}

function seedPresence(state: ChatState, conversations: Conversation[]): Record<string, PresenceInfo> {
  let presence = state.presence
  for (const c of conversations) {
    const peer = c.peer
    if (peer && peer.online !== undefined && peer.online !== null && !presence[peer.id]) {
      presence = { ...presence, [peer.id]: { online: peer.online, lastSeenAt: peer.lastSeenAt ?? null } }
    }
  }
  return presence
}

/* ---------------------------------------------------------------- Reducer ---- */

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'inbox/loaded': {
      const conversations = { ...state.conversations }
      for (const c of action.conversations) conversations[c.id] = { ...conversations[c.id], ...c }
      return { ...state, conversations, inboxLoaded: true, inboxHasMore: action.hasMore, presence: seedPresence(state, action.conversations) }
    }
    case 'conv/upsert': {
      const prev = state.conversations[action.conversation.id]
      const merged: Conversation = { ...prev, ...action.conversation }
      // Keep the freshest watermark if an older snapshot arrives after a newer message.
      if (prev && prev.lastSeq > merged.lastSeq) {
        merged.lastSeq = prev.lastSeq
        merged.lastMessage = prev.lastMessage
      }
      let threads = state.threads
      if (action.conversation.participants && threads[action.conversation.id]) {
        threads = updateThread(state, action.conversation.id, (t) => ({ ...t, participants: action.conversation.participants ?? t.participants }))
      }
      return { ...state, conversations: { ...state.conversations, [merged.id]: merged }, threads, presence: seedPresence(state, [merged]) }
    }
    case 'conv/remove': {
      const conversations = { ...state.conversations }
      delete conversations[action.id]
      const threads = { ...state.threads }
      delete threads[action.id]
      return { ...state, conversations, threads, activeId: state.activeId === action.id ? null : state.activeId }
    }
    case 'active/set':
      return { ...state, activeId: action.id }
    case 'thread/loaded': {
      const prev = state.threads[action.id] ?? emptyThread()
      return {
        ...state,
        threads: {
          ...state.threads,
          [action.id]: { ...prev, loaded: true, messages: mergeMessages(prev.messages, action.messages), hasMoreBefore: action.hasMoreBefore },
        },
      }
    }
    case 'thread/older':
      return {
        ...state,
        threads: updateThread(state, action.id, (t) => ({ ...t, messages: mergeMessages(t.messages, action.messages), hasMoreBefore: action.hasMoreBefore, loadingOlder: false })),
      }
    case 'thread/loadingOlder':
      return { ...state, threads: updateThread(state, action.id, (t) => ({ ...t, loadingOlder: action.value })) }
    case 'thread/merge': {
      const threads = updateThread(state, action.id, (t) => ({ ...t, messages: mergeMessages(t.messages, action.messages) }))
      const conv = state.conversations[action.id]
      const newest = action.messages.reduce<Message | null>((acc, m) => (!acc || m.seq > acc.seq ? m : acc), null)
      const conversations = conv && newest ? { ...state.conversations, [conv.id]: applyMessageToConversation(conv, newest, false) } : state.conversations
      return { ...state, threads, conversations }
    }
    case 'thread/participants': {
      const prev = state.threads[action.id] ?? emptyThread()
      return { ...state, threads: { ...state.threads, [action.id]: { ...prev, participants: action.participants } } }
    }
    case 'receipt': {
      let threads = updateThread(state, action.id, (t) =>
        t.participants
          ? {
              ...t,
              participants: t.participants.map((p) =>
                p.userId === action.userId
                  ? { ...p, deliveredSeq: Math.max(p.deliveredSeq, action.deliveredSeq), readSeq: Math.max(p.readSeq, action.readSeq) }
                  : p,
              ),
            }
          : t,
      )
      let conversations = state.conversations
      const conv = state.conversations[action.id]
      // Our own read receipt from another session clears the unread badge here too.
      if (action.userId === action.me && conv && action.readSeq >= conv.lastSeq && conv.unreadCount > 0) {
        conversations = { ...conversations, [conv.id]: { ...conv, unreadCount: 0 } }
      }
      if (threads === state.threads) threads = state.threads
      return { ...state, threads, conversations }
    }
    case 'pending/add':
      return { ...state, pending: { ...state.pending, [action.pending.clientMsgId]: action.pending } }
    case 'pending/update': {
      const prev = state.pending[action.clientMsgId]
      if (!prev) return state
      return { ...state, pending: { ...state.pending, [action.clientMsgId]: { ...prev, ...action.patch } } }
    }
    case 'pending/remove': {
      if (!state.pending[action.clientMsgId]) return state
      const pending = { ...state.pending }
      delete pending[action.clientMsgId]
      return { ...state, pending }
    }
    case 'message/confirmed': {
      const m = action.message
      let pending = state.pending
      if (m.clientMsgId && pending[m.clientMsgId]) {
        pending = { ...pending }
        delete pending[m.clientMsgId]
      }
      const threads = updateThread(state, m.conversationId, (t) => ({ ...t, messages: mergeMessages(t.messages, [m]) }))
      const conv = state.conversations[m.conversationId]
      const conversations = conv ? { ...state.conversations, [conv.id]: applyMessageToConversation(conv, m, false) } : state.conversations
      return { ...state, pending, threads, conversations }
    }
    case 'message/incoming': {
      const m = action.message
      const thread = state.threads[m.conversationId]
      const known = thread?.messages.some((x) => x.id === m.id) ?? false
      const threads = thread ? updateThread(state, m.conversationId, (t) => ({ ...t, messages: mergeMessages(t.messages, [m]) })) : state.threads
      const conv = state.conversations[m.conversationId]
      const conversations = conv ? { ...state.conversations, [conv.id]: applyMessageToConversation(conv, m, action.countUnread && !known) } : state.conversations
      // A message from someone ends their typing indicator.
      let typing = state.typing
      if (typing[m.conversationId]?.[m.senderId]) {
        const forConv = { ...typing[m.conversationId] }
        delete forConv[m.senderId]
        typing = { ...typing, [m.conversationId]: forConv }
      }
      return { ...state, threads, conversations, typing }
    }
    case 'message/updated': {
      const m = action.message
      const threads = updateThread(state, m.conversationId, (t) => (t.messages.some((x) => x.id === m.id) ? { ...t, messages: mergeMessages(t.messages, [m]) } : t))
      const conv = state.conversations[m.conversationId]
      const conversations =
        conv && conv.lastMessage?.id === m.id
          ? { ...state.conversations, [conv.id]: { ...conv, lastMessage: { ...conv.lastMessage, preview: previewOf(m) } } }
          : state.conversations
      return { ...state, threads, conversations }
    }
    case 'typing': {
      const forConv = { ...(state.typing[action.id] ?? {}) }
      if (action.typing) forConv[action.userId] = action.now + TYPING_TTL_MS
      else delete forConv[action.userId]
      return { ...state, typing: { ...state.typing, [action.id]: forConv } }
    }
    case 'typing/sweep': {
      let changed = false
      const typing: ChatState['typing'] = {}
      for (const [conv, users] of Object.entries(state.typing)) {
        const kept: Record<string, number> = {}
        for (const [user, until] of Object.entries(users)) {
          if (until > action.now) kept[user] = until
          else changed = true
        }
        if (Object.keys(kept).length) typing[conv] = kept
        else if (Object.keys(users).length) changed = true
      }
      return changed ? { ...state, typing } : state
    }
    case 'presence':
      return { ...state, presence: { ...state.presence, [action.userId]: { online: action.online, lastSeenAt: action.lastSeenAt } } }
    case 'read/local': {
      const conv = state.conversations[action.id]
      if (!conv || conv.unreadCount === 0) return state
      return { ...state, conversations: { ...state.conversations, [conv.id]: { ...conv, unreadCount: 0 } } }
    }
    default:
      return state
  }
}

/* -------------------------------------------------------------- Selectors ---- */

export function sortedConversations(state: ChatState): Conversation[] {
  const activity = (c: Conversation) => Date.parse(c.lastMessage?.at ?? c.createdAt) || 0
  return Object.values(state.conversations)
    .filter((c) => !c.archived)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || activity(b) - activity(a))
}

export function conversationTitle(conv: Conversation, me: string | null): string {
  if (conv.type === 'DIRECT') {
    const peer = conv.peer
    if (peer) return peer.displayName || peer.id
    const other = conv.participants?.find((p) => p.userId !== me)
    return other?.displayName || other?.userId || 'Direct chat'
  }
  return conv.title || 'Untitled group'
}

export type TickState = 'pending' | 'failed' | 'sent' | 'delivered' | 'read'

/**
 * WhatsApp-style ticks for an own message: one tick once acknowledged (stored), two grey ticks when
 * every other participant's deliveredSeq has reached it, two blue ticks when every readSeq has.
 */
export function tickFor(seq: number, participants: Participant[] | null, me: string): TickState {
  const others = (participants ?? []).filter((p) => p.userId !== me)
  if (others.length === 0) return 'sent'
  if (others.every((p) => p.readSeq >= seq)) return 'read'
  if (others.every((p) => Math.max(p.deliveredSeq, p.readSeq) >= seq)) return 'delivered'
  return 'sent'
}
