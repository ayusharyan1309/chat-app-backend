import { createClientApi, type ClientApi } from '../../api/client'
import { createUserHttp, errorMessage, toApiError } from '../../api/http'
import { RealtimeClient, type ConnectionState, type RealtimeOptions, type RealtimeTransport } from '../../api/realtime'
import { tenantUsersApi } from '../../api/tenants'
import { uuid } from '../../lib/util'
import type {
  Attachment,
  Conversation,
  ConversationSettingsPatch,
  FeatureId,
  FeatureSet,
  Message,
  MessageType,
  RealtimeEvent,
  ReceiptKind,
  SendMessageFrame,
  TenantSettings,
  User,
  UserToken,
} from '../../types'
import { lastSeqOf, staleConversationIds, type ChatAction, type ChatState, type PendingMessage } from './chatState'

export type SessionStatus = 'authenticating' | 'connecting' | 'open' | 'reconnecting' | 'error' | 'closed'

export interface EngineHost {
  tenant: string
  userId: string
  dispatch: (action: ChatAction) => void
  getState: () => ChatState
  getFeatures: () => FeatureSet
  getSettings: () => TenantSettings | null
  /** Whether the user can currently see the page (read receipts are only sent then). */
  isVisible: () => boolean
  onMe: (user: User) => void
  onStatus: (status: SessionStatus) => void
  onFatal: (message: string) => void
  onNotice: (message: string) => void
}

/** Outside-world dependencies, replaceable in tests. */
export interface EngineDeps {
  /** Mint an (audited) impersonation token for the pane's user. */
  mintToken: (tenant: string, userId: string) => Promise<UserToken>
  createApi: (getToken: () => string | null, onUnauthorized: () => void) => ClientApi
  createRealtime: (options: RealtimeOptions) => RealtimeTransport
}

export const defaultEngineDeps: EngineDeps = {
  mintToken: (tenant, userId) => tenantUsersApi.token(tenant, userId),
  createApi: (getToken, onUnauthorized) => createClientApi(createUserHttp(getToken, onUnauthorized)),
  createRealtime: (options) => new RealtimeClient(options),
}

type Timer = ReturnType<typeof setTimeout>

export const PAGE = 50
const SYNC_PAGE = 100
export const ACK_TIMEOUT_MS = 15_000
const TYPING_RESEND_MS = 3_000
const TYPING_IDLE_MS = 4_000
const PREVIEW_GRACE_MS = 15_000
const PROGRESS_INTERVAL_MS = 150

export function messageTypeForFile(file: File): MessageType {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('video/')) return 'VIDEO'
  if (file.type.startsWith('audio/')) return 'AUDIO'
  return 'FILE'
}

/**
 * Rate-limit upload progress updates: lets a value through at most every `intervalMs`, and always
 * lets the final 100% through. Upload events fire far more often than a progress bar needs.
 */
export function createProgressGate(intervalMs = PROGRESS_INTERVAL_MS, clock: () => number = Date.now): (fraction: number) => boolean {
  let lastAt = Number.NEGATIVE_INFINITY
  let lastValue = -1
  return (fraction) => {
    const now = clock()
    if (fraction === lastValue) return false
    if (fraction < 1 && now - lastAt < intervalMs) return false
    lastAt = now
    lastValue = fraction
    return true
  }
}

/**
 * One end-user session in the playground: impersonation token, client REST API and STOMP socket.
 * Holds no React state itself; it dispatches actions into the pane's reducer.
 */
export class ChatEngine {
  private readonly host: EngineHost
  private readonly deps: EngineDeps
  private readonly api: ClientApi
  private rt: RealtimeTransport | null = null
  private token: { value: string; expiresAt: number } | null = null
  private tokenPromise: Promise<string> | null = null
  private me: User | null = null
  private stopped = false
  private activeId: string | null = null
  /** Highest seq per conversation the server has (as far as we know) accepted, per receipt kind. */
  private readonly sentReceipts: Record<ReceiptKind, Record<string, number>> = { delivered: {}, read: {} }
  /** Receipts currently travelling over REST, so the same one is not sent twice concurrently. */
  private readonly receiptsInFlight: Record<ReceiptKind, Record<string, number>> = { delivered: {}, read: {} }
  private readonly files = new Map<string, File>()
  private readonly previews = new Set<string>()
  private readonly typingSentAt = new Map<string, number>()
  private readonly typingStopTimers = new Map<string, Timer>()
  private readonly syncing = new Set<string>()
  /** Pending sends whose REST fallback request has not settled; the ack timeout ignores them. */
  private readonly restSends = new Set<string>()
  /** In-flight `GET /conversations/{id}` per id (deduplicated). */
  private readonly fetching = new Map<string, Promise<Conversation>>()
  /** In-flight thread loads per id (deduplicated). */
  private readonly opening = new Map<string, Promise<void>>()
  /** Local time a conversation was last updated by something newer than an inbox snapshot. */
  private readonly touchedAt = new Map<string, number>()
  private inboxRefresh: Promise<void> | null = null
  private presenceKey = ''
  private presenceTimer: Timer | undefined
  private intervals: Array<ReturnType<typeof setInterval>> = []
  private readonly timeouts = new Set<Timer>()

  constructor(host: EngineHost, deps: EngineDeps = defaultEngineDeps) {
    this.host = host
    this.deps = deps
    this.api = deps.createApi(
      () => this.token?.value ?? null,
      () => {
        this.token = null
      },
    )
  }

  /* ------------------------------------------------------------ Lifecycle ---- */

  async start(): Promise<void> {
    this.host.onStatus('authenticating')
    try {
      await this.refreshToken()
    } catch (err) {
      if (!this.stopped) {
        this.host.onFatal(errorMessage(err))
        this.host.onStatus('error')
      }
      return
    }
    if (this.stopped) return
    this.rt = this.deps.createRealtime({
      getToken: () => this.ensureToken(),
      onEvent: (event) => this.onEvent(event),
      onStateChange: (state, resumed) => this.onConnection(state, resumed),
      onProtocolError: (message) => {
        // Usually an auth failure on CONNECT: force a fresh token for the next attempt.
        this.token = null
        this.host.onNotice(message)
      },
      onTokenError: (err) => {
        if (!this.stopped) this.host.onNotice(`Realtime sign-in failed: ${errorMessage(err)} Retrying automatically.`)
      },
    })
    this.rt.connect()
    this.intervals.push(setInterval(() => this.host.dispatch({ type: 'typing/sweep', now: Date.now() }), 1_000))
    this.intervals.push(setInterval(() => this.checkAckTimeouts(), 2_000))
    await this.loadInbox()
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    // Peers would otherwise see "typing…" until their indicator times out.
    if (this.rt?.connected) {
      for (const conversationId of this.typingSentAt.keys()) this.rt.sendTyping({ conversationId, typing: false })
    }
    this.typingSentAt.clear()
    this.typingStopTimers.forEach((t) => clearTimeout(t))
    this.typingStopTimers.clear()
    this.intervals.forEach((t) => clearInterval(t))
    this.intervals = []
    this.timeouts.forEach((t) => clearTimeout(t))
    this.timeouts.clear()
    clearTimeout(this.presenceTimer)
    this.previews.forEach((url) => URL.revokeObjectURL(url))
    this.previews.clear()
    this.files.clear()
    void this.rt?.disconnect()
    this.rt = null
  }

  /** setTimeout that is cleared on stop() and never scheduled after it. */
  private later(fn: () => void, ms: number): void {
    if (this.stopped) return
    const timer = setTimeout(() => {
      this.timeouts.delete(timer)
      fn()
    }, ms)
    this.timeouts.add(timer)
  }

  /* ----------------------------------------------------------------- Auth ---- */

  private async refreshToken(): Promise<string> {
    if (!this.tokenPromise) {
      this.tokenPromise = this.deps
        .mintToken(this.host.tenant, this.host.userId)
        .then((res) => {
          this.token = { value: res.token, expiresAt: Date.parse(res.expiresAt) || Date.now() + 3_600_000 }
          if (!this.me || this.me.id !== res.user.id) {
            this.me = res.user
            this.host.onMe(res.user)
          }
          return res.token
        })
        .finally(() => {
          this.tokenPromise = null
        })
    }
    return this.tokenPromise
  }

  private async ensureToken(): Promise<string> {
    if (this.token && this.token.expiresAt - Date.now() > 60_000) return this.token.value
    return this.refreshToken()
  }

  /** Run a client API call with a valid token, retrying once after a 401. */
  private async call<T>(fn: (api: ClientApi) => Promise<T>): Promise<T> {
    await this.ensureToken()
    try {
      return await fn(this.api)
    } catch (err) {
      if (toApiError(err).status === 401) {
        await this.refreshToken()
        return fn(this.api)
      }
      throw err
    }
  }

  get userId(): string {
    return this.me?.id ?? this.host.userId
  }

  private hasFeature(id: FeatureId): boolean {
    return this.host.getFeatures().has(id)
  }

  private touch(conversationId: string): void {
    this.touchedAt.set(conversationId, Date.now())
  }

  /* ------------------------------------------------------------ Connection ---- */

  private onConnection(state: ConnectionState, resumed: boolean): void {
    if (this.stopped) return
    if (state === 'open') {
      this.host.onStatus('open')
      this.presenceKey = ''
      this.schedulePresenceWatch()
      this.flushUnacked()
      if (resumed) void this.resync()
    } else if (state === 'reconnecting') {
      this.host.onStatus('reconnecting')
    } else if (state === 'connecting') {
      this.host.onStatus('connecting')
    } else if (state === 'closed') {
      this.host.onStatus('closed')
    }
  }

  /**
   * After a reconnect: refresh the inbox (dropping conversations we lost while offline) and pull
   * every thread still held in memory forward from its last seq. Evicted threads reload on open.
   */
  async resync(): Promise<void> {
    await this.loadInbox()
    if (this.stopped) return
    const state = this.host.getState()
    await Promise.all(Object.entries(state.threads).filter(([, t]) => t.loaded).map(([id]) => this.syncThread(id)))
    if (this.activeId && this.host.getState().threads[this.activeId]?.loaded) {
      this.refreshParticipants(this.activeId)
      this.markRead(this.activeId)
    }
  }

  /** Fetch everything after the newest loaded message (gap-free catch-up). */
  async syncThread(id: string): Promise<void> {
    if (this.syncing.has(id) || !this.host.getState().threads[id]) return
    this.syncing.add(id)
    try {
      let after = lastSeqOf(this.host.getState().threads[id]?.messages ?? [])
      for (let page = 0; page < 20; page += 1) {
        const batch = await this.call((api) => api.listMessages(id, { after, limit: SYNC_PAGE }))
        if (this.stopped || batch.length === 0) break
        this.host.dispatch({ type: 'thread/merge', id, messages: batch })
        this.ackDelivered(id, batch)
        after = Math.max(after, lastSeqOf(batch))
        if (batch.length < SYNC_PAGE) break
      }
      if (this.activeId === id) this.markRead(id, after)
    } catch (err) {
      if (await this.accessLost(id, err, false)) this.dropConversation(id, 'A conversation is no longer available to this user.')
      else this.notice(`Could not sync a conversation: ${errorMessage(err)}`)
    } finally {
      this.syncing.delete(id)
    }
  }

  /**
   * Whether a failed request means this user can no longer see the conversation. 404 always does
   * (the backend answers 404 to non-members). 403 is also what a suspended tenant or deactivated
   * user gets for every call, so it only counts when the conversation itself is gone too.
   */
  private async accessLost(id: string, err: unknown, failedOnDetail: boolean): Promise<boolean> {
    const status = toApiError(err).status
    if (status === 404) return true
    if (status !== 403 || failedOnDetail || this.stopped) return false
    try {
      await this.getConversation(id)
      return false
    } catch (probe) {
      return toApiError(probe).status === 404
    }
  }

  /** Forget a conversation locally (removed, left, or no longer visible). */
  private dropConversation(id: string, notice?: string): void {
    if (this.stopped) return
    const state = this.host.getState()
    if (!state.conversations[id] && !state.threads[id]) return
    if (this.activeId === id) this.activeId = null
    this.releasePendingFiles(id)
    this.host.dispatch({ type: 'conv/remove', id })
    this.schedulePresenceWatch()
    if (notice) this.notice(notice)
  }

  /** Free the files and preview URLs of optimistic sends to a conversation that is going away. */
  private releasePendingFiles(conversationId: string): void {
    for (const p of Object.values(this.host.getState().pending)) {
      if (p.conversationId !== conversationId) continue
      if (p.localPreviewUrl) {
        URL.revokeObjectURL(p.localPreviewUrl)
        this.previews.delete(p.localPreviewUrl)
      }
      this.files.delete(p.clientMsgId)
    }
  }

  private notice(message: string): void {
    if (!this.stopped) this.host.onNotice(message)
  }

  /* ---------------------------------------------------------------- Events ---- */

  private onEvent(event: RealtimeEvent): void {
    if (this.stopped) return
    const me = this.userId
    switch (event.type) {
      case 'message.ack':
        this.confirm(event.data.message)
        break
      case 'message.new': {
        const m = event.data
        if (m.senderId === me) {
          // Our own message, echoed to every session: reconcile by clientMsgId, dedupe by id.
          this.confirm(m)
          break
        }
        const state = this.host.getState()
        const thread = state.threads[m.conversationId]
        const visible = this.activeId === m.conversationId && this.host.isVisible()
        if (thread?.loaded && m.seq > lastSeqOf(thread.messages) + 1) void this.syncThread(m.conversationId)
        this.touch(m.conversationId)
        this.host.dispatch({ type: 'message/incoming', message: m, countUnread: !visible })
        if (!state.conversations[m.conversationId]) this.fetchConversation(m.conversationId)
        this.sendReceipt(m.conversationId, m.seq, 'delivered')
        if (visible) this.markRead(m.conversationId, m.seq)
        break
      }
      case 'message.updated':
        this.host.dispatch({ type: 'message/updated', message: event.data })
        break
      case 'receipt':
        if (event.conversationId) {
          this.host.dispatch({ type: 'receipt', id: event.conversationId, userId: event.data.userId, deliveredSeq: event.data.deliveredSeq, readSeq: event.data.readSeq, me })
        }
        break
      case 'typing':
        if (event.conversationId && event.data.userId !== me && this.hasFeature('typing-indicators')) {
          this.host.dispatch({ type: 'typing', id: event.conversationId, userId: event.data.userId, typing: event.data.typing, now: Date.now() })
        }
        break
      case 'presence':
        this.host.dispatch({ type: 'presence', userId: event.data.userId, online: event.data.online, lastSeenAt: event.data.lastSeenAt })
        break
      case 'conversation.updated': {
        const conv = event.data
        this.touch(conv.id)
        this.host.dispatch({ type: 'conv/upsert', conversation: conv })
        if (conv.id === this.activeId && !conv.participants) this.refreshParticipants(conv.id)
        this.schedulePresenceWatch()
        break
      }
      case 'conversation.removed': {
        const id = event.data.conversationId ?? event.conversationId
        if (id) this.dropConversation(id)
        break
      }
      case 'error': {
        const { clientMsgId, message, code } = event.data
        if (clientMsgId && this.host.getState().pending[clientMsgId]) {
          this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { status: 'failed', error: message || code } })
        } else {
          this.notice(message || code)
        }
        break
      }
      default:
        break
    }
  }

  private confirm(message: Message): void {
    if (this.stopped) return
    const state = this.host.getState()
    const pending = message.clientMsgId ? state.pending[message.clientMsgId] : undefined
    this.touch(message.conversationId)
    this.host.dispatch({ type: 'message/confirmed', message })
    // Sent from another session of this user into a conversation this pane has not seen yet.
    if (!state.conversations[message.conversationId]) this.fetchConversation(message.conversationId)
    if (message.clientMsgId) {
      this.files.delete(message.clientMsgId)
      const preview = pending?.localPreviewUrl
      if (preview) {
        // Let the server image load before dropping the local preview (stop() revokes it anyway).
        this.later(() => {
          URL.revokeObjectURL(preview)
          this.previews.delete(preview)
        }, PREVIEW_GRACE_MS)
      }
    }
  }

  /* ----------------------------------------------------------------- Inbox ---- */

  /**
   * `more = false`: refresh the newest page and reconcile (conversations that should be on it but
   * are not were removed while we were not listening). `more = true`: append the next older page.
   */
  loadInbox(more = false): Promise<void> {
    if (!more && this.inboxRefresh) return this.inboxRefresh
    const run = async () => {
      const startedAt = Date.now()
      try {
        let before: string | undefined
        if (more) {
          const oldest = Object.values(this.host.getState().conversations).reduce<string | null>((min, c) => {
            const at = c.lastMessage?.at ?? c.createdAt
            return !min || at < min ? at : min
          }, null)
          before = oldest ?? undefined
        }
        const list = await this.call((api) => api.listConversations({ limit: PAGE, before }))
        if (this.stopped) return
        // Anything updated locally after the request went out is newer than this snapshot: keep it.
        const removed = more
          ? []
          : staleConversationIds(this.host.getState().conversations, list, PAGE).filter((id) => (this.touchedAt.get(id) ?? 0) < startedAt)
        if (this.activeId && removed.includes(this.activeId)) this.activeId = null
        for (const id of removed) this.releasePendingFiles(id)
        this.host.dispatch({ type: 'inbox/loaded', conversations: list, hasMore: list.length >= PAGE, removed })
        // Fetching the inbox means those messages reached this device.
        for (const c of list) if (c.unreadCount > 0 && c.lastSeq > 0) this.sendReceipt(c.id, c.lastSeq, 'delivered')
        this.schedulePresenceWatch()
      } catch (err) {
        this.notice(`Could not load conversations: ${errorMessage(err)}`)
      }
    }
    if (more) return run()
    const refresh = run().finally(() => {
      if (this.inboxRefresh === refresh) this.inboxRefresh = null
    })
    this.inboxRefresh = refresh
    return refresh
  }

  /** Deduplicated `GET /conversations/{id}`; the result is merged into state. Rejects on failure. */
  private getConversation(id: string): Promise<Conversation> {
    const existing = this.fetching.get(id)
    if (existing) return existing
    const request = this.call((api) => api.getConversation(id)).then((conv) => {
      if (!this.stopped) {
        this.touch(conv.id)
        this.host.dispatch({ type: 'conv/upsert', conversation: conv })
        this.schedulePresenceWatch()
      }
      return conv
    })
    this.fetching.set(id, request)
    const settle = () => {
      if (this.fetching.get(id) === request) this.fetching.delete(id)
    }
    request.then(settle, settle)
    return request
  }

  /** Best-effort fetch for a conversation we only heard about through an event. */
  private fetchConversation(id: string): void {
    this.getConversation(id).catch((err: unknown) => {
      if (toApiError(err).status === 404) this.dropConversation(id)
    })
  }

  private refreshParticipants(id: string): void {
    this.fetchConversation(id)
  }

  /* ---------------------------------------------------------- Conversation ---- */

  async open(id: string | null): Promise<void> {
    this.activeId = id
    this.host.dispatch({ type: 'active/set', id })
    this.schedulePresenceWatch()
    if (!id) return
    const thread = this.host.getState().threads[id]
    if (thread?.loaded) {
      this.markRead(id)
      this.refreshParticipants(id)
      return
    }
    await this.loadThread(id)
  }

  /** First page of a thread. A failure is kept as the thread's error (the view offers a retry). */
  private loadThread(id: string): Promise<void> {
    const inflight = this.opening.get(id)
    if (inflight) return inflight
    const run = (async () => {
      this.host.dispatch({ type: 'thread/loading', id })
      let onDetail = true
      try {
        const detail = await this.getConversation(id)
        if (detail.participants) this.host.dispatch({ type: 'thread/participants', id, participants: detail.participants })
        onDetail = false
        // `before = lastSeq + 1` returns the newest page regardless of how the backend orders an
        // un-cursored request.
        const messages = detail.lastSeq > 0 ? await this.call((api) => api.listMessages(id, { before: detail.lastSeq + 1, limit: PAGE })) : []
        if (this.stopped) return
        const firstSeq = messages[0]?.seq ?? 1
        this.host.dispatch({ type: 'thread/loaded', id, messages, hasMoreBefore: messages.length >= PAGE && firstSeq > 1 })
        this.ackDelivered(id, messages)
        // Anything that arrived while we were loading.
        await this.syncThread(id)
        if (this.activeId === id) this.markRead(id)
        this.schedulePresenceWatch()
      } catch (err) {
        if (this.stopped) return
        if (await this.accessLost(id, err, onDetail)) {
          this.dropConversation(id, 'That conversation is no longer available to this user.')
          return
        }
        this.host.dispatch({ type: 'thread/failed', id, error: errorMessage(err) })
      }
    })()
    this.opening.set(id, run)
    void run.finally(() => {
      if (this.opening.get(id) === run) this.opening.delete(id)
    })
    return run
  }

  async loadOlder(id: string): Promise<void> {
    const thread = this.host.getState().threads[id]
    if (!thread || thread.loadingOlder || !thread.hasMoreBefore) return
    const first = thread.messages[0]
    if (!first) return
    this.host.dispatch({ type: 'thread/loadingOlder', id, value: true })
    try {
      const messages = await this.call((api) => api.listMessages(id, { before: first.seq, limit: PAGE }))
      const firstSeq = messages[0]?.seq ?? 1
      this.host.dispatch({ type: 'thread/older', id, messages, hasMoreBefore: messages.length >= PAGE && firstSeq > 1 })
    } catch (err) {
      this.host.dispatch({ type: 'thread/loadingOlder', id, value: false })
      if (await this.accessLost(id, err, false)) this.dropConversation(id, 'That conversation is no longer available to this user.')
      else this.notice(`Could not load older messages: ${errorMessage(err)}`)
    }
  }

  /** Send a read receipt up to `seq` (default: newest known) when the conversation is visible. */
  markRead(id: string, seq?: number): void {
    if (!this.host.isVisible() || this.activeId !== id) return
    const state = this.host.getState()
    const target = seq ?? Math.max(state.conversations[id]?.lastSeq ?? 0, lastSeqOf(state.threads[id]?.messages ?? []))
    if (target <= 0) return
    this.sendReceipt(id, target, 'delivered')
    this.sendReceipt(id, target, 'read')
    this.host.dispatch({ type: 'read/local', id })
  }

  /** Called when the browser tab becomes visible again. */
  onVisible(): void {
    if (this.activeId) this.markRead(this.activeId)
  }

  private ackDelivered(id: string, messages: Message[]): void {
    const me = this.userId
    const newest = messages.reduce((max, m) => (m.senderId !== me && m.seq > max ? m.seq : max), 0)
    if (newest > 0) this.sendReceipt(id, newest, 'delivered')
  }

  /** A receipt counts as sent only once it was published on the socket or the REST call succeeded. */
  private sendReceipt(conversationId: string, seq: number, kind: ReceiptKind): void {
    const sent = this.sentReceipts[kind]
    const inflight = this.receiptsInFlight[kind]
    if ((sent[conversationId] ?? 0) >= seq || (inflight[conversationId] ?? 0) >= seq) return
    if (this.rt?.sendReceipt({ conversationId, seq, kind })) {
      sent[conversationId] = seq
      return
    }
    inflight[conversationId] = seq
    const request = kind === 'read' ? this.call((api) => api.markRead(conversationId, seq)) : this.call((api) => api.markDelivered(conversationId, seq))
    request
      .then(
        () => {
          sent[conversationId] = Math.max(sent[conversationId] ?? 0, seq)
        },
        () => {
          // Not recorded as sent: the next trigger (message, open, reconnect) retries it.
        },
      )
      .finally(() => {
        if (inflight[conversationId] === seq) delete inflight[conversationId]
      })
  }

  /* ----------------------------------------------------------------- Send ---- */

  send(conversationId: string, content: string, replyToId: string | null): void {
    const pending: PendingMessage = {
      clientMsgId: uuid(),
      conversationId,
      type: 'TEXT',
      content,
      attachment: null,
      localPreviewUrl: null,
      fileName: null,
      fileSize: null,
      replyToId,
      createdAt: new Date().toISOString(),
      status: 'sending',
      progress: null,
      error: null,
      sentAt: Date.now(),
    }
    this.host.dispatch({ type: 'pending/add', pending })
    this.stopTyping(conversationId)
    this.transmit(pending)
  }

  async sendFile(conversationId: string, file: File, caption: string, replyToId: string | null): Promise<void> {
    const settings = this.host.getSettings()
    if (settings && file.size > settings.maxFileSizeMb * 1024 * 1024) {
      this.notice(`${file.name} is larger than this tenant's ${settings.maxFileSizeMb} MB limit.`)
      return
    }
    const type = messageTypeForFile(file)
    const localPreviewUrl = type === 'IMAGE' || type === 'VIDEO' ? URL.createObjectURL(file) : null
    if (localPreviewUrl) this.previews.add(localPreviewUrl)
    const pending: PendingMessage = {
      clientMsgId: uuid(),
      conversationId,
      type,
      content: caption.trim() || null,
      attachment: null,
      localPreviewUrl,
      fileName: file.name,
      fileSize: file.size,
      replyToId,
      createdAt: new Date().toISOString(),
      status: 'uploading',
      progress: 0,
      error: null,
      sentAt: null,
    }
    this.files.set(pending.clientMsgId, file)
    this.host.dispatch({ type: 'pending/add', pending })
    this.stopTyping(conversationId)
    await this.uploadAndSend(pending, file)
  }

  private async uploadAndSend(pending: PendingMessage, file: File): Promise<void> {
    const { clientMsgId } = pending
    this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { status: 'uploading', progress: 0, error: null } })
    const gate = createProgressGate()
    try {
      const media = await this.call((api) =>
        api.uploadMedia(file, (fraction) => {
          if (gate(fraction)) this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { progress: fraction } })
        }),
      )
      if (this.stopped) return
      const attachment: Attachment = {
        mediaId: media.mediaId,
        url: media.url,
        mimeType: media.mimeType ?? (file.type || null),
        size: media.size ?? file.size,
        fileName: media.fileName ?? file.name,
      }
      const next: PendingMessage = { ...pending, attachment, status: 'sending', progress: null, sentAt: Date.now() }
      this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { attachment, status: 'sending', progress: null, sentAt: next.sentAt } })
      this.transmit(next)
    } catch (err) {
      this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { status: 'failed', progress: null, error: errorMessage(err) } })
    }
  }

  /** STOMP first; falls back to REST while the socket is down. Both are idempotent by clientMsgId. */
  private transmit(pending: PendingMessage): void {
    const frame: SendMessageFrame = {
      conversationId: pending.conversationId,
      clientMsgId: pending.clientMsgId,
      type: pending.type,
      content: pending.content,
      attachment: pending.attachment,
      replyToId: pending.replyToId,
    }
    if (this.rt?.sendMessage(frame)) return
    const { clientMsgId } = pending
    if (this.restSends.has(clientMsgId)) return
    this.restSends.add(clientMsgId)
    const { conversationId, ...body } = frame
    this.call((api) => api.sendMessage(conversationId, body))
      .then((message) => this.confirm(message))
      .catch((err: unknown) => this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { status: 'failed', error: errorMessage(err) } }))
      .finally(() => this.restSends.delete(clientMsgId))
  }

  retry(clientMsgId: string): void {
    const pending = this.host.getState().pending[clientMsgId]
    if (!pending || this.restSends.has(clientMsgId)) return
    const file = this.files.get(clientMsgId)
    if (!pending.attachment && file) {
      void this.uploadAndSend(pending, file)
      return
    }
    const sentAt = Date.now()
    this.host.dispatch({ type: 'pending/update', clientMsgId, patch: { status: 'sending', error: null, sentAt } })
    this.transmit({ ...pending, sentAt })
  }

  discard(clientMsgId: string): void {
    const pending = this.host.getState().pending[clientMsgId]
    if (pending?.localPreviewUrl) {
      URL.revokeObjectURL(pending.localPreviewUrl)
      this.previews.delete(pending.localPreviewUrl)
    }
    this.files.delete(clientMsgId)
    this.host.dispatch({ type: 'pending/remove', clientMsgId })
  }

  /** Re-send frames that were never acknowledged (e.g. sent just before a disconnect). */
  private flushUnacked(): void {
    for (const pending of Object.values(this.host.getState().pending)) {
      if (pending.status === 'sending' && !this.restSends.has(pending.clientMsgId)) {
        const sentAt = Date.now()
        this.host.dispatch({ type: 'pending/update', clientMsgId: pending.clientMsgId, patch: { sentAt } })
        this.transmit({ ...pending, sentAt })
      }
    }
  }

  /** A socket send with no ack in time fails; a REST send still in flight is left to settle. */
  private checkAckTimeouts(): void {
    const now = Date.now()
    for (const pending of Object.values(this.host.getState().pending)) {
      if (pending.status !== 'sending' || !pending.sentAt || this.restSends.has(pending.clientMsgId)) continue
      if (now - pending.sentAt > ACK_TIMEOUT_MS) {
        this.host.dispatch({
          type: 'pending/update',
          clientMsgId: pending.clientMsgId,
          patch: { status: 'failed', error: 'No acknowledgement from the server. Retry to send it again.' },
        })
      }
    }
  }

  /* -------------------------------------------------------- Message actions ---- */

  async edit(message: Message, content: string): Promise<void> {
    try {
      const updated = await this.call((api) => api.editMessage(message.conversationId, message.id, content))
      this.host.dispatch({ type: 'message/updated', message: updated })
    } catch (err) {
      this.notice(`Edit failed: ${errorMessage(err)}`)
    }
  }

  async remove(message: Message): Promise<void> {
    try {
      const tombstone = await this.call((api) => api.deleteMessage(message.conversationId, message.id))
      this.host.dispatch({ type: 'message/updated', message: tombstone })
    } catch (err) {
      this.notice(`Delete failed: ${errorMessage(err)}`)
    }
  }

  async react(message: Message, emoji: string): Promise<void> {
    const mine = Object.entries(message.reactions ?? {}).find(([, users]) => users.includes(this.userId))?.[0]
    try {
      const updated =
        mine === emoji
          ? await this.call((api) => api.unreact(message.conversationId, message.id))
          : await this.call((api) => api.react(message.conversationId, message.id, emoji))
      this.host.dispatch({ type: 'message/updated', message: updated })
    } catch (err) {
      this.notice(`Reaction failed: ${errorMessage(err)}`)
    }
  }

  /* ---------------------------------------------------------------- Typing ---- */

  notifyTyping(conversationId: string): void {
    if (this.stopped || !this.hasFeature('typing-indicators') || !this.rt?.connected) return
    const now = Date.now()
    if (now - (this.typingSentAt.get(conversationId) ?? 0) > TYPING_RESEND_MS) {
      this.typingSentAt.set(conversationId, now)
      this.rt.sendTyping({ conversationId, typing: true })
    }
    clearTimeout(this.typingStopTimers.get(conversationId))
    this.typingStopTimers.set(
      conversationId,
      setTimeout(() => this.stopTyping(conversationId), TYPING_IDLE_MS),
    )
  }

  stopTyping(conversationId: string): void {
    clearTimeout(this.typingStopTimers.get(conversationId))
    this.typingStopTimers.delete(conversationId)
    if (this.typingSentAt.has(conversationId)) {
      this.typingSentAt.delete(conversationId)
      this.rt?.sendTyping({ conversationId, typing: false })
    }
  }

  /* -------------------------------------------------------------- Presence ---- */

  private schedulePresenceWatch(): void {
    if (this.stopped) return
    clearTimeout(this.presenceTimer)
    this.presenceTimer = setTimeout(() => this.watchPresence(), 250)
  }

  private watchPresence(): void {
    if (!this.hasFeature('presence') || !this.rt?.connected) return
    const state = this.host.getState()
    const me = this.userId
    const ids = new Set<string>()
    for (const c of Object.values(state.conversations)) if (c.peer && c.peer.id !== me) ids.add(c.peer.id)
    if (this.activeId) for (const p of state.threads[this.activeId]?.participants ?? []) if (p.userId !== me) ids.add(p.userId)
    const userIds = [...ids].sort().slice(0, 200)
    const key = userIds.join(',')
    if (key === this.presenceKey) return
    if (this.rt.watchPresence({ userIds })) this.presenceKey = key
  }

  /* ------------------------------------------------------------- Directory ---- */

  searchUsers(q: string): Promise<User[]> {
    return this.call((api) => api.searchUsers(q, 20))
  }

  async startDirect(userId: string): Promise<Conversation> {
    const conv = await this.call((api) => api.openDirect(userId))
    this.touch(conv.id)
    this.host.dispatch({ type: 'conv/upsert', conversation: conv })
    void this.open(conv.id)
    return conv
  }

  async createGroup(title: string, members: string[]): Promise<Conversation> {
    const conv = await this.call((api) => api.createGroup({ title, members }))
    this.touch(conv.id)
    this.host.dispatch({ type: 'conv/upsert', conversation: conv })
    void this.open(conv.id)
    return conv
  }

  async updateSettings(id: string, patch: ConversationSettingsPatch): Promise<void> {
    try {
      const conv = await this.call((api) => api.updateSettings(id, patch))
      this.touch(conv.id)
      this.host.dispatch({ type: 'conv/upsert', conversation: conv })
    } catch (err) {
      this.notice(errorMessage(err))
    }
  }

  reconnect(): void {
    void this.rt?.reconnect()
  }
}
