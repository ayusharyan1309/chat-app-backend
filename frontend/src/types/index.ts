// ============================================================
// Database Configuration Types
// ============================================================

export type StorageType = 'mysql' | 'postgresql' | 'mongodb' | 'firebase' | 'supabase' | 'h2';

export interface DatabaseConfig {
  type: StorageType;
  sql?: SqlConfig;
  mongodb?: MongoDbConfig;
  firebase?: FirebaseConfig;
  supabase?: SupabaseConfig;
}

export interface SqlConfig {
  url: string;
  username: string;
  password: string;
  driverClassName: string;
  poolSize: number;
}

export interface MongoDbConfig {
  uri?: string;
  host: string;
  port: number;
  database: string;
  username?: string;
  password?: string;
  authDatabase?: string;
}

export interface FirebaseConfig {
  projectId: string;
  credentialPath: string;
  firestoreDatabase: string;
}

export interface SupabaseConfig {
  url: string;
  apiKey: string;
  anonKey?: string;
  schema: string;
}

// ============================================================
// Platform User Database Types
// ============================================================

export type PlatformDbType = 'supabase' | 'mysql' | 'postgresql' | 'mongodb' | 'firebase' | 'custom';

export interface PlatformDbConfig {
  id: string;
  name: string;
  type: PlatformDbType;
  isActive: boolean;
  supabase?: {
    url: string;
    apiKey: string;
    anonKey?: string;
  };
  sql?: {
    url: string;
    username: string;
    password: string;
    driverClassName: string;
  };
  mongodb?: {
    uri: string;
    database: string;
  };
  firebase?: {
    projectId: string;
    credentialPath: string;
  };
  custom?: {
    apiUrl: string;
    apiKey: string;
    headers?: Record<string, string>;
  };
}

// ============================================================
// User Types
// ============================================================

export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string;
  platformId?: string;
  source?: string; // Which platform DB the user came from
}

// ============================================================
// Chat Types
// ============================================================

export interface Conversation {
  id: string;
  user1Id: string;
  user1Email: string;
  user2Id: string;
  user2Email: string;
  lastMessage?: string;
  lastMessageTime?: string;
  status: 'INIT' | 'ACTIVE' | 'INACTIVE';
  blockedByUser1: boolean;
  blockedByUser2: boolean;
  messageCount: number;
}

export interface ChatMessage {
  id: string;
  conversationId: string;
  senderId: string;
  senderEmail: string;
  senderName?: string;
  receiverId: string;
  receiverEmail: string;
  content: string;
  messageType: 'TEXT' | 'IMAGE' | 'FILE' | 'VIDEO' | 'AUDIO' | 'SYSTEM';
  mediaUrl?: string;
  read: boolean;
  readAt?: string;
  status: 'SENT' | 'DELIVERED' | 'READ' | 'FAILED';
  createdAt: string;
  updatedAt: string;
}

// ============================================================
// WebSocket Types
// ============================================================

export interface TypingIndicator {
  senderUsername: string;
  recipientUsername: string;
  typing: boolean;
}

// ============================================================
// API Response Types
// ============================================================

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
}

export interface PaginatedMessages {
  messages: ChatMessage[];
  hasMore: boolean;
  total: number;
}
