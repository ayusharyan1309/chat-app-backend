import { CircleAlert, CircleCheck, Info, TriangleAlert } from 'lucide-react'
import type { CSSProperties, ReactNode } from 'react'
import { cx } from '../../lib/util'

/* ----------------------------------------------------------------- Badge ---- */

export type Tone = 'neutral' | 'accent' | 'ok' | 'warn' | 'danger' | 'info'

export function Badge({
  tone = 'neutral',
  children,
  dot,
  mono,
  className,
  title,
}: {
  tone?: Tone
  children: ReactNode
  dot?: boolean
  mono?: boolean
  className?: string
  title?: string
}) {
  return (
    <span className={cx('badge', `badge--${tone}`, mono && 'badge--mono', className)} title={title}>
      {dot ? <span className="badge__dot" aria-hidden /> : null}
      {children}
    </span>
  )
}

/** Small neutral chip for lists of values (features, compatible products). */
export function Chip({ children, className, title }: { children: ReactNode; className?: string; title?: string }) {
  return (
    <span className={cx('chip', className)} title={title}>
      {children}
    </span>
  )
}

/* ------------------------------------------------------------------ Lamp ---- */

export type LampState = 'ok' | 'down' | 'warn' | 'idle'

/** Signal lamp + label. Status is never conveyed by colour alone. */
export function Lamp({ state, label, className }: { state: LampState; label?: ReactNode; className?: string }) {
  return (
    <span className={cx('lamp', `lamp--${state}`, className)}>
      <span className="lamp__light" aria-hidden />
      {label ? <span className="lamp__label">{label}</span> : null}
    </span>
  )
}

/* ---------------------------------------------------------------- Callout ---- */

const CALLOUT_ICON = { info: Info, ok: CircleCheck, warn: TriangleAlert, danger: CircleAlert } as const

export function Callout({
  tone = 'info',
  title,
  children,
  actions,
  className,
}: {
  tone?: keyof typeof CALLOUT_ICON
  title?: ReactNode
  children?: ReactNode
  actions?: ReactNode
  className?: string
}) {
  const Icon = CALLOUT_ICON[tone]
  return (
    <div className={cx('callout', `callout--${tone}`, className)} role={tone === 'danger' ? 'alert' : undefined}>
      <Icon className="callout__icon" size={16} aria-hidden />
      <div className="callout__body">
        {title ? <p className="callout__title">{title}</p> : null}
        {children ? <div className="callout__text">{children}</div> : null}
      </div>
      {actions ? <div className="callout__actions">{actions}</div> : null}
    </div>
  )
}

/* ---------------------------------------------------------------- Spinner ---- */

export function Spinner({ size = 14, label }: { size?: number; label?: string }) {
  return (
    <span className="spinner" style={{ width: size, height: size }} role={label ? 'status' : undefined} aria-label={label}>
      <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden>
        <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeOpacity="0.2" strokeWidth="3" />
        <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      </svg>
    </span>
  )
}

/* --------------------------------------------------------------- Skeleton ---- */

export function Skeleton({
  width = '100%',
  height = 12,
  radius,
  className,
  style,
}: {
  width?: number | string
  height?: number | string
  radius?: number
  className?: string
  style?: CSSProperties
}) {
  return <span className={cx('skeleton', className)} style={{ width, height, borderRadius: radius, ...style }} aria-hidden />
}

export function SkeletonLines({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton-lines" aria-busy="true" aria-label="Loading">
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} width={`${90 - ((i * 17) % 40)}%`} />
      ))}
    </div>
  )
}

/* ------------------------------------------------------------- EmptyState ---- */

export function EmptyState({
  icon,
  title,
  description,
  action,
  compact,
  className,
}: {
  icon?: ReactNode
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  compact?: boolean
  className?: string
}) {
  return (
    <div className={cx('empty', compact && 'empty--compact', className)}>
      {icon ? <div className="empty__icon">{icon}</div> : null}
      <p className="empty__title">{title}</p>
      {description ? <p className="empty__desc">{description}</p> : null}
      {action ? <div className="empty__action">{action}</div> : null}
    </div>
  )
}

/** Inline error block for a failed load, with retry. */
export function LoadError({ error, onRetry, title = 'Could not load this data' }: { error: { message: string } | null; onRetry?: () => void; title?: string }) {
  if (!error) return null
  return (
    <Callout
      tone="danger"
      title={title}
      actions={
        onRetry ? (
          <button type="button" className="btn btn--secondary btn--sm" onClick={onRetry}>
            <span className="btn__label">Retry</span>
          </button>
        ) : undefined
      }
    >
      {error.message}
    </Callout>
  )
}
