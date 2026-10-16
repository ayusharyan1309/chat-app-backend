import type { AxiosInstance } from 'axios'
import { publicHttp, query, seg } from './http'
import type {
  Conversation,
  ConversationSettingsPatch,
  CreateGroupRequest,
  MediaUploadResult,
  Message,
  SendMessageRequest,
  TenantPublicConfig,
  User,
} from '../types'

/** Public, unauthenticated tenant config (enabled features, settings, branding). */
export function getTenantConfig(slug: string): Promise<TenantPublicConfig> {
  return publicHttp.get<TenantPublicConfig>(`/api/v1/tenants/${seg(slug)}/config`).then((r) => r.data)
}

export interface MessageQuery {
  after?: number
  before?: number
  limit?: number
}

/** End-user client API (`/api/v1/**`, user JWT). Bound to an axios instance carrying the token. */
export function createClientApi(http: AxiosInstance) {
  const conv = (id: string) => `/api/v1/conversations/${seg(id)}`
  return {
    me: () => http.get<User>('/api/v1/me').then((r) => r.data),
    updateMe: (body: { displayName?: string; avatarUrl?: string; about?: string }) =>
      http.patch<User>('/api/v1/me', body).then((r) => r.data),

    searchUsers: (q: string, limit = 20) =>
      http.get<User[]>('/api/v1/users', { params: query({ q, limit }) }).then((r) => r.data),
    getUser: (id: string) => http.get<User>(`/api/v1/users/${seg(id)}`).then((r) => r.data),

    listConversations: (params: { limit?: number; before?: string } = {}) =>
      http
        .get<Conversation[]>('/api/v1/conversations', { params: query({ limit: params.limit ?? 50, before: params.before }) })
        .then((r) => r.data),
    openDirect: (userId: string) =>
      http.post<Conversation>('/api/v1/conversations/direct', { userId }).then((r) => r.data),
    createGroup: (body: CreateGroupRequest) =>
      http.post<Conversation>('/api/v1/conversations/group', body).then((r) => r.data),
    getConversation: (id: string) => http.get<Conversation>(conv(id)).then((r) => r.data),
    updateSettings: (id: string, body: ConversationSettingsPatch) =>
      http.patch<Conversation>(`${conv(id)}/settings`, body).then((r) => r.data),

    listMessages: (id: string, params: MessageQuery) =>
      http
        .get<Message[]>(`${conv(id)}/messages`, {
          params: query({ after: params.after, before: params.before, limit: params.limit ?? 50 }),
        })
        .then((r) => r.data),
    sendMessage: (id: string, body: SendMessageRequest) =>
      http.post<Message>(`${conv(id)}/messages`, body).then((r) => r.data),
    editMessage: (id: string, messageId: string, content: string) =>
      http.patch<Message>(`${conv(id)}/messages/${seg(messageId)}`, { content }).then((r) => r.data),
    deleteMessage: (id: string, messageId: string) =>
      http.delete<Message>(`${conv(id)}/messages/${seg(messageId)}`).then((r) => r.data),
    react: (id: string, messageId: string, emoji: string) =>
      http.put<Message>(`${conv(id)}/messages/${seg(messageId)}/reactions`, { emoji }).then((r) => r.data),
    unreact: (id: string, messageId: string) =>
      http.delete<Message>(`${conv(id)}/messages/${seg(messageId)}/reactions`).then((r) => r.data),

    markRead: (id: string, seq: number) => http.post<void>(`${conv(id)}/read`, { seq }).then(() => undefined),
    markDelivered: (id: string, seq: number) => http.post<void>(`${conv(id)}/delivered`, { seq }).then(() => undefined),
    typing: (id: string, typing: boolean) => http.post<void>(`${conv(id)}/typing`, { typing }).then(() => undefined),

    uploadMedia: (file: File, onProgress?: (fraction: number) => void) => {
      const form = new FormData()
      form.append('file', file)
      return http
        .post<MediaUploadResult>('/api/v1/media', form, {
          timeout: 300_000,
          onUploadProgress: (e) => {
            if (onProgress && e.total) onProgress(e.loaded / e.total)
          },
        })
        .then((r) => r.data)
    },
  }
}

export type ClientApi = ReturnType<typeof createClientApi>
