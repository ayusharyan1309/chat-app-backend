import { Client, ReconnectionTimeMode, type IMessage, type IStompSocket, type StompSubscription } from '@stomp/stompjs'
import { realtimeUrl } from '../lib/config'
import { isRecord } from '../lib/util'
import type {
  PresenceWatchFrame,
  RealtimeEvent,
  ReceiptFrame,
  SendMessageFrame,
  TypingFrame,
} from '../types'

export type ConnectionState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface RealtimeOptions {
  /** Called before every (re)connect so a refreshed token is always used. */
  getToken: () => Promise<string>
  onEvent: (event: RealtimeEvent) => void
  /** `resumed` is true when a connection opens after a previous one was lost. */
  onStateChange: (state: ConnectionState, resumed: boolean) => void
  /** STOMP ERROR frames and malformed frames. */
  onProtocolError?: (message: string) => void
  /** `getToken` failed; the client backs off and tries again on its own. */
  onTokenError?: (error: unknown, retryInMs: number) => void
  url?: string
  /** Socket factory (tests); defaults to a native WebSocket on `url`. */
  webSocketFactory?: () => IStompSocket
}

/** What the chat engine needs from a realtime connection (lets tests substitute a fake). */
export interface RealtimeTransport {
  readonly connected: boolean
  connect(): void
  disconnect(): Promise<void>
  reconnect(): Promise<void>
  sendMessage(frame: SendMessageFrame): boolean
  sendTyping(frame: TypingFrame): boolean
  sendReceipt(frame: ReceiptFrame): boolean
  watchPresence(frame: PresenceWatchFrame): boolean
}

const EVENTS_DESTINATION = '/user/queue/events'
const RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 20_000

function isEvent(value: unknown): value is RealtimeEvent {
  return isRecord(value) && typeof value.type === 'string' && 'data' in value
}

/**
 * STOMP over native WebSocket (`/ws`) with exponential-backoff reconnect.
 * Sends: /app/messages.send, /app/typing, /app/receipts, /app/presence.watch.
 * Receives envelopes on /user/queue/events.
 */
export class RealtimeClient implements RealtimeTransport {
  private readonly client: Client
  private readonly options: RealtimeOptions
  private subscription: StompSubscription | null = null
  private hasConnected = false
  private stopped = false
  private tokenFailures = 0
  private attempt = 0
  private tokenRetryTimer: ReturnType<typeof setTimeout> | undefined

  constructor(options: RealtimeOptions) {
    this.options = options
    this.client = new Client({
      brokerURL: options.webSocketFactory ? undefined : (options.url ?? realtimeUrl()),
      webSocketFactory: options.webSocketFactory,
      reconnectDelay: RECONNECT_DELAY_MS,
      maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 20_000,
      heartbeatOutgoing: 20_000,
      connectionTimeout: 10_000,
      // stompjs awaits this inside `_connect()` without a catch: a rejection would leave the client
      // ACTIVE with no socket and no reconnect timer, i.e. silently dead. It must never reject.
      beforeConnect: (client) => this.beforeConnect(client),
      onConnect: () => {
        this.tokenFailures = 0
        this.subscription?.unsubscribe()
        this.subscription = this.client.subscribe(EVENTS_DESTINATION, (frame: IMessage) => this.handleFrame(frame))
        const resumed = this.hasConnected
        this.hasConnected = true
        this.options.onStateChange('open', resumed)
      },
      onWebSocketClose: () => {
        this.subscription = null
        if (!this.stopped) this.options.onStateChange(this.hasConnected ? 'reconnecting' : 'connecting', false)
      },
      onStompError: (frame) => {
        this.options.onProtocolError?.(frame.headers.message || frame.body || 'The server rejected the connection.')
      },
    })
  }

  private async beforeConnect(client: Client): Promise<void> {
    if (this.stopped) return
    const attempt = ++this.attempt
    this.options.onStateChange(this.hasConnected ? 'reconnecting' : 'connecting', false)
    try {
      const token = await this.options.getToken()
      client.connectHeaders = { Authorization: `Bearer ${token}` }
    } catch (err) {
      // Superseded by a newer attempt (e.g. a manual reconnect) or stopped meanwhile.
      if (this.stopped || attempt !== this.attempt) return
      // Abort this attempt instead of sending a CONNECT without credentials: deactivating now (no
      // socket exists yet, so it completes synchronously) makes `_connect()` bail out after we
      // return, and our own backoff timer activates the client again.
      this.tokenFailures += 1
      const delay = Math.min(RECONNECT_DELAY_MS * 2 ** (this.tokenFailures - 1), MAX_RECONNECT_DELAY_MS)
      void this.client.deactivate()
      this.scheduleTokenRetry(delay)
      this.options.onTokenError?.(err, delay)
    }
  }

  private scheduleTokenRetry(delay: number): void {
    clearTimeout(this.tokenRetryTimer)
    this.tokenRetryTimer = setTimeout(() => {
      this.tokenRetryTimer = undefined
      if (!this.stopped) this.client.activate()
    }, delay)
  }

  private handleFrame(frame: IMessage): void {
    try {
      const parsed: unknown = JSON.parse(frame.body)
      if (isEvent(parsed)) this.options.onEvent(parsed)
    } catch {
      this.options.onProtocolError?.('Received a malformed realtime frame.')
    }
  }

  connect(): void {
    this.stopped = false
    this.client.activate()
  }

  async disconnect(): Promise<void> {
    this.stopped = true
    clearTimeout(this.tokenRetryTimer)
    this.tokenRetryTimer = undefined
    this.subscription = null
    await this.client.deactivate()
    this.options.onStateChange('closed', false)
  }

  /** Force a fresh connection (e.g. after a token refresh). */
  async reconnect(): Promise<void> {
    clearTimeout(this.tokenRetryTimer)
    this.tokenRetryTimer = undefined
    this.tokenFailures = 0
    await this.client.deactivate()
    if (!this.stopped) this.client.activate()
  }

  get connected(): boolean {
    return this.client.connected
  }

  private publish(destination: string, body: unknown): boolean {
    if (!this.client.connected) return false
    try {
      this.client.publish({ destination, body: JSON.stringify(body), headers: { 'content-type': 'application/json' } })
      return true
    } catch {
      return false
    }
  }

  sendMessage(frame: SendMessageFrame): boolean {
    return this.publish('/app/messages.send', frame)
  }

  sendTyping(frame: TypingFrame): boolean {
    return this.publish('/app/typing', frame)
  }

  sendReceipt(frame: ReceiptFrame): boolean {
    return this.publish('/app/receipts', frame)
  }

  watchPresence(frame: PresenceWatchFrame): boolean {
    return this.publish('/app/presence.watch', frame)
  }
}
