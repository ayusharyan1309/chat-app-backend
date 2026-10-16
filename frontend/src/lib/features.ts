import type { FeatureId, FeatureSet, TenantDetail } from '../types'

/** Enabled feature ids from a public tenant config (`features: string[]`). */
export function featureSet(enabled: readonly string[]): FeatureSet {
  return new Set(enabled)
}

/** Whether a feature is effectively enabled for a tenant (plan, core or override). */
export function featureEnabled(tenant: Pick<TenantDetail, 'features'>, id: FeatureId): boolean {
  return tenant.features.some((f) => f.id === id && f.enabled)
}
