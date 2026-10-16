import type { Conversation, Message, Participant, User } from '../../types'
import type { PendingMessage } from './chatState'

/** Builders for unit tests (not imported by application code). */

export function makeMessage(seq: number, over: Partial<Message> = {}): Message {
  return {
    id: `m${seq}`,
    conversationId: 'c1',
    seq,
    senderId: 'bob',
    clientMsgId: null,
    type: 'TEXT',
    content: `message ${seq}`,
    attachment: null,
    replyToId: null,
    forwarded: false,
    reactions: null,
    edited: false,
    editedAt: null,
    deleted: false,
    createdAt: new Date(Date.UTC(2026, 0, 1, 12, 0, seq)).toISOString(),
    metadata: null,
    ...over,
  }
}

export function makeUser(id: string, over: Partial<User> = {}): User {
  return {
    id,
    displayName: id,
    email: null,
    phone: null,
    avatarUrl: null,
    about: null,
    metadata: null,
    deactivated: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

export function makeConversation(id: string, over: Partial<Conversation> = {}): Conversation {
  return {
    id,
    type: 'DIRECT',
    title: null,
    description: null,
    avatarUrl: null,
    createdBy: 'alice',
    createdAt: '2026-01-01T00:00:00Z',
    lastSeq: 0,
    lastMessage: null,
    unreadCount: 0,
    muted: false,
    archived: false,
    pinned: false,
    role: 'MEMBER',
    peer: null,
    ...over,
  }
}

export function makeParticipant(userId: string, deliveredSeq: number, readSeq: number): Participant {
  return { userId, role: 'MEMBER', displayName: userId, avatarUrl: null, deliveredSeq, readSeq, joinedAt: '2026-01-01T00:00:00Z' }
}

export function makePending(clientMsgId: string, over: Partial<PendingMessage> = {}): PendingMessage {
  return {
    clientMsgId,
    conversationId: 'c1',
    type: 'TEXT',
    content: 'hello',
    attachment: null,
    localPreviewUrl: null,
    fileName: null,
    fileSize: null,
    replyToId: null,
    createdAt: '2026-01-01T12:00:00Z',
    status: 'sending',
    progress: null,
    error: null,
    sentAt: 0,
    ...over,
  }
}
