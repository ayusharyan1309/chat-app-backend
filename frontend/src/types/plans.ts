export type FeatureStatus = 'CORE' | 'AVAILABLE' | 'PLANNED'

/** Every feature id in the backend catalogue (`GET /api/admin/features`, `tenant.Feature`). */
export const FEATURE_IDS = [
  // Core
  'direct-messaging',
  'message-replies',
  'user-blocking',
  // Available
  'group-chat',
  'read-receipts',
  'typing-indicators',
  'presence',
  'message-reactions',
  'message-editing',
  'message-deletion',
  'media-sharing',
  'file-sharing',
  'push-notifications',
  'webhooks',
  'external-auth',
  'analytics',
  'custom-branding',
  // Planned
  'voice-calls',
  'video-calls',
  'channels',
  'message-search',
  'ai-moderation',
  'ai-translation',
  'sso',
] as const

export type FeatureId = (typeof FEATURE_IDS)[number]

const KNOWN_FEATURES: ReadonlySet<string> = new Set(FEATURE_IDS)

export function isFeatureId(value: string): value is FeatureId {
  return KNOWN_FEATURES.has(value)
}

/**
 * Read-only view of a set of enabled feature ids. A `Set<string>` built from a config's `features`
 * array satisfies it, while `has()` only accepts known ids so a typo fails to compile.
 */
export interface FeatureSet {
  has(id: FeatureId): boolean
}

export interface Feature {
  /** A `FeatureId` for every feature this console knows; newer backends may add more. */
  id: string
  name: string
  description: string | null
  category: string | null
  status: FeatureStatus
}

/** `null` means unlimited. */
export interface PlanLimits {
  maxUsers: number | null
  maxMessagesPerDay: number | null
  maxConnections: number | null
  maxStorageMb: number | null
  maxGroupSize: number | null
  messagesPerSecond: number | null
}

export type PlanLimitKey = keyof PlanLimits

export const PLAN_LIMIT_FIELDS: ReadonlyArray<{ key: PlanLimitKey; label: string; unit?: string; hint: string }> = [
  { key: 'maxUsers', label: 'Users', hint: 'Registered end users' },
  { key: 'maxMessagesPerDay', label: 'Messages per day', hint: 'Across all conversations, UTC day' },
  { key: 'maxConnections', label: 'Live connections', hint: 'Concurrent sockets' },
  { key: 'maxStorageMb', label: 'Storage', unit: 'MB', hint: 'Media and files' },
  { key: 'maxGroupSize', label: 'Group size', hint: 'Members per group' },
  { key: 'messagesPerSecond', label: 'Send rate', unit: 'msg/s', hint: 'Per user' },
]

export const EMPTY_LIMITS: PlanLimits = {
  maxUsers: null,
  maxMessagesPerDay: null,
  maxConnections: null,
  maxStorageMb: null,
  maxGroupSize: null,
  messagesPerSecond: null,
}

export interface Plan {
  code: string
  name: string
  description: string | null
  priceMonthly: number | null
  priceYearly: number | null
  currency: string
  limits: PlanLimits
  features: string[]
  isDefault: boolean
  active: boolean
  sortOrder: number
  tenantCount: number
}

export type PlanInput = Omit<Plan, 'tenantCount'>
