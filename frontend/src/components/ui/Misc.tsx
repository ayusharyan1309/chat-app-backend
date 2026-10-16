import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { formatNumber, hueFor, initials } from '../../lib/format'
import { cx } from '../../lib/util'
import { resolveBackendUrl } from '../../lib/config'
import { IconButton } from './Button'

/* -------------------------------------------------------------- Segmented ---- */

export interface SegmentOption<T extends string> {
  value: T
  label: ReactNode
  disabled?: boolean
}

/** Radio group styled as a segmented control (arrow keys move the selection). */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
  size = 'md',
  className,
}: {
  value: T
  onChange: (value: T) => void
  options: ReadonlyArray<SegmentOption<T>>
  label: string
  size?: 'sm' | 'md'
  className?: string
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])
  const move = (event: KeyboardEvent, index: number) => {
    const delta = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (!delta) return
    event.preventDefault()
    for (let step = 1; step <= options.length; step += 1) {
      const next = (index + delta * step + options.length) % options.length
      const option = options[next]
      if (option && !option.disabled) {
        onChange(option.value)
        refs.current[next]?.focus()
        return
      }
    }
  }
  return (
    <div className={cx('segmented', size === 'sm' && 'segmented--sm', className)} role="radiogroup" aria-label={label}>
      {options.map((option, index) => {
        const checked = option.value === value
        return (
          <button
            key={option.value}
            ref={(el) => {
              refs.current[index] = el
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            tabIndex={checked ? 0 : -1}
            disabled={option.disabled}
            className={cx('segmented__option', checked && 'is-checked')}
            onClick={() => onChange(option.value)}
            onKeyDown={(e) => move(e, index)}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

/* ----------------------------------------------------------------- Avatar ---- */

export function Avatar({
  name,
  id,
  src,
  size = 32,
  online,
  className,
}: {
  name: string | null | undefined
  id: string
  src?: string | null
  size?: number
  /** undefined = unknown (no dot). */
  online?: boolean | null
  className?: string
}) {
  const [failed, setFailed] = useState(false)
  const hue = hueFor(id)
  const showImage = src && !failed
  return (
    <span
      className={cx('avatar', className)}
      style={{ width: size, height: size, fontSize: Math.max(10, Math.round(size * 0.38)), ['--avatar-hue' as string]: hue }}
      aria-hidden
    >
      {showImage ? <img src={resolveBackendUrl(src)} alt="" onError={() => setFailed(true)} /> : <span className="avatar__initials">{initials(name || id)}</span>}
      {online !== undefined && online !== null ? <span className={cx('avatar__presence', online && 'is-online')} /> : null}
    </span>
  )
}

/* ------------------------------------------------------------------- Menu ---- */

export interface MenuItem {
  key: string
  label: ReactNode
  icon?: ReactNode
  onSelect: () => void
  danger?: boolean
  disabled?: boolean
}

/** Small dropdown menu: click or keyboard to open, arrows to move, Escape to close. */
export function Menu({
  trigger,
  items,
  align = 'end',
  label,
  header,
}: {
  trigger: (props: { onClick: () => void; 'aria-expanded': boolean; 'aria-haspopup': 'menu'; 'aria-controls': string; id: string }) => ReactNode
  items: MenuItem[]
  align?: 'start' | 'end'
  label: string
  header?: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const id = useId()

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    const first = listRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')
    first?.focus()
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const buttons = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpen(false)
      wrapRef.current?.querySelector<HTMLButtonElement>(`[aria-controls="${id}"]`)?.focus()
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      buttons[(index + 1) % buttons.length]?.focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      buttons[(index - 1 + buttons.length) % buttons.length]?.focus()
    } else if (event.key === 'Tab') {
      setOpen(false)
    }
  }

  return (
    <div className="menu" ref={wrapRef}>
      {trigger({ onClick: () => setOpen((o) => !o), 'aria-expanded': open, 'aria-haspopup': 'menu', 'aria-controls': id, id: `${id}-trigger` })}
      {open ? (
        <div className={cx('menu__list', `menu__list--${align}`)} role="menu" id={id} aria-label={label} ref={listRef} onKeyDown={onKeyDown}>
          {header ? <div className="menu__header">{header}</div> : null}
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              className={cx('menu__item', item.danger && 'is-danger')}
              onClick={() => {
                setOpen(false)
                item.onSelect()
              }}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}

/* ------------------------------------------------------------- Pagination ---- */

export function Pagination({ offset, limit, total, onChange, itemLabel = 'items' }: { offset: number; limit: number; total: number; onChange: (offset: number) => void; itemLabel?: string }) {
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + limit, total)
  return (
    <nav className="pagination" aria-label="Pagination">
      <span className="pagination__summary">
        {total === 0 ? `No ${itemLabel}` : `${formatNumber(from)}–${formatNumber(to)} of ${formatNumber(total)} ${itemLabel}`}
      </span>
      <div className="pagination__buttons">
        <IconButton label="Previous page" icon={<ChevronLeft size={16} aria-hidden />} variant="secondary" size="sm" disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - limit))} />
        <IconButton label="Next page" icon={<ChevronRight size={16} aria-hidden />} variant="secondary" size="sm" disabled={to >= total} onClick={() => onChange(offset + limit)} />
      </div>
    </nav>
  )
}

/* ------------------------------------------------------------------- Kbd ---- */

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>
}
