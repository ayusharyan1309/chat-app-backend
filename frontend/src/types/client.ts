import type { IsoDateTime, JsonObject } from './common'
import type { ExternalAuthType, TenantBranding, TenantSettings } from './tenants'

export interface User {
  id: string
  displayName: string | null
  email: string | null
  phone: string | null
  avatarUrl: string | null
  about: string | null
  metadata: JsonObject | null
  deactivated: boolean
  createdAt: IsoDateTime
  /** Only when the `presence` feature is enabled. */
  lastSeenAt?: IsoDateTime | null
  online?: boolean | null
}

export type ConversationType = 'DIRECT' | 'GROUP'
export type ParticipantRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface LastMessagePreview {
  id: string
  preview: string | null
  senderId: string
  at: IsoDateTime
}

export interface Participant {
  userId: string
  role: ParticipantRole
  displayName: string | null
  avatarUrl: string | null
  deliveredSeq: number
  readSeq: number
  joinedAt: IsoDateTime
}

export interface Conversation {
  id: string
  type: ConversationType
  title: string | null
  description: string | null
  avatarUrl: string | null
  createdBy: string
  createdAt: IsoDateTime
  lastSeq: number
  lastMessage: LastMessagePreview | null
  unreadCount: number
  muted: boolean
  archived: boolean
  pinned: boolean
  role: ParticipantRole
  /** DIRECT only: the other participant. */
  peer: User | null
  /** Detail view only. */
  participants?: Participant[]
}

export type MessageType = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'FILE' | 'LOCATION' | 'CONTACT' | 'STICKER' | 'SYSTEM'

export interface Attachment {
  mediaId: string
  url: string
  mimeType: string | null
  size: number | null
  fileName: string | null
  width?: number | null
  height?: number | null
  durationMs?: number | null
  thumbnailUrl?: string | null
}

export interface Message {
  id: string
  conversationId: string
  seq: number
  senderId: string
  clientMsgId: string | null
  type: MessageType
  content: string | null
  attachment: Attachment | null
  replyToId: string | null
  forwarded: boolean
  /** emoji -> userIds */
  reactions: Record<string, string[]> | null
  edited: boolean
  editedAt: IsoDateTime | null
  deleted: boolean
  createdAt: IsoDateTime
  metadata: JsonObject | null
}

export interface SendMessageRequest {
  clientMsgId: string
  type?: MessageType
  content?: string | null
  attachment?: Attachment | null
  replyToId?: string | null
  forwarded?: boolean
  metadata?: JsonObject
}

export interface CreateGroupRequest {
  title: string
  members: string[]
  description?: string
  avatarUrl?: string
}

export interface ConversationSettingsPatch {
  muted?: boolean
  archived?: boolean
  pinned?: boolean
}

export interface MediaUploadResult {
  mediaId: string
  url: string
  mimeType: string | null
  size: number | null
  fileName: string | null
}

export interface TenantPublicConfig {
  tenant: string
  name: string
  branding: TenantBranding
  /** Enabled feature ids. */
  features: string[]
  auth: { devLoginEnabled: boolean; externalType: ExternalAuthType }
  settings: TenantSettings
}
