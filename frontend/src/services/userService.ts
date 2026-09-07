import type { PlatformDbConfig, User } from '../types';

// ============================================================
// Supabase Config (Hardcoded defaults for the primary user DB)
// ============================================================

const SUPABASE_DEFAULTS = {
  url: import.meta.env.VITE_SUPABASE_URL || 'https://your-project.supabase.co',
  apiKey: import.meta.env.VITE_SUPABASE_ANON_KEY || 'your-anon-key',
  schema: 'public',
};

/**
 * Primary Supabase client for user data fetching.
 * This is hardcoded but can be overridden at runtime.
 */
class SupabaseClient {
  private url: string;
  private apiKey: string;

  constructor(url?: string, apiKey?: string) {
    this.url = url || SUPABASE_DEFAULTS.url;
    this.apiKey = apiKey || SUPABASE_DEFAULTS.apiKey;
  }

  /**
   * Query the Supabase REST API (PostgREST).
   */
  async query<T>(table: string, params?: {
    select?: string;
    filters?: Record<string, string>;
    order?: { column: string; ascending?: boolean };
    limit?: number;
    offset?: number;
  }): Promise<T[]> {
    const url = new URL(`${this.url}/rest/v1/${table}`);
    
    if (params?.select) {
      url.searchParams.set('select', params.select);
    }
    if (params?.filters) {
      Object.entries(params.filters).forEach(([key, value]) => {
        url.searchParams.set(key, value);
      });
    }
    if (params?.order) {
      const direction = params.order.ascending !== false ? 'asc' : 'desc';
      url.searchParams.set('order', `${params.order.column}.${direction}`);
    }
    if (params?.limit) {
      url.searchParams.set('limit', String(params.limit));
    }
    if (params?.offset) {
      url.searchParams.set('offset', String(params.offset));
    }

    const response = await fetch(url.toString(), {
      headers: {
        'apikey': this.apiKey,
        'Authorization': `Bearer ${this.apiKey}`,
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Prefer': 'return=representation',
      },
    });

    if (!response.ok) {
      throw new Error(`Supabase query failed: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }

  /**
   * Fetch users from Supabase.
   */
  async getUsers(query?: string): Promise<User[]> {
    const params: Record<string, string> = {};
    if (query) {
      params.or = `(email.ilike.%${query}%,full_name.ilike.%${query}%)`;
    }

    const rows = await this.query('users', {
      select: 'id,email,full_name,avatar_url',
      filters: params,
      order: { column: 'created_at', ascending: false },
      limit: 100,
    });

    return rows.map((row) => {
      const r = row as Record<string, unknown>;
      return {
        id: String(r.id),
        email: r.email as string,
        fullName: r.full_name as string,
        avatarUrl: r.avatar_url as string | undefined,
        source: 'supabase',
      };
    });
  }
}

// ============================================================
// Platform Database Service
// ============================================================

interface PlatformAdapter {
  fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]>;
  testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }>;
}

/**
 * MySQL/PostgreSQL adapter — fetches users via the backend API.
 */
class SqlAdapter implements PlatformAdapter {
  async fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/users${query ? `?q=${query}` : ''}`,
      {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    if (!response.ok) throw new Error('Failed to fetch users from SQL platform');
    return response.json();
  }

  async testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/test`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    return response.json();
  }
}

/**
 * MongoDB adapter — fetches users via the backend API.
 */
class MongoAdapter implements PlatformAdapter {
  async fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/users${query ? `?q=${query}` : ''}`,
      {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    if (!response.ok) throw new Error('Failed to fetch users from MongoDB platform');
    return response.json();
  }

  async testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/test`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    return response.json();
  }
}

/**
 * Firebase adapter — fetches users via the backend API.
 */
class FirebaseAdapter implements PlatformAdapter {
  async fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/users${query ? `?q=${query}` : ''}`,
      {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    if (!response.ok) throw new Error('Failed to fetch users from Firebase platform');
    return response.json();
  }

  async testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }> {
    const response = await fetch(
      `${import.meta.env.VITE_API_BASE || 'http://localhost:8080'}/api/platforms/${config.id}/test`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('auth_token') || ''}`,
          'Content-Type': 'application/json',
        },
      }
    );
    return response.json();
  }
}

/**
 * Supabase adapter — fetches users directly from Supabase REST API.
 */
class SupabasePlatformAdapter implements PlatformAdapter {
  private getClient(config: PlatformDbConfig): SupabaseClient {
    if (!config.supabase) throw new Error('Supabase config missing');
    return new SupabaseClient(config.supabase.url, config.supabase.apiKey);
  }

  async fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]> {
    const client = this.getClient(config);
    const users = await client.getUsers(query);
    return users.map((u) => ({ ...u, source: config.id }));
  }

  async testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }> {
    try {
      const client = this.getClient(config);
      await client.query('users', { limit: 1 });
      return { healthy: true, message: 'Connection successful' };
    } catch (e) {
      return { healthy: false, message: `Connection failed: ${e}` };
    }
  }
}

/**
 * Custom API adapter — fetches users via a custom REST API endpoint.
 */
class CustomApiAdapter implements PlatformAdapter {
  async fetchUsers(config: PlatformDbConfig, query?: string): Promise<User[]> {
    if (!config.custom) throw new Error('Custom config missing');
    const url = new URL(`${config.custom.apiUrl}/users`);
    if (query) url.searchParams.set('q', query);

    const response = await fetch(url.toString(), {
      headers: {
        'Authorization': `Bearer ${config.custom.apiKey}`,
        'Content-Type': 'application/json',
        ...config.custom.headers,
      },
    });

    if (!response.ok) throw new Error('Failed to fetch users from custom platform');
    return response.json();
  }

  async testConnection(config: PlatformDbConfig): Promise<{ healthy: boolean; message: string }> {
    if (!config.custom) throw new Error('Custom config missing');
    try {
      const response = await fetch(`${config.custom.apiUrl}/health`, {
        headers: {
          'Authorization': `Bearer ${config.custom.apiKey}`,
          ...config.custom.headers,
        },
      });
      return { healthy: response.ok, message: response.statusText };
    } catch (e) {
      return { healthy: false, message: `Connection failed: ${e}` };
    }
  }
}

// ============================================================
// User Service (Main Entry Point)
// ============================================================

/**
 * Unified user service that handles:
 * 1. Primary Supabase user DB (hardcoded)
 * 2. External platform databases (MySQL, PostgreSQL, MongoDB, Firebase, Supabase, Custom)
 */
class UserService {
  private supabaseClient: SupabaseClient;
  private platformConfigs: Map<string, PlatformDbConfig> = new Map();
  private adapters: Map<string, PlatformAdapter> = new Map();

  constructor() {
    this.supabaseClient = new SupabaseClient();
    this.adapters.set('sql', new SqlAdapter());
    this.adapters.set('mongodb', new MongoAdapter());
    this.adapters.set('firebase', new FirebaseAdapter());
    this.adapters.set('supabase', new SupabasePlatformAdapter());
    this.adapters.set('custom', new CustomApiAdapter());
  }

  /**
   * Initialize with platform configurations.
   */
  setPlatformConfigs(configs: PlatformDbConfig[]): void {
    this.platformConfigs.clear();
    configs.forEach((c) => this.platformConfigs.set(c.id, c));
  }

  /**
   * Add or update a platform configuration.
   */
  addPlatform(config: PlatformDbConfig): void {
    this.platformConfigs.set(config.id, config);
  }

  /**
   * Remove a platform configuration.
   */
  removePlatform(platformId: string): void {
    this.platformConfigs.delete(platformId);
  }

  /**
   * Get the adapter for a platform type.
   */
  private getAdapter(type: string): PlatformAdapter {
    const adapter = this.adapters.get(type);
    if (!adapter) throw new Error(`No adapter for platform type: ${type}`);
    return adapter;
  }

  /**
   * Fetch users from the primary Supabase DB.
   */
  async getPrimaryUsers(query?: string): Promise<User[]> {
    return this.supabaseClient.getUsers(query);
  }

  /**
   * Fetch users from a specific platform.
   */
  async getUsersFromPlatform(platformId: string, query?: string): Promise<User[]> {
    const config = this.platformConfigs.get(platformId);
    if (!config) throw new Error(`Platform not found: ${platformId}`);
    if (!config.isActive) return [];

    const adapter = this.getAdapter(config.type);
    return adapter.fetchUsers(config, query);
  }

  /**
   * Fetch users from ALL configured platforms (primary + external).
   */
  async getAllUsers(query?: string): Promise<User[]> {
    const allUsers: User[] = [];

    // Fetch from primary Supabase
    try {
      const primaryUsers = await this.getPrimaryUsers(query);
      allUsers.push(...primaryUsers);
    } catch (e) {
      console.error('[UserService] Failed to fetch primary users:', e);
    }

    // Fetch from each active platform
    for (const [id, config] of this.platformConfigs) {
      if (!config.isActive) continue;
      try {
        const adapter = this.getAdapter(config.type);
        const users = await adapter.fetchUsers(config, query);
        allUsers.push(...users);
      } catch (e) {
        console.error(`[UserService] Failed to fetch users from platform ${id}:`, e);
      }
    }

    // Deduplicate by email (primary Supabase users take precedence)
    const seen = new Set<string>();
    return allUsers.filter((user) => {
      if (seen.has(user.email)) return false;
      seen.add(user.email);
      return true;
    });
  }

  /**
   * Test connection to the primary Supabase DB.
   */
  async testPrimaryConnection(): Promise<{ healthy: boolean; message: string }> {
    try {
      await this.supabaseClient.query('users', { limit: 1 });
      return { healthy: true, message: 'Primary Supabase connection successful' };
    } catch (e) {
      return { healthy: false, message: `Primary connection failed: ${e}` };
    }
  }

  /**
   * Test connection to a specific platform.
   */
  async testPlatformConnection(platformId: string): Promise<{ healthy: boolean; message: string }> {
    const config = this.platformConfigs.get(platformId);
    if (!config) throw new Error(`Platform not found: ${platformId}`);
    const adapter = this.getAdapter(config.type);
    return adapter.testConnection(config);
  }
}

// Singleton
export const userService = new UserService();
export default userService;
