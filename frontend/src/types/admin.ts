import type { IsoDateTime, JsonObject } from './common'

export type AdminRole = 'OWNER' | 'ADMIN' | 'VIEWER'

export const ADMIN_ROLES: readonly AdminRole[] = ['OWNER', 'ADMIN', 'VIEWER']

export interface Admin {
  id: string
  email: string
  name: string
  role: AdminRole
  lastLoginAt: IsoDateTime | null
  createdAt: IsoDateTime
}

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  expiresAt: IsoDateTime
  admin: Admin
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface CreateAdminRequest {
  email: string
  name: string
  password: string
  role: AdminRole
}

/* ---------------------------------------------------------------- System ---- */

/** Connector slot slugs. Kept open-ended so new slots from the backend still render. */
export type ConnectorKind = 'message-store' | 'cache' | 'event-bus' | 'blob-store' | 'push' | (string & {})

export type ConfigSource = 'database' | 'bootstrap'

export interface ComponentStatus {
  kind: ConnectorKind
  displayName: string
  provider: string
  providerName: string
  source: ConfigSource
  healthy: boolean
  message: string | null
  latencyMs: number | null
  details: JsonObject | null
  lastError: string | null
}

export interface ThroughputPoint {
  minute: IsoDateTime
  messages: number
  deliveries: number
}

export interface SystemTotals {
  tenants: number
  activeTenants: number
  users: number
  conversations: number
  messages: number
  connections: number
  messagesToday: number
}

export interface SystemOverview {
  version: string
  nodeId: string
  startedAt: IsoDateTime
  uptimeSeconds: number
  components: ComponentStatus[]
  totals: SystemTotals
  /** Last 60 minutes, oldest first. */
  throughput: ThroughputPoint[]
}

/* ----------------------------------------------------------------- Audit ---- */

export type AuditActorType = 'ADMIN' | 'API_KEY' | 'SYSTEM'

export interface AuditEntry {
  id: string | number
  at: IsoDateTime
  actor: string | null
  actorType: AuditActorType
  action: string
  targetType: string | null
  targetId: string | null
  tenant: string | null
  details: unknown
  ip: string | null
}

export interface AuditQuery {
  tenant?: string
  limit?: number
  before?: string | number
}
