import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ClientApi, MessageQuery } from '../../api/client'
import { ApiError } from '../../api/http'
import type { RealtimeOptions, RealtimeTransport } from '../../api/realtime'
import type { Conversation, FeatureId, Message, PresenceWatchFrame, RealtimeEvent, ReceiptFrame, SendMessageFrame, TypingFrame } from '../../types'
import { ACK_TIMEOUT_MS, ChatEngine, createProgressGate, type EngineDeps, type SessionStatus } from './chatEngine'
import { chatReducer, initialChatState, type ChatState } from './chatState'
import { makeConversation, makeMessage, makeUser } from './testFixtures'

/* --------------------------------------------------------------- Fakes ---- */

class FakeRealtime implements RealtimeTransport {
  options: RealtimeOptions | null = null
  connected = false
  readonly messages: SendMessageFrame[] = []
  readonly typing: TypingFrame[] = []
  readonly receipts: ReceiptFrame[] = []
  readonly presence: PresenceWatchFrame[] = []

  connect(): void {}
  async disconnect(): Promise<void> {
    this.connected = false
  }
  async reconnect(): Promise<void> {}
  sendMessage(frame: SendMessageFrame): boolean {
    if (this.connected) this.messages.push(frame)
    return this.connected
  }
  sendTyping(frame: TypingFrame): boolean {
    if (this.connected) this.typing.push(frame)
    return this.connected
  }
  sendReceipt(frame: ReceiptFrame): boolean {
    if (this.connected) this.receipts.push(frame)
    return this.connected
  }
  watchPresence(frame: PresenceWatchFrame): boolean {
    if (this.connected) this.presence.push(frame)
    return this.connected
  }

  /** Simulate the socket opening (`resumed` = after a lost connection). */
  open(resumed: boolean): void {
    this.connected = true
    this.options?.onStateChange('open', resumed)
  }
  drop(): void {
    this.connected = false
    this.options?.onStateChange('reconnecting', false)
  }
  emit(event: RealtimeEvent): void {
    this.options?.onEvent(event)
  }
}

function notImplemented(name: string) {
  return () => Promise.reject(new Error(`${name} not stubbed`))
}

function fakeApi(): ClientApi {
  return {
    me: vi.fn(notImplemented('me')),
    updateMe: vi.fn(notImplemented('updateMe')),
    searchUsers: vi.fn(() => Promise.resolve([])),
    getUser: vi.fn(notImplemented('getUser')),
    listConversations: vi.fn(() => Promise.resolve<Conversation[]>([])),
    openDirect: vi.fn(notImplemented('openDirect')),
    createGroup: vi.fn(notImplemented('createGroup')),
    getConversation: vi.fn(notImplemented('getConversation')),
    updateSettings: vi.fn(notImplemented('updateSettings')),
    listMessages: vi.fn((_id: string, _q: MessageQuery) => Promise.resolve<Message[]>([])),
    sendMessage: vi.fn(notImplemented('sendMessage')),
    editMessage: vi.fn(notImplemented('editMessage')),
    deleteMessage: vi.fn(notImplemented('deleteMessage')),
    react: vi.fn(notImplemented('react')),
    unreact: vi.fn(notImplemented('unreact')),
    markRead: vi.fn(() => Promise.resolve(undefined)),
    markDelivered: vi.fn(() => Promise.resolve(undefined)),
    typing: vi.fn(() => Promise.resolve(undefined)),
    uploadMedia: vi.fn(notImplemented('uploadMedia')),
  }
}

const ALL_FEATURES: FeatureId[] = ['presence', 'typing-indicators', 'read-receipts', 'group-chat']

function setup(features: FeatureId[] = ALL_FEATURES) {
  let state: ChatState = initialChatState
  const notices: string[] = []
  const statuses: SessionStatus[] = []
  const rt = new FakeRealtime()
  const api = fakeApi()
  const deps: EngineDeps = {
    mintToken: () => Promise.resolve({ token: 'token-1', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), user: makeUser('alice') }),
    createApi: () => api,
    createRealtime: (options) => {
      rt.options = options
      return rt
    },
  }
  const engine = new ChatEngine(
    {
      tenant: 'acme',
      userId: 'alice',
      dispatch: (action) => {
        state = chatReducer(state, action)
      },
      getState: () => state,
      getFeatures: () => new Set<string>(features),
      getSettings: () => null,
      isVisible: () => true,
      onMe: () => undefined,
      onStatus: (s) => statuses.push(s),
      onFatal: (m) => notices.push(m),
      onNotice: (m) => notices.push(m),
    },
    deps,
  )
  return { engine, rt, api, notices, statuses, state: () => state }
}

/** Let promise chains and zero-delay timers settle. */
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i += 1) await vi.advanceTimersByTimeAsync(0)
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined
  let reject: (reason: unknown) => void = () => undefined
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

/* --------------------------------------------------------------- Tests ---- */

describe('ChatEngine reconnect and resync', () => {
  it('after a reconnect, drops conversations missing from the inbox and syncs only threads still loaded', async () => {
    const { engine, rt, api, state } = setup()
    const c1 = makeConversation('c1', { lastSeq: 2 })
    const c2 = makeConversation('c2', { lastSeq: 1 })
    vi.mocked(api.listConversations).mockResolvedValueOnce([c1, c2]).mockResolvedValueOnce([c1])
    vi.mocked(api.getConversation).mockImplementation((id) => Promise.resolve(id === 'c1' ? c1 : c2))
    vi.mocked(api.listMessages).mockImplementation((_id, q) => Promise.resolve(q.before ? [makeMessage(1), makeMessage(2)] : []))

    await engine.start()
    rt.open(false)
    await engine.open('c1')
    await settle()
    expect(state().threads.c1?.loaded).toBe(true)
    expect(Object.keys(state().conversations).sort()).toEqual(['c1', 'c2'])

    vi.mocked(api.listMessages).mockClear()
    rt.drop()
    rt.open(true)
    await settle()

    // c2 vanished from the (complete) first page while we were offline.
    expect(Object.keys(state().conversations)).toEqual(['c1'])
    // Only the loaded thread was caught up, from its newest seq.
    expect(vi.mocked(api.listMessages).mock.calls).toEqual([['c1', { after: 2, limit: 100 }]])
    engine.stop()
  })

  it('keeps a conversation updated after the refresh request went out', async () => {
    const { engine, rt, api, state } = setup()
    const page = deferred<Conversation[]>()
    vi.mocked(api.listConversations).mockResolvedValueOnce([makeConversation('c1')]).mockReturnValueOnce(page.promise)
    await engine.start()
    rt.open(false)
    rt.drop()
    rt.open(true)
    await settle()
    // A brand-new conversation arrives while the refresh is in flight.
    rt.emit({ type: 'conversation.updated', conversationId: 'fresh', at: '', data: makeConversation('fresh', { createdAt: '2027-01-01T00:00:00Z' }) })
    page.resolve([makeConversation('c1')])
    await settle()
    expect(Object.keys(state().conversations).sort()).toEqual(['c1', 'fresh'])
    engine.stop()
  })
})

describe('ChatEngine opening conversations', () => {
  it('records a failed open as an error (not loaded) and loads on retry', async () => {
    const { engine, api, state } = setup()
    const c1 = makeConversation('c1', { lastSeq: 1 })
    await engine.start()
    vi.mocked(api.getConversation).mockRejectedValueOnce(new ApiError('The service is temporarily unavailable.', 503, 'unavailable')).mockResolvedValue(c1)
    vi.mocked(api.listMessages).mockResolvedValue([makeMessage(1)])

    await engine.open('c1')
    expect(state().threads.c1).toMatchObject({ loaded: false, error: 'The service is temporarily unavailable.' })

    await engine.open('c1')
    await settle()
    expect(state().threads.c1).toMatchObject({ loaded: true, error: null })
    expect(state().threads.c1?.messages).toHaveLength(1)
    engine.stop()
  })

  it('treats 404 from opening as removal of the conversation', async () => {
    const { engine, api, state } = setup()
    vi.mocked(api.listConversations).mockResolvedValueOnce([makeConversation('c1')])
    await engine.start()
    vi.mocked(api.getConversation).mockRejectedValue(new ApiError('Conversation not found', 404, 'not_found'))
    await engine.open('c1')
    await settle()
    expect(state().conversations.c1).toBeUndefined()
    expect(state().threads.c1).toBeUndefined()
    expect(state().activeId).toBeNull()
    engine.stop()
  })

  it('treats 403 from syncing as removal only when the conversation itself is gone', async () => {
    const { engine, api, state } = setup()
    const c1 = makeConversation('c1', { lastSeq: 1 })
    vi.mocked(api.listConversations).mockResolvedValueOnce([c1])
    await engine.start()
    vi.mocked(api.getConversation).mockResolvedValueOnce(c1)
    vi.mocked(api.listMessages).mockResolvedValueOnce([makeMessage(1)])
    await engine.open('c1')
    await settle()

    // A suspended tenant answers 403 everywhere: keep the conversation.
    vi.mocked(api.listMessages).mockRejectedValue(new ApiError("Tenant 'acme' is suspended", 403, 'forbidden'))
    vi.mocked(api.getConversation).mockRejectedValue(new ApiError("Tenant 'acme' is suspended", 403, 'forbidden'))
    await engine.syncThread('c1')
    expect(state().conversations.c1).toBeDefined()

    // Removed from the conversation: the probe answers 404.
    vi.mocked(api.getConversation).mockRejectedValue(new ApiError('Conversation not found', 404, 'not_found'))
    await engine.syncThread('c1')
    expect(state().conversations.c1).toBeUndefined()
    engine.stop()
  })

  it('reloads a thread that was evicted from the cache when it is opened again', async () => {
    const { engine, api, state } = setup()
    await engine.start()
    vi.mocked(api.getConversation).mockImplementation((id) => Promise.resolve(makeConversation(id, { lastSeq: 1 })))
    vi.mocked(api.listMessages).mockImplementation((id, q) => Promise.resolve(q.before ? [makeMessage(1, { conversationId: id })] : []))
    for (const id of ['c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7']) await engine.open(id)
    await settle()
    expect(state().threads.c1).toBeUndefined()

    vi.mocked(api.getConversation).mockClear()
    await engine.open('c1')
    await settle()
    expect(api.getConversation).toHaveBeenCalledWith('c1')
    expect(state().threads.c1?.loaded).toBe(true)
    expect(state().threads.c2).toBeUndefined()
    engine.stop()
  })

  it('fetches an unknown conversation once even when several events mention it', async () => {
    const { engine, rt, api } = setup()
    await engine.start()
    rt.open(false)
    const pending = deferred<Conversation>()
    vi.mocked(api.getConversation).mockReturnValue(pending.promise)
    rt.emit({ type: 'message.new', conversationId: 'c9', at: '', data: makeMessage(1, { conversationId: 'c9' }) })
    rt.emit({ type: 'message.new', conversationId: 'c9', at: '', data: makeMessage(2, { conversationId: 'c9' }) })
    await settle()
    expect(api.getConversation).toHaveBeenCalledTimes(1)
    pending.resolve(makeConversation('c9', { lastSeq: 2 }))
    await settle()
    engine.stop()
  })
})

describe('ChatEngine receipts', () => {
  it('records a receipt as sent only after the REST call succeeded', async () => {
    const { engine, api } = setup()
    const unread = makeConversation('c1', { lastSeq: 3, unreadCount: 2 })
    vi.mocked(api.listConversations).mockResolvedValue([unread])
    vi.mocked(api.markDelivered).mockRejectedValueOnce(new ApiError('down', 0, 'network_error')).mockResolvedValue(undefined)

    await engine.start() // socket not open: delivered receipt goes over REST and fails
    await settle()
    expect(api.markDelivered).toHaveBeenCalledTimes(1)

    await engine.loadInbox() // not recorded as sent, so it is retried
    await settle()
    expect(api.markDelivered).toHaveBeenCalledTimes(2)

    await engine.loadInbox() // succeeded last time: not sent again
    await settle()
    expect(api.markDelivered).toHaveBeenCalledTimes(2)
    engine.stop()
  })

  it('publishes receipts on the socket once it is open', async () => {
    const { engine, rt, api } = setup()
    vi.mocked(api.listConversations).mockResolvedValue([makeConversation('c1', { lastSeq: 3, unreadCount: 1 })])
    vi.mocked(api.markDelivered).mockRejectedValueOnce(new ApiError('down', 0, 'network_error'))
    await engine.start()
    await settle()
    rt.open(false)
    await engine.loadInbox()
    expect(rt.receipts).toEqual([{ conversationId: 'c1', seq: 3, kind: 'delivered' }])
    expect(api.markDelivered).toHaveBeenCalledTimes(1) // only the failed REST attempt from before the socket opened
    engine.stop()
  })
})

describe('ChatEngine sending', () => {
  it('does not fail a send on ack timeout while its REST fallback is still in flight', async () => {
    const { engine, api, state } = setup()
    await engine.start()
    const response = deferred<Message>()
    vi.mocked(api.sendMessage).mockReturnValue(response.promise)

    engine.send('c1', 'hello', null) // socket down: REST fallback
    const [clientMsgId] = Object.keys(state().pending)
    expect(clientMsgId).toBeDefined()
    await vi.advanceTimersByTimeAsync(ACK_TIMEOUT_MS * 2)
    expect(Object.values(state().pending)[0]?.status).toBe('sending')

    response.resolve(makeMessage(1, { senderId: 'alice', clientMsgId: clientMsgId ?? null, content: 'hello' }))
    await settle()
    expect(state().pending).toEqual({})
    engine.stop()
  })

  it('fails a socket send that is never acknowledged', async () => {
    const { engine, rt, state } = setup()
    await engine.start()
    rt.open(false)
    engine.send('c1', 'hello', null)
    expect(rt.messages).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(ACK_TIMEOUT_MS + 2_500)
    expect(Object.values(state().pending)[0]?.status).toBe('failed')
    engine.stop()
  })
})

describe('ChatEngine typing', () => {
  it('sends typing:false for an active typing indicator when stopped', async () => {
    const { engine, rt } = setup()
    await engine.start()
    rt.open(false)
    engine.notifyTyping('c1')
    expect(rt.typing).toEqual([{ conversationId: 'c1', typing: true }])
    engine.stop()
    expect(rt.typing).toEqual([
      { conversationId: 'c1', typing: true },
      { conversationId: 'c1', typing: false },
    ])
  })

  it('neither sends nor shows typing when typing-indicators is disabled', async () => {
    const { engine, rt, state } = setup(['presence'])
    await engine.start()
    rt.open(false)
    engine.notifyTyping('c1')
    rt.emit({ type: 'typing', conversationId: 'c1', at: '', data: { userId: 'bob', typing: true } })
    expect(rt.typing).toEqual([])
    expect(state().typing).toEqual({})
    engine.stop()
  })
})

describe('createProgressGate', () => {
  it('lets through at most one update per interval, plus the final one', () => {
    let now = 0
    const gate = createProgressGate(100, () => now)
    expect(gate(0.01)).toBe(true)
    now = 50
    expect(gate(0.2)).toBe(false)
    now = 120
    expect(gate(0.3)).toBe(true)
    now = 130
    expect(gate(1)).toBe(true)
    expect(gate(1)).toBe(false)
  })
})
