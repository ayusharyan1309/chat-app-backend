import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { cx } from '../../lib/util'

export interface TabItem<T extends string> {
  id: T
  label: ReactNode
  icon?: ReactNode
  /** Trailing count/status. */
  badge?: ReactNode
  disabled?: boolean
}

function tabId(base: string, id: string): string {
  return `${base}-tab-${id}`
}
function panelId(base: string, id: string): string {
  return `${base}-panel-${id}`
}

interface TabsProps<T extends string> {
  items: ReadonlyArray<TabItem<T>>
  value: T
  onChange: (id: T) => void
  /** Prefix for tab/panel ids so panels can reference their tab. */
  idBase: string
  label: string
  className?: string
}

/** WAI-ARIA tablist with roving focus (arrows, Home/End) and automatic activation. */
export function Tabs<T extends string>({ items, value, onChange, idBase, label, className }: TabsProps<T>) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])

  const focusAt = (index: number) => {
    const enabled = items.map((item, i) => (item.disabled ? -1 : i)).filter((i) => i >= 0)
    if (enabled.length === 0) return
    const pos = ((index % items.length) + items.length) % items.length
    const target = enabled.includes(pos) ? pos : enabled[0]
    refs.current[target]?.focus()
    const item = items[target]
    if (item) onChange(item.id)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault()
        focusAt(index + 1)
        break
      case 'ArrowLeft':
        event.preventDefault()
        focusAt(index - 1)
        break
      case 'Home':
        event.preventDefault()
        focusAt(0)
        break
      case 'End':
        event.preventDefault()
        focusAt(items.length - 1)
        break
      default:
        break
    }
  }

  return (
    <div className={cx('tabs', className)} role="tablist" aria-label={label}>
      {items.map((item, index) => {
        const selected = item.id === value
        return (
          <button
            key={item.id}
            ref={(el) => {
              refs.current[index] = el
            }}
            type="button"
            role="tab"
            id={tabId(idBase, item.id)}
            aria-selected={selected}
            aria-controls={panelId(idBase, item.id)}
            tabIndex={selected ? 0 : -1}
            disabled={item.disabled}
            className={cx('tab', selected && 'is-selected')}
            onClick={() => onChange(item.id)}
            onKeyDown={(e) => onKeyDown(e, index)}
          >
            {item.icon}
            <span>{item.label}</span>
            {item.badge}
          </button>
        )
      })}
    </div>
  )
}

export function TabPanel({ idBase, id, children, className }: { idBase: string; id: string; children: ReactNode; className?: string }) {
  return (
    <div role="tabpanel" id={panelId(idBase, id)} aria-labelledby={tabId(idBase, id)} className={cx('tabpanel', className)} tabIndex={-1}>
      {children}
    </div>
  )
}
