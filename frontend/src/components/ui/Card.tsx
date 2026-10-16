import type { ReactNode } from 'react'
import { cx } from '../../lib/util'

export interface CardProps {
  title?: ReactNode
  description?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  /** Remove body padding (tables, lists that run edge to edge). */
  flush?: boolean
  className?: string
  as?: 'section' | 'div' | 'article'
  headingLevel?: 2 | 3
  footer?: ReactNode
}

export function Card({ title, description, actions, children, flush, className, as = 'section', headingLevel = 2, footer }: CardProps) {
  const Tag = as
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  return (
    <Tag className={cx('card', className)}>
      {title || actions ? (
        <header className="card__header">
          <div className="card__heading">
            {title ? <Heading className="card__title">{title}</Heading> : null}
            {description ? <p className="card__desc">{description}</p> : null}
          </div>
          {actions ? <div className="card__actions">{actions}</div> : null}
        </header>
      ) : null}
      {children !== undefined ? <div className={cx('card__body', flush && 'card__body--flush')}>{children}</div> : null}
      {footer ? <footer className="card__footer">{footer}</footer> : null}
    </Tag>
  )
}

/** Definition list for key/value metadata. */
export function MetaList({ items, columns = 2, className }: { items: Array<{ label: ReactNode; value: ReactNode; key?: string }>; columns?: 1 | 2 | 3; className?: string }) {
  return (
    <dl className={cx('meta-list', `meta-list--${columns}`, className)}>
      {items.map((item, i) => (
        <div className="meta-list__item" key={item.key ?? i}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** A compact figure: number with label underneath. */
export function Stat({ label, value, hint, className }: { label: ReactNode; value: ReactNode; hint?: ReactNode; className?: string }) {
  return (
    <div className={cx('stat', className)}>
      <span className="stat__value">{value}</span>
      <span className="stat__label">{label}</span>
      {hint ? <span className="stat__hint">{hint}</span> : null}
    </div>
  )
}
