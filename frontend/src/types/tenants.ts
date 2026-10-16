import type { IsoDateTime, JsonObject } from './common'
import type { FeatureStatus, PlanLimits } from './plans'
import type { User } from './client'

export type TenantStatus = 'ACTIVE' | 'TRIAL' | 'SUSPENDED' | 'DELETED'

export const TENANT_STATUSES: readonly TenantStatus[] = ['ACTIVE', 'TRIAL', 'SUSPENDED', 'DELETED']

export interface TenantSummary {
  slug: string
  name: string
  contactEmail: string | null
  domain: string | null
  status: TenantStatus
  planCode: string | null
  planName: string | null
  /** `null` = shared platform store, otherwise the dedicated provider id. */
  storeProvider: string | null
  messagesToday: number
  connections: number
  createdAt: IsoDateTime
  trialEndsAt: IsoDateTime | null
}

export interface TenantBranding {
  displayName: string | null
  logoUrl: string | null
  primaryColor: string | null
  accentColor: string | null
}

export interface TenantSettings {
  maxMessageLength: number
  editWindowMinutes: number
  maxFileSizeMb: number
  allowUserSearch: boolean
}

export type TenantFeatureSource = 'core' | 'plan' | 'override' | 'planned'

export interface TenantFeature {
  id: string
  name: string
  category: string | null
  status: FeatureStatus
  enabled: boolean
  source: TenantFeatureSource
  description?: string | null
}

export interface TenantStats {
  users: number
  conversations: number
  messages: number
  messagesToday: number
  connections: number
  storageBytes: number
}

export interface TenantDetail extends TenantSummary {
  branding: TenantBranding
  settings: TenantSettings
  /** Effective limits (plan merged with overrides). `null` = unlimited. */
  limits: PlanLimits
  /** `null` per field = inherit the plan value. */
  limitOverrides: PlanLimits
  features: TenantFeature[]
  stats: TenantStats
  updatedAt: IsoDateTime | null
}

export interface CreateTenantRequest {
  name: string
  slug?: string
  contactEmail: string
  domain?: string
  planCode?: string
  trialDays?: number
}

export interface CreateTenantResponse {
  tenant: TenantDetail
  apiKey: ApiKeyCreated
}

export interface UpdateTenantRequest {
  name?: string
  contactEmail?: string | null
  domain?: string | null
  planCode?: string
  branding?: TenantBranding
  settings?: TenantSettings
  limitOverrides?: PlanLimits
}

export interface TenantQuery {
  q?: string
  status?: TenantStatus | ''
}

export interface UsagePoint {
  /** Calendar day, "2026-10-16". */
  day: string
  messages: number
  mediaBytes: number
  apiCalls: number
}

/* ----------------------------------------------------------------- Auth ---- */

export type ExternalAuthType = 'none' | 'firebase' | 'jwt'

export interface ExternalAuthConfig {
  type: ExternalAuthType
  firebaseProjectId?: string | null
  jwtIssuer?: string | null
  jwtAudience?: string | null
  jwksUrl?: string | null
  jwtSecret?: string | null
  userIdClaim?: string | null
  nameClaim?: string | null
  emailClaim?: string | null
}

export interface TenantAuthConfig {
  devLoginEnabled: boolean
  tokenTtlMinutes: number
  external: ExternalAuthConfig
}

/* ------------------------------------------------------------- API keys ---- */

export interface ApiKey {
  id: string
  name: string
  prefix: string
  createdAt: IsoDateTime
  createdBy: string | null
  lastUsedAt: IsoDateTime | null
  revokedAt: IsoDateTime | null
}

export interface ApiKeyCreated extends ApiKey {
  /** Full key, shown exactly once. */
  key: string
}

/* ------------------------------------------------------------- Webhooks ---- */

export type WebhookEvent =
  | 'message.created'
  | 'message.updated'
  | 'message.deleted'
  | 'conversation.created'
  | 'conversation.member_added'
  | 'conversation.member_removed'

export const WEBHOOK_EVENTS: ReadonlyArray<{ id: WebhookEvent; label: string }> = [
  { id: 'message.created', label: 'Message created' },
  { id: 'message.updated', label: 'Message edited or reacted' },
  { id: 'message.deleted', label: 'Message deleted' },
  { id: 'conversation.created', label: 'Conversation created' },
  { id: 'conversation.member_added', label: 'Member added' },
  { id: 'conversation.member_removed', label: 'Member removed' },
]

export interface Webhook {
  id: string
  url: string
  events: string[]
  description: string | null
  active: boolean
  /** "********" except in the create response, where the real secret is shown once. */
  secret: string
  createdAt: IsoDateTime
  updatedAt: IsoDateTime | null
}

export interface WebhookInput {
  url: string
  events: string[]
  description: string | null
  active: boolean
}

export type DeliveryStatus = 'SUCCESS' | 'FAILED' | 'PENDING'

export interface WebhookDelivery {
  id: string
  eventId: string
  eventType: string
  status: DeliveryStatus
  attempts: number
  responseCode: number | null
  error: string | null
  durationMs: number | null
  createdAt: IsoDateTime
}

/* ---------------------------------------------------------------- Users ---- */

export interface UpsertUserRequest {
  id: string
  displayName: string
  email?: string
  phone?: string
  avatarUrl?: string
}

export interface UserQuery {
  q?: string
  offset?: number
  limit?: number
}

export interface UserToken {
  token: string
  expiresAt: IsoDateTime
  user: User
}

export type { JsonObject }
