import { Database, Share2 } from 'lucide-react'
import { formatDate } from '../../lib/format'
import type { TenantStatus } from '../../types'
import { Badge, type Tone } from '../ui/Feedback'

const STATUS: Record<TenantStatus, { tone: Tone; label: string }> = {
  ACTIVE: { tone: 'ok', label: 'Active' },
  TRIAL: { tone: 'info', label: 'Trial' },
  SUSPENDED: { tone: 'warn', label: 'Suspended' },
  DELETED: { tone: 'danger', label: 'Deleted' },
}

export function TenantStatusBadge({ status, trialEndsAt }: { status: TenantStatus; trialEndsAt?: string | null }) {
  const meta = STATUS[status] ?? { tone: 'neutral' as Tone, label: status }
  return (
    <Badge tone={meta.tone} dot title={status === 'TRIAL' && trialEndsAt ? `Trial ends ${formatDate(trialEndsAt)}` : undefined}>
      {meta.label}
    </Badge>
  )
}

/** "Shared" platform store or the dedicated provider id. */
export function StoreBadge({ provider }: { provider: string | null }) {
  if (!provider) {
    return (
      <span className="store-badge">
        <Share2 size={13} aria-hidden />
        Shared
      </span>
    )
  }
  return (
    <span className="store-badge store-badge--dedicated" title="Dedicated message store">
      <Database size={13} aria-hidden />
      <span className="mono">{provider}</span>
    </span>
  )
}
