import type { TenantDetail } from '../../types'

export interface TenantTabProps {
  tenant: TenantDetail
  /** Replace the loaded tenant with a fresh copy returned by a mutation. */
  onTenantChange: (tenant: TenantDetail) => void
  reload: () => Promise<void>
  canWrite: boolean
}

export function featureEnabled(tenant: TenantDetail, id: string): boolean {
  return tenant.features.some((f) => f.id === id && f.enabled)
}

export function tenantPath(slug: string, tab?: string): string {
  return `/tenants/${encodeURIComponent(slug)}${tab ? `/${tab}` : ''}`
}

export function playgroundLink(slug: string, userIds: string[]): string {
  const params = new URLSearchParams({ tenant: slug })
  if (userIds.length) params.set('users', userIds.join(','))
  return `/playground?${params.toString()}`
}
