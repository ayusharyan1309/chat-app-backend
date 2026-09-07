import React, { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react';
import type { DatabaseConfig, StorageType, PlatformDbConfig, User, Conversation, ChatMessage } from '../types';
import { chatStorageApi, platformUserDbApi, chatApi } from '../services/api';
import wsService from '../services/websocket';
import userService from '../services/userService';

// ============================================================
// App State
// ============================================================

interface AppState {
  // Auth
  isLoggedIn: boolean;
  currentUser: User | null;
  authToken: string | null;

  // Database Config
  storageType: StorageType;
  dbConfig: DatabaseConfig;
  dbConfigLoading: boolean;
  dbConfigError: string | null;

  // Platform DBs
  platformConfigs: PlatformDbConfig[];

  // Users
  users: User[];
  usersLoading: boolean;

  // Chat
  conversations: Conversation[];
  activeConversation: Conversation | null;
  messages: ChatMessage[];
  messagesLoading: boolean;
  typingUsers: Set<string>;
  wsConnected: boolean;
}

// ============================================================
// Actions
// ============================================================

interface AppActions {
  // Auth
  login: (email: string, token: string) => void;
  logout: () => void;

  // Database Config
  setStorageType: (type: StorageType) => void;
  saveDbConfig: (config: DatabaseConfig) => Promise<void>;
  testDbConnection: () => Promise<boolean>;

  // Platform DBs
  addPlatform: (config: PlatformDbConfig) => Promise<void>;
  removePlatform: (platformId: string) => Promise<void>;
  refreshPlatforms: () => Promise<void>;

  // Users
  searchUsers: (query: string) => Promise<void>;
  refreshUsers: () => Promise<void>;

  // Chat
  selectConversation: (conversation: Conversation) => void;
  sendMessage: (content: string) => void;
  loadMoreMessages: () => Promise<void>;
  refreshConversations: () => Promise<void>;
}

// ============================================================
// Context
// ============================================================

const AppContext = createContext<(AppState & AppActions) | null>(null);

export const useApp = () => {
  const context = useContext(AppContext);
  if (!context) throw new Error('useApp must be used within AppProvider');
  return context;
};

// ============================================================
// Default DB Config per type
// ============================================================

const defaultDbConfig: Record<StorageType, DatabaseConfig> = {
  h2: { type: 'h2', sql: { url: 'jdbc:h2:mem:chat_storage', username: 'sa', password: '', driverClassName: 'org.h2.Driver', poolSize: 10 } },
  mysql: { type: 'mysql', sql: { url: 'jdbc:mysql://localhost:3306/chat_db', username: 'root', password: '', driverClassName: 'com.mysql.cj.jdbc.Driver', poolSize: 20 } },
  postgresql: { type: 'postgresql', sql: { url: 'jdbc:postgresql://localhost:5432/chat_db', username: 'postgres', password: '', driverClassName: 'org.postgresql.Driver', poolSize: 20 } },
  mongodb: { type: 'mongodb', mongodb: { host: 'localhost', port: 27017, database: 'chat_db' } },
  firebase: { type: 'firebase', firebase: { projectId: '', credentialPath: 'firebase-service-account.json', firestoreDatabase: '(default)' } },
  supabase: { type: 'supabase', supabase: { url: '', apiKey: '', schema: 'public' } },
};

// ============================================================
// Provider
// ============================================================

export const AppProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  // Auth state
  const [isLoggedIn, setIsLoggedIn] = useState(() => !!localStorage.getItem('auth_token'));
  const [currentUser, setCurrentUser] = useState<User | null>(() => {
    const saved = localStorage.getItem('current_user');
    return saved ? JSON.parse(saved) : null;
  });
  const [authToken, setAuthToken] = useState(() => localStorage.getItem('auth_token'));

  // DB config state
  const [storageType, setStorageTypeState] = useState<StorageType>('h2');
  const [dbConfig, setDbConfig] = useState<DatabaseConfig>(defaultDbConfig.h2);
  const [dbConfigLoading, setDbConfigLoading] = useState(false);
  const [dbConfigError, setDbConfigError] = useState<string | null>(null);

  // Platform state
  const [platformConfigs, setPlatformConfigs] = useState<PlatformDbConfig[]>([]);

  // Users state
  const [users, setUsers] = useState<User[]>([]);
  const [usersLoading, setUsersLoading] = useState(false);

  // Chat state
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConversation, setActiveConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [typingUsers, setTypingUsers] = useState<Set<string>>(new Set());
  const [wsConnected, setWsConnected] = useState(false);

  // ============================================================
  // Auth Actions
  // ============================================================

  const login = useCallback((email: string, token: string) => {
    localStorage.setItem('auth_token', token);
    localStorage.setItem('current_user', JSON.stringify({ id: email, email, fullName: email }));
    setAuthToken(token);
    setCurrentUser({ id: email, email, fullName: email });
    setIsLoggedIn(true);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('auth_token');
    localStorage.removeItem('current_user');
    wsService.disconnect();
    setAuthToken(null);
    setCurrentUser(null);
    setIsLoggedIn(false);
    setConversations([]);
    setActiveConversation(null);
    setMessages([]);
  }, []);

  // ============================================================
  // DB Config Actions
  // ============================================================

  const setStorageType = useCallback((type: StorageType) => {
    setStorageTypeState(type);
    setDbConfig(defaultDbConfig[type]);
    setDbConfigError(null);
  }, []);

  const saveDbConfig = useCallback(async (config: DatabaseConfig) => {
    setDbConfigLoading(true);
    setDbConfigError(null);
    try {
      await chatStorageApi.updateConfig(config);
      setDbConfig(config);
      setStorageTypeState(config.type);
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to save config';
      setDbConfigError(message);
      throw e;
    } finally {
      setDbConfigLoading(false);
    }
  }, []);

  const testDbConnection = useCallback(async (): Promise<boolean> => {
    setDbConfigLoading(true);
    try {
      const result = await chatStorageApi.testConnection();
      return result.healthy;
    } catch {
      return false;
    } finally {
      setDbConfigLoading(false);
    }
  }, []);

  // ============================================================
  // Platform DB Actions
  // ============================================================

  const addPlatform = useCallback(async (config: PlatformDbConfig) => {
    await platformUserDbApi.savePlatform(config);
    setPlatformConfigs((prev) => {
      const exists = prev.findIndex((p) => p.id === config.id);
      if (exists >= 0) {
        const updated = [...prev];
        updated[exists] = config;
        return updated;
      }
      return [...prev, config];
    });
    userService.addPlatform(config);
  }, []);

  const removePlatform = useCallback(async (platformId: string) => {
    await platformUserDbApi.removePlatform(platformId);
    setPlatformConfigs((prev) => prev.filter((p) => p.id !== platformId));
    userService.removePlatform(platformId);
  }, []);

  const refreshPlatforms = useCallback(async () => {
    try {
      const configs = await platformUserDbApi.getPlatforms();
      setPlatformConfigs(configs);
      userService.setPlatformConfigs(configs);
    } catch (e) {
      console.error('[App] Failed to refresh platforms:', e);
    }
  }, []);

  // ============================================================
  // User Actions
  // ============================================================

  const searchUsers = useCallback(async (query: string) => {
    setUsersLoading(true);
    try {
      const allUsers = await userService.getAllUsers(query);
      setUsers(allUsers);
    } catch (e) {
      console.error('[App] Failed to search users:', e);
    } finally {
      setUsersLoading(false);
    }
  }, []);

  const refreshUsers = useCallback(async () => {
    setUsersLoading(true);
    try {
      const allUsers = await userService.getAllUsers();
      setUsers(allUsers);
    } catch (e) {
      console.error('[App] Failed to refresh users:', e);
    } finally {
      setUsersLoading(false);
    }
  }, []);

  // ============================================================
  // Chat Actions
  // ============================================================

  const selectConversation = useCallback(async (conversation: Conversation) => {
    setActiveConversation(conversation);
    setMessagesLoading(true);
    try {
      const data = await chatApi.getMessages(conversation.id);
      setMessages(data.messages.reverse());
    } catch (e) {
      console.error('[App] Failed to load messages:', e);
    } finally {
      setMessagesLoading(false);
    }
  }, []);

  const sendMessage = useCallback((content: string) => {
    if (!activeConversation || !currentUser) return;
    const recipientEmail = currentUser.email === activeConversation.user1Email
      ? activeConversation.user2Email
      : activeConversation.user1Email;
    wsService.sendMessage(recipientEmail, content);
  }, [activeConversation, currentUser]);

  const loadMoreMessages = useCallback(async () => {
    if (!activeConversation || messages.length === 0) return;
    setMessagesLoading(true);
    try {
      const data = await chatApi.getMessages(activeConversation.id, 0, messages.length + 40);
      setMessages(data.messages.reverse());
    } catch (e) {
      console.error('[App] Failed to load more messages:', e);
    } finally {
      setMessagesLoading(false);
    }
  }, [activeConversation, messages.length]);

  const refreshConversations = useCallback(async () => {
    try {
      const convs = await chatApi.getConversations();
      setConversations(convs);
    } catch (e) {
      console.error('[App] Failed to refresh conversations:', e);
    }
  }, []);

  // ============================================================
  // WebSocket Setup
  // ============================================================

  useEffect(() => {
    if (!isLoggedIn || !currentUser || !authToken) return;

    wsService.onMessage((message) => {
      setMessages((prev) => [...prev, message]);
    });

    wsService.onTyping((indicator) => {
      setTypingUsers((prev) => {
        const next = new Set(prev);
        if (indicator.typing) {
          next.add(indicator.senderUsername);
        } else {
          next.delete(indicator.senderUsername);
        }
        return next;
      });
    });

    wsService.onConnectionChange((connected) => {
      setWsConnected(connected);
    });

    wsService.connect(currentUser.email, authToken).catch((e) => {
      console.error('[App] WebSocket connection failed:', e);
    });

    return () => {
      wsService.disconnect();
    };
  }, [isLoggedIn, currentUser, authToken]);

  // ============================================================
  // Load initial data
  // ============================================================

  useEffect(() => {
    if (!isLoggedIn) return;
    refreshPlatforms();
    refreshUsers();
    refreshConversations();
  }, [isLoggedIn, refreshPlatforms, refreshUsers, refreshConversations]);

  // ============================================================
  // Render
  // ============================================================

  const value: AppState & AppActions = {
    // Auth
    isLoggedIn,
    currentUser,
    authToken,
    login,
    logout,

    // DB Config
    storageType,
    dbConfig,
    dbConfigLoading,
    dbConfigError,
    setStorageType,
    saveDbConfig,
    testDbConnection,

    // Platform DBs
    platformConfigs,
    addPlatform,
    removePlatform,
    refreshPlatforms,

    // Users
    users,
    usersLoading,
    searchUsers,
    refreshUsers,

    // Chat
    conversations,
    activeConversation,
    messages,
    messagesLoading,
    typingUsers,
    wsConnected,
    selectConversation,
    sendMessage,
    loadMoreMessages,
    refreshConversations,
  };

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
};
