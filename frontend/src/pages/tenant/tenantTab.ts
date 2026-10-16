import type { TenantDetail } from '../../types'

/** Props every tab of the tenant detail page receives. */
export interface TenantTabProps {
  tenant: TenantDetail
  /** Replace the loaded tenant with a fresh copy returned by a mutation. */
  onTenantChange: (tenant: TenantDetail) => void
  reload: () => Promise<void>
  canWrite: boolean
}

/** Route of the tenant detail page, optionally on a specific tab. */
export function tenantPath(slug: string, tab?: string): string {
  return `/tenants/${encodeURIComponent(slug)}${tab ? `/${tab}` : ''}`
}

/** Playground URL with panes signed in as the given users (duplicates are dropped). */
export function playgroundLink(slug: string, userIds: string[]): string {
  const params = new URLSearchParams({ tenant: slug })
  const unique = [...new Set(userIds.filter(Boolean))]
  if (unique.length) params.set('users', unique.join(','))
  return `/playground?${params.toString()}`
}
