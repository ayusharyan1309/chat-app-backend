import type { Attachment, Conversation, Message, MessageType } from './client'
import type { IsoDateTime, JsonObject } from './common'

/* Client -> server (SEND) bodies */

export interface SendMessageFrame {
  conversationId: string
  clientMsgId: string
  type?: MessageType
  content?: string | null
  attachment?: Attachment | null
  replyToId?: string | null
  forwarded?: boolean
  metadata?: JsonObject
}

export interface TypingFrame {
  conversationId: string
  typing: boolean
}

export type ReceiptKind = 'delivered' | 'read'

export interface ReceiptFrame {
  conversationId: string
  seq: number
  kind: ReceiptKind
}

export interface PresenceWatchFrame {
  userIds: string[]
}

/* Server -> client (/user/queue/events) */

export interface MessageAckData {
  clientMsgId: string
  message: Message
  duplicate: boolean
}

export interface ReceiptData {
  userId: string
  deliveredSeq: number
  readSeq: number
}

export interface TypingData {
  userId: string
  typing: boolean
}

export interface PresenceData {
  userId: string
  online: boolean
  lastSeenAt: IsoDateTime | null
}

export interface ConversationRemovedData {
  conversationId: string
}

export interface RealtimeErrorData {
  code: string
  message: string
  clientMsgId?: string | null
}

interface EnvelopeBase<T extends string, D> {
  type: T
  conversationId: string | null
  at: IsoDateTime
  data: D
}

export type RealtimeEvent =
  | EnvelopeBase<'message.new', Message>
  | EnvelopeBase<'message.ack', MessageAckData>
  | EnvelopeBase<'message.updated', Message>
  | EnvelopeBase<'receipt', ReceiptData>
  | EnvelopeBase<'typing', TypingData>
  | EnvelopeBase<'presence', PresenceData>
  | EnvelopeBase<'conversation.updated', Conversation>
  | EnvelopeBase<'conversation.removed', ConversationRemovedData>
  | EnvelopeBase<'error', RealtimeErrorData>

export type RealtimeEventType = RealtimeEvent['type']
