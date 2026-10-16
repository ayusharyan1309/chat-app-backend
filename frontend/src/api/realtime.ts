import { Client, ReconnectionTimeMode, type IMessage, type StompSubscription } from '@stomp/stompjs'
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
  onProtocolError?: (message: string) => void
  url?: string
}

const EVENTS_DESTINATION = '/user/queue/events'

function isEvent(value: unknown): value is RealtimeEvent {
  return isRecord(value) && typeof value.type === 'string' && 'data' in value
}

/**
 * STOMP over native WebSocket (`/ws`) with exponential-backoff reconnect.
 * Sends: /app/messages.send, /app/typing, /app/receipts, /app/presence.watch.
 * Receives envelopes on /user/queue/events.
 */
export class RealtimeClient {
  private readonly client: Client
  private readonly options: RealtimeOptions
  private subscription: StompSubscription | null = null
  private hasConnected = false
  private stopped = false

  constructor(options: RealtimeOptions) {
    this.options = options
    this.client = new Client({
      brokerURL: options.url ?? realtimeUrl(),
      reconnectDelay: 1_000,
      maxReconnectDelay: 20_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 20_000,
      heartbeatOutgoing: 20_000,
      connectionTimeout: 10_000,
      beforeConnect: async (client) => {
        this.options.onStateChange(this.hasConnected ? 'reconnecting' : 'connecting', false)
        const token = await this.options.getToken()
        client.connectHeaders = { Authorization: `Bearer ${token}` }
      },
      onConnect: () => {
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
    this.subscription = null
    await this.client.deactivate()
    this.options.onStateChange('closed', false)
  }

  /** Force a fresh connection (e.g. after a token refresh). */
  async reconnect(): Promise<void> {
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
