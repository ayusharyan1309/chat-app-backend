import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { formatNumber, hueFor, initials } from '../../lib/format'
import { safeMediaUrl } from '../../lib/mediaUrl'
import { cx } from '../../lib/util'
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
            // ARIA radio group with roving tabindex (WAI-ARIA APG); native radios cannot be styled as segments.
            // oxlint-disable-next-line jsx-a11y/prefer-tag-over-role
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
  // Tenant-supplied: only http(s) or relative URLs are loaded; anything else shows initials.
  const url = safeMediaUrl(src)
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const hue = hueFor(id)
  const showImage = url !== null && failedUrl !== url
  return (
    <span
      className={cx('avatar', className)}
      style={{ width: size, height: size, fontSize: Math.max(10, Math.round(size * 0.38)), ['--avatar-hue' as string]: hue }}
      aria-hidden
    >
      {showImage ? <img src={url} alt="" onError={() => setFailedUrl(url)} /> : <span className="avatar__initials">{initials(name || id)}</span>}
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
  /** Accessible name when the label alone is not descriptive (e.g. a bare emoji). */
  ariaLabel?: string
}

export interface MenuTriggerProps {
  onClick: () => void
  'aria-expanded': boolean
  'aria-haspopup': 'menu'
  'aria-controls': string
  id: string
}

/**
 * Small dropdown menu: click or keyboard to open, arrow keys to move, Escape (focus returns to the
 * trigger), Tab or a click outside to close.
 */
export function Menu({
  trigger,
  items,
  align = 'end',
  label,
  header,
  orientation = 'vertical',
  className,
  listClassName,
  itemClassName,
  onOpenChange,
}: {
  trigger: (props: MenuTriggerProps) => ReactNode
  items: MenuItem[]
  align?: 'start' | 'end'
  label: string
  header?: ReactNode
  /** Horizontal menus (e.g. a row of reactions) move with Left/Right as well. */
  orientation?: 'vertical' | 'horizontal'
  className?: string
  /** Replaces the default list styling. */
  listClassName?: string
  /** Replaces the default item styling. */
  itemClassName?: string
  onOpenChange?: (open: boolean) => void
}) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const id = useId()

  useEffect(() => {
    onOpenChange?.(open)
  }, [open, onOpenChange])

  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (wrapRef.current && e.target instanceof Node && !wrapRef.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    const first = listRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')
    first?.focus()
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const buttons = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
    const index = buttons.findIndex((b) => b === document.activeElement)
    const next = orientation === 'horizontal' ? ['ArrowDown', 'ArrowRight'] : ['ArrowDown']
    const prev = orientation === 'horizontal' ? ['ArrowUp', 'ArrowLeft'] : ['ArrowUp']
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      setOpen(false)
      wrapRef.current?.querySelector<HTMLButtonElement>(`[aria-controls="${id}"]`)?.focus()
    } else if (next.includes(event.key)) {
      event.preventDefault()
      buttons[(index + 1) % buttons.length]?.focus()
    } else if (prev.includes(event.key)) {
      event.preventDefault()
      buttons[(index - 1 + buttons.length) % buttons.length]?.focus()
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      buttons[event.key === 'Home' ? 0 : buttons.length - 1]?.focus()
    } else if (event.key === 'Tab') {
      setOpen(false)
    }
  }

  return (
    <div className={cx('menu', className)} ref={wrapRef}>
      {trigger({ onClick: () => setOpen(!open), 'aria-expanded': open, 'aria-haspopup': 'menu', 'aria-controls': id, id: `${id}-trigger` })}
      {open ? (
        <div
          className={listClassName ?? cx('menu__list', `menu__list--${align}`)}
          role="menu"
          id={id}
          aria-label={label}
          aria-orientation={orientation}
          tabIndex={-1}
          ref={listRef}
          onKeyDown={onKeyDown}
        >
          {header ? <div className="menu__header">{header}</div> : null}
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              disabled={item.disabled}
              aria-label={item.ariaLabel}
              className={itemClassName ?? cx('menu__item', item.danger && 'is-danger')}
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
