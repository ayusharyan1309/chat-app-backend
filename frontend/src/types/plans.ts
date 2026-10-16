export type FeatureStatus = 'CORE' | 'AVAILABLE' | 'PLANNED'

export interface Feature {
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
