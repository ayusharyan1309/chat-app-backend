import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { formatLatency } from '../../lib/format'
import { cx } from '../../lib/util'
import type { ComponentStatus } from '../../types'
import { Lamp, Skeleton } from '../ui/Feedback'
import { sourceLabel } from './labels'

/** A socket with the active provider "plugged in". */
export function Socket({
  name,
  title,
  source,
  down,
  empty,
  className,
}: {
  name: ReactNode
  title?: string
  source?: ReactNode
  down?: boolean
  empty?: boolean
  className?: string
}) {
  return (
    <span className={cx('socket', down && 'is-down', empty && 'is-empty', className)} title={title}>
      <span className="socket__plug">
        <span className="socket__prongs" aria-hidden />
        <span className="socket__name">{name}</span>
      </span>
      {source ? <span className="socket__source">{source}</span> : null}
    </span>
  )
}

function PatchRow({ component }: { component: ComponentStatus }) {
  const errorText = !component.healthy ? (component.lastError ?? component.message) : null
  return (
    <Link to={`/infrastructure#${component.kind}`} className="patch-row" aria-label={`${component.displayName}: ${component.providerName}, ${component.healthy ? 'healthy' : 'down'}`}>
      <span className="patch-row__slot">
        <span className="patch-row__slot-name">{component.displayName}</span>
        <span className="patch-row__slot-kind">{component.kind}</span>
      </span>
      <Socket name={component.providerName || component.provider} title={component.provider} source={sourceLabel(component.source)} down={!component.healthy} />
      <span className="patch-row__signal">
        <Lamp state={component.healthy ? 'ok' : 'down'} label={component.healthy ? 'Healthy' : 'Down'} />
        <span className="patch-row__latency">{formatLatency(component.latencyMs)}</span>
      </span>
      <span className={cx('patch-row__message', errorText ? 'is-error' : undefined)} title={errorText ?? component.message ?? undefined}>
        {errorText ?? component.message ?? ''}
      </span>
    </Link>
  )
}

/** Compact patch bay: one row per infrastructure slot. */
export function PatchBay({ components, loading }: { components: ComponentStatus[] | undefined; loading?: boolean }) {
  if (!components && loading) {
    return (
      <div className="patch-bay" aria-busy="true">
        {Array.from({ length: 5 }, (_, i) => (
          <div className="patch-row" key={i}>
            <Skeleton width="70%" />
            <Skeleton width={150} height={26} />
            <Skeleton width={90} />
            <Skeleton width="60%" />
          </div>
        ))}
      </div>
    )
  }
  return (
    <div className="patch-bay">
      {(components ?? []).map((component) => (
        <PatchRow key={component.kind} component={component} />
      ))}
    </div>
  )
}
