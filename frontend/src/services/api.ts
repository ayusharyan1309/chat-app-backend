import axios from 'axios';
import type { DatabaseConfig, Conversation, PaginatedMessages, PlatformDbConfig, User } from '../types';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

const api = axios.create({
  baseURL: API_BASE,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// ============================================================
// Auth Interceptor — attach Firebase token if available
// ============================================================
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('auth_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ============================================================
// Chat Storage Config API
// ============================================================

export const chatStorageApi = {
  /**
   * Get the current active storage configuration.
   */
  getConfig(): Promise<DatabaseConfig> {
    return api.get('/api/admin/storage/config').then((r) => r.data);
  },

  /**
   * Update the chat storage backend configuration.
   */
  updateConfig(config: DatabaseConfig): Promise<{ success: boolean; message: string }> {
    return api.put('/api/admin/storage/config', config).then((r) => r.data);
  },

  /**
   * Test the storage connection.
   */
  testConnection(): Promise<{ healthy: boolean; type: string; message: string }> {
    return api.post('/api/admin/storage/test').then((r) => r.data);
  },

  /**
   * Get available storage types.
   */
  getAvailableTypes(): Promise<{ id: string; name: string; description: string }[]> {
    return api.get('/api/admin/storage/types').then((r) => r.data);
  },
};

// ============================================================
// Platform User DB API
// ============================================================

export const platformUserDbApi = {
  /**
   * Get all configured platform databases for user fetching.
   */
  getPlatforms(): Promise<PlatformDbConfig[]> {
    return api.get('/api/admin/platforms').then((r) => r.data);
  },

  /**
   * Add or update a platform database configuration.
   */
  savePlatform(config: PlatformDbConfig): Promise<{ success: boolean; message: string }> {
    return api.post('/api/admin/platforms', config).then((r) => r.data);
  },

  /**
   * Remove a platform database configuration.
   */
  removePlatform(platformId: string): Promise<{ success: boolean; message: string }> {
    return api.delete(`/api/admin/platforms/${platformId}`).then((r) => r.data);
  },

  /**
   * Test connection to a platform database.
   */
  testPlatform(platformId: string): Promise<{ healthy: boolean; message: string }> {
    return api.post(`/api/admin/platforms/${platformId}/test`).then((r) => r.data);
  },

  /**
   * Fetch users from a specific platform database.
   */
  getUsersFromPlatform(platformId: string, query?: string): Promise<User[]> {
    return api.get(`/api/admin/platforms/${platformId}/users`, { params: { q: query } }).then((r) => r.data);
  },

  /**
   * Fetch all users from all configured platforms.
   */
  getAllUsers(query?: string): Promise<User[]> {
    return api.get('/api/admin/users', { params: { q: query } }).then((r) => r.data);
  },
};

// ============================================================
// Chat API
// ============================================================

export const chatApi = {
  /**
   * Get all conversations for the current user.
   */
  getConversations(): Promise<Conversation[]> {
    return api.get('/chat/friends-chats').then((r) => r.data);
  },

  /**
   * Get a conversation with a specific user.
   */
  getConversationWithUser(userId: number): Promise<Conversation | null> {
    return api.get(`/chat/conversation/with/${userId}`).then((r) => r.data);
  },

  /**
   * Get paginated messages for a conversation.
   */
  getMessages(conversationId: string, page = 0, size = 40): Promise<PaginatedMessages> {
    return api.get(`/chat/conversation/${conversationId}/messages`, {
      params: { page, size },
    }).then((r) => r.data);
  },

  /**
   * Get unread messages.
   */
  getUnreadMessages(): Promise<Record<string, unknown>> {
    return api.get('/chat/unread-messages').then((r) => r.data);
  },

  /**
   * Mark messages as read.
   */
  markMessagesAsRead(messageIds: string[]): Promise<void> {
    return api.post('/chat/mark-messages-read', messageIds).then(() => undefined);
  },

  /**
   * Accept a conversation.
   */
  acceptConversation(conversationId: string): Promise<void> {
    return api.post(`/chat/conversation/${conversationId}/accept`).then(() => undefined);
  },

  /**
   * Block a user in a conversation.
   */
  blockUser(conversationId: string): Promise<void> {
    return api.post(`/chat/conversation/${conversationId}/block`).then(() => undefined);
  },

  /**
   * Unblock a user in a conversation.
   */
  unblockUser(conversationId: string): Promise<void> {
    return api.post(`/chat/conversation/${conversationId}/unblock`).then(() => undefined);
  },
};

export default api;
