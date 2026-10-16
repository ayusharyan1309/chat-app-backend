import { jsonEqual } from '../../lib/util'
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
  /** Why the last attempt to open the conversation failed; the view offers a retry. */
  error: string | null
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
  /** Thread ids, most recently opened first. Bounds how many threads keep messages in memory. */
  threadLru: string[]
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
  threadLru: [],
  pending: {},
  typing: {},
  presence: {},
}

/** Threads beyond this many (least recently opened, never the active one) drop back to not-loaded. */
export const MAX_CACHED_THREADS = 6

export type ChatAction =
  /** A page of the inbox. `removed`: conversations a full refresh proved gone (see `staleConversationIds`). */
  | { type: 'inbox/loaded'; conversations: Conversation[]; hasMore: boolean; removed?: readonly string[] }
  | { type: 'conv/upsert'; conversation: Conversation }
  | { type: 'conv/remove'; id: string }
  | { type: 'active/set'; id: string | null }
  | { type: 'thread/loading'; id: string }
  | { type: 'thread/loaded'; id: string; messages: Message[]; hasMoreBefore: boolean }
  | { type: 'thread/failed'; id: string; error: string }
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

/* ------------------------------------------------------------- Messages ---- */

const TYPING_TTL_MS = 6_000

function sameReactions(a: Message['reactions'], b: Message['reactions']): boolean {
  if (a === b) return true
  const ak = Object.keys(a ?? {})
  const bk = Object.keys(b ?? {})
  if (ak.length !== bk.length) return false
  for (const key of ak) {
    const x = a?.[key] ?? []
    const y = b?.[key]
    if (!y || x.length !== y.length || x.some((user, i) => user !== y[i])) return false
  }
  return true
}

/** Value equality for two snapshots of the same message (server sends fresh objects every time). */
export function sameMessage(a: Message, b: Message): boolean {
  if (a === b) return true
  return (
    a.id === b.id &&
    a.seq === b.seq &&
    a.conversationId === b.conversationId &&
    a.senderId === b.senderId &&
    a.clientMsgId === b.clientMsgId &&
    a.type === b.type &&
    a.content === b.content &&
    a.replyToId === b.replyToId &&
    a.forwarded === b.forwarded &&
    a.edited === b.edited &&
    a.editedAt === b.editedAt &&
    a.deleted === b.deleted &&
    a.createdAt === b.createdAt &&
    sameReactions(a.reactions, b.reactions) &&
    jsonEqual(a.attachment, b.attachment) &&
    jsonEqual(a.metadata, b.metadata)
  )
}

function ascendingBySeq(messages: Message[]): boolean {
  for (let i = 1; i < messages.length; i += 1) {
    const prev = messages[i - 1]
    const next = messages[i]
    if (!prev || !next || next.seq <= prev.seq) return false
  }
  return true
}

/**
 * Merge `incoming` into a thread's messages (ascending by seq, unique by id).
 * - Fast path: an ascending batch that starts after the newest message is appended without
 *   rebuilding or sorting (live messages, catch-up pages).
 * - Messages equal to what is already held keep the existing object, and when nothing changes the
 *   same array is returned, so memoised bubbles and selectors do not re-render.
 * - Pure updates (known ids, same seq) are replaced in place; anything else is rebuilt and sorted.
 */
export function mergeMessages(existing: Message[], incoming: Message[]): Message[] {
  const firstIncoming = incoming[0]
  if (!firstIncoming) return existing
  const last = existing[existing.length - 1]
  if ((!last || firstIncoming.seq > last.seq) && ascendingBySeq(incoming)) {
    return existing.length === 0 ? incoming.slice() : existing.concat(incoming)
  }

  const byId = new Map<string, Message>()
  for (const m of existing) byId.set(m.id, m)
  const replaced = new Map<string, Message>()
  const added: Message[] = []
  for (const m of incoming) {
    const prev = byId.get(m.id)
    if (prev && sameMessage(prev, m)) continue
    if (prev && prev.seq === m.seq) replaced.set(m.id, m)
    else added.push(m)
    byId.set(m.id, m)
  }
  if (replaced.size === 0 && added.length === 0) return existing
  if (added.length === 0) return existing.map((m) => replaced.get(m.id) ?? m)
  return [...byId.values()].sort((a, b) => a.seq - b.seq)
}

export function lastSeqOf(messages: Message[]): number {
  return messages[messages.length - 1]?.seq ?? 0
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

/* ---------------------------------------------------------- Conversations ---- */

/** Last activity (ms) used for inbox order: newest message, else creation. */
export function activityOf(conv: Pick<Conversation, 'lastMessage' | 'createdAt'>): number {
  return Date.parse(conv.lastMessage?.at ?? conv.createdAt) || 0
}

/**
 * Conversations held locally that a full (first-page) inbox refresh proves gone: they are missing
 * from the page although they are newer than its oldest entry (or the page is the whole inbox), so
 * they would have been in it. Typical cause: removed from, or left, a conversation while the socket
 * was down. Older conversations may simply sit on a later page and are kept.
 */
export function staleConversationIds(known: Record<string, Conversation>, firstPage: Conversation[], pageSize: number): string[] {
  const inPage = new Set(firstPage.map((c) => c.id))
  const complete = firstPage.length < pageSize
  const oldest = firstPage.reduce((min, c) => Math.min(min, activityOf(c)), Number.POSITIVE_INFINITY)
  return Object.values(known)
    .filter((c) => !inPage.has(c.id) && (complete || activityOf(c) > oldest))
    .map((c) => c.id)
}

function applyMessageToConversation(conv: Conversation, message: Message, countUnread: boolean): Conversation {
  if (message.seq < conv.lastSeq) return conv
  const isNewer = message.seq > conv.lastSeq
  const preview = previewOf(message)
  const unreadCount = countUnread && isNewer ? conv.unreadCount + 1 : conv.unreadCount
  const last = conv.lastMessage
  if (!isNewer && last && last.id === message.id && last.preview === preview && last.senderId === message.senderId && last.at === message.createdAt) {
    return conv
  }
  return {
    ...conv,
    lastSeq: Math.max(conv.lastSeq, message.seq),
    lastMessage: { id: message.id, preview, senderId: message.senderId, at: message.createdAt },
    unreadCount,
  }
}

/** Merge a server snapshot without letting an older one move the watermark backwards. */
function mergeConversation(prev: Conversation | undefined, next: Conversation): Conversation {
  const merged: Conversation = { ...prev, ...next }
  if (prev && prev.lastSeq > merged.lastSeq) {
    merged.lastSeq = prev.lastSeq
    merged.lastMessage = prev.lastMessage
  }
  return merged
}

function withConversation(state: ChatState, conv: Conversation | undefined, next: Conversation | undefined): Record<string, Conversation> {
  if (!conv || !next || next === conv) return state.conversations
  return { ...state.conversations, [conv.id]: next }
}

function seedPresence(presence: Record<string, PresenceInfo>, conversations: Conversation[]): Record<string, PresenceInfo> {
  let next = presence
  for (const c of conversations) {
    const peer = c.peer
    if (peer && peer.online !== undefined && peer.online !== null && !next[peer.id]) {
      next = { ...next, [peer.id]: { online: peer.online, lastSeenAt: peer.lastSeenAt ?? null } }
    }
  }
  return next
}

/** Drop a conversation and everything hanging off it (thread, typing, optimistic sends). */
function removeConversation(state: ChatState, id: string): ChatState {
  const has = (record: Record<string, unknown>) => Object.hasOwn(record, id)
  const pendingIds = Object.values(state.pending).filter((p) => p.conversationId === id).map((p) => p.clientMsgId)
  if (!has(state.conversations) && !has(state.threads) && !has(state.typing) && pendingIds.length === 0 && state.activeId !== id) return state
  const conversations = { ...state.conversations }
  delete conversations[id]
  const threads = { ...state.threads }
  delete threads[id]
  const typing = { ...state.typing }
  delete typing[id]
  let pending = state.pending
  if (pendingIds.length) {
    pending = { ...pending }
    for (const clientMsgId of pendingIds) delete pending[clientMsgId]
  }
  return {
    ...state,
    conversations,
    threads,
    typing,
    pending,
    threadLru: state.threadLru.filter((x) => x !== id),
    activeId: state.activeId === id ? null : state.activeId,
  }
}

/* --------------------------------------------------------------- Threads ---- */

function emptyThread(): Thread {
  return { messages: [], loaded: false, error: null, loadingOlder: false, hasMoreBefore: false, participants: null }
}

function updateThread(state: ChatState, id: string, fn: (t: Thread) => Thread): Record<string, Thread> {
  const thread = state.threads[id]
  if (!thread) return state.threads
  const next = fn(thread)
  return next === thread ? state.threads : { ...state.threads, [id]: next }
}

/**
 * Put `id` first in the LRU and drop the least recently opened threads beyond the cap (never the
 * active one). Dropped threads disappear from `threads`, so opening them again reloads them and
 * a reconnect does not resync them.
 */
function touchThread(threads: Record<string, Thread>, lru: string[], id: string, activeId: string | null): Pick<ChatState, 'threads' | 'threadLru'> {
  let order = lru[0] === id ? lru : [id, ...lru.filter((x) => x !== id)]
  let kept = threads
  while (order.length > MAX_CACHED_THREADS) {
    const victim = [...order].reverse().find((x) => x !== id && x !== activeId)
    if (!victim) break
    order = order.filter((x) => x !== victim)
    if (kept[victim]) {
      kept = { ...kept }
      delete kept[victim]
    }
  }
  return { threads: kept, threadLru: order }
}

function setThread(state: ChatState, id: string, thread: Thread): ChatState {
  const threads = { ...state.threads, [id]: thread }
  return { ...state, ...touchThread(threads, state.threadLru, id, state.activeId) }
}

/* ---------------------------------------------------------------- Reducer ---- */

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'inbox/loaded': {
      let base = state
      for (const id of action.removed ?? []) base = removeConversation(base, id)
      const conversations = { ...base.conversations }
      for (const c of action.conversations) conversations[c.id] = mergeConversation(conversations[c.id], c)
      return { ...base, conversations, inboxLoaded: true, inboxHasMore: action.hasMore, presence: seedPresence(base.presence, action.conversations) }
    }
    case 'conv/upsert': {
      const merged = mergeConversation(state.conversations[action.conversation.id], action.conversation)
      const participants = action.conversation.participants
      const threads = participants ? updateThread(state, merged.id, (t) => ({ ...t, participants })) : state.threads
      return { ...state, conversations: { ...state.conversations, [merged.id]: merged }, threads, presence: seedPresence(state.presence, [merged]) }
    }
    case 'conv/remove':
      return removeConversation(state, action.id)
    case 'active/set': {
      if (action.id === null || !state.threads[action.id]) return state.activeId === action.id ? state : { ...state, activeId: action.id }
      return { ...state, activeId: action.id, ...touchThread(state.threads, state.threadLru, action.id, action.id) }
    }
    case 'thread/loading': {
      const prev = state.threads[action.id]
      if (prev && !prev.error) return state
      return setThread(state, action.id, { ...(prev ?? emptyThread()), loaded: false, error: null })
    }
    case 'thread/loaded': {
      const prev = state.threads[action.id] ?? emptyThread()
      return setThread(state, action.id, {
        ...prev,
        loaded: true,
        error: null,
        messages: mergeMessages(prev.messages, action.messages),
        hasMoreBefore: action.hasMoreBefore,
      })
    }
    case 'thread/failed': {
      const prev = state.threads[action.id] ?? emptyThread()
      return setThread(state, action.id, { ...prev, loaded: false, error: action.error, loadingOlder: false })
    }
    case 'thread/older':
      return {
        ...state,
        threads: updateThread(state, action.id, (t) => ({ ...t, messages: mergeMessages(t.messages, action.messages), hasMoreBefore: action.hasMoreBefore, loadingOlder: false })),
      }
    case 'thread/loadingOlder':
      return { ...state, threads: updateThread(state, action.id, (t) => (t.loadingOlder === action.value ? t : { ...t, loadingOlder: action.value })) }
    case 'thread/merge': {
      const threads = updateThread(state, action.id, (t) => {
        const messages = mergeMessages(t.messages, action.messages)
        return messages === t.messages ? t : { ...t, messages }
      })
      const conv = state.conversations[action.id]
      const newest = action.messages.reduce<Message | null>((acc, m) => (!acc || m.seq > acc.seq ? m : acc), null)
      const conversations = withConversation(state, conv, conv && newest ? applyMessageToConversation(conv, newest, false) : undefined)
      if (threads === state.threads && conversations === state.conversations) return state
      return { ...state, threads, conversations }
    }
    case 'thread/participants':
      return { ...state, threads: updateThread(state, action.id, (t) => ({ ...t, participants: action.participants })) }
    case 'receipt': {
      const threads = updateThread(state, action.id, (t) => {
        if (!t.participants) return t
        let changed = false
        const participants = t.participants.map((p) => {
          if (p.userId !== action.userId) return p
          const deliveredSeq = Math.max(p.deliveredSeq, action.deliveredSeq)
          const readSeq = Math.max(p.readSeq, action.readSeq)
          if (deliveredSeq === p.deliveredSeq && readSeq === p.readSeq) return p
          changed = true
          return { ...p, deliveredSeq, readSeq }
        })
        return changed ? { ...t, participants } : t
      })
      const conv = state.conversations[action.id]
      // Our own read receipt from another session clears the unread badge here too.
      const cleared = action.userId === action.me && conv && action.readSeq >= conv.lastSeq && conv.unreadCount > 0 ? { ...conv, unreadCount: 0 } : undefined
      const conversations = withConversation(state, conv, cleared)
      if (threads === state.threads && conversations === state.conversations) return state
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
      const threads = updateThread(state, m.conversationId, (t) => {
        const messages = mergeMessages(t.messages, [m])
        return messages === t.messages ? t : { ...t, messages }
      })
      const conv = state.conversations[m.conversationId]
      const conversations = withConversation(state, conv, conv ? applyMessageToConversation(conv, m, false) : undefined)
      if (pending === state.pending && threads === state.threads && conversations === state.conversations) return state
      return { ...state, pending, threads, conversations }
    }
    case 'message/incoming': {
      const m = action.message
      const thread = state.threads[m.conversationId]
      const known = thread?.messages.some((x) => x.id === m.id) ?? false
      const threads = updateThread(state, m.conversationId, (t) => {
        const messages = mergeMessages(t.messages, [m])
        return messages === t.messages ? t : { ...t, messages }
      })
      const conv = state.conversations[m.conversationId]
      const conversations = withConversation(state, conv, conv ? applyMessageToConversation(conv, m, action.countUnread && !known) : undefined)
      // A message from someone ends their typing indicator.
      let typing = state.typing
      const typers = typing[m.conversationId]
      if (typers?.[m.senderId]) {
        const forConv = { ...typers }
        delete forConv[m.senderId]
        typing = { ...typing, [m.conversationId]: forConv }
      }
      if (threads === state.threads && conversations === state.conversations && typing === state.typing) return state
      return { ...state, threads, conversations, typing }
    }
    case 'message/updated': {
      const m = action.message
      const threads = updateThread(state, m.conversationId, (t) => {
        if (!t.messages.some((x) => x.id === m.id)) return t
        const messages = mergeMessages(t.messages, [m])
        return messages === t.messages ? t : { ...t, messages }
      })
      const conv = state.conversations[m.conversationId]
      const last = conv?.lastMessage
      const preview = previewOf(m)
      const updatedConv = conv && last && last.id === m.id && last.preview !== preview ? { ...conv, lastMessage: { ...last, preview } } : undefined
      const conversations = withConversation(state, conv, updatedConv)
      if (threads === state.threads && conversations === state.conversations) return state
      return { ...state, threads, conversations }
    }
    case 'typing': {
      const current = state.typing[action.id] ?? {}
      if (!action.typing && current[action.userId] === undefined) return state
      const forConv = { ...current }
      if (action.typing) forConv[action.userId] = action.now + TYPING_TTL_MS
      else delete forConv[action.userId]
      return { ...state, typing: { ...state.typing, [action.id]: forConv } }
    }
    case 'typing/sweep': {
      let changed = false
      const typing: ChatState['typing'] = {}
      for (const [conv, users] of Object.entries(state.typing)) {
        const live = Object.entries(users).filter(([, until]) => until > action.now)
        const count = Object.keys(users).length
        if (live.length === count) {
          if (count) typing[conv] = users
          else changed = true
          continue
        }
        changed = true
        if (live.length) typing[conv] = Object.fromEntries(live)
      }
      return changed ? { ...state, typing } : state
    }
    case 'presence': {
      const prev = state.presence[action.userId]
      if (prev && prev.online === action.online && prev.lastSeenAt === action.lastSeenAt) return state
      return { ...state, presence: { ...state.presence, [action.userId]: { online: action.online, lastSeenAt: action.lastSeenAt } } }
    }
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

/** Inbox order: pinned first, then by last activity. Archived conversations are listed separately. */
export function sortedConversations(conversations: Record<string, Conversation>): Conversation[] {
  return Object.values(conversations)
    .filter((c) => !c.archived)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || activityOf(b) - activityOf(a))
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

/** Highest seq every other participant has received / read. `null` when nobody else is in the chat. */
export interface TickThresholds {
  delivered: number
  read: number
}

export function tickThresholds(participants: Participant[] | null, me: string): TickThresholds | null {
  const others = (participants ?? []).filter((p) => p.userId !== me)
  if (others.length === 0) return null
  return {
    delivered: Math.min(...others.map((p) => Math.max(p.deliveredSeq, p.readSeq))),
    read: Math.min(...others.map((p) => p.readSeq)),
  }
}

/** Tick for an own message given precomputed thresholds (O(1) per message). */
export function tickFromThresholds(seq: number, thresholds: TickThresholds | null): TickState {
  if (!thresholds) return 'sent'
  if (thresholds.read >= seq) return 'read'
  if (thresholds.delivered >= seq) return 'delivered'
  return 'sent'
}

/**
 * WhatsApp-style ticks for an own message: one tick once acknowledged (stored), two grey ticks when
 * every other participant's deliveredSeq has reached it, two blue ticks when every readSeq has.
 */
export function tickFor(seq: number, participants: Participant[] | null, me: string): TickState {
  return tickFromThresholds(seq, tickThresholds(participants, me))
}
