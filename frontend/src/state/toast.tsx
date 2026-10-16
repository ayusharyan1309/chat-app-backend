import { CircleAlert, CircleCheck, Info, X } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createToastApi, type ToastApi, type ToastTone } from './toastApi'

export type { ToastApi, ToastTone } from './toastApi'

interface ToastItem {
  id: number
  tone: ToastTone
  title: string
  description?: string
}

const ToastContext = createContext<ToastApi | null>(null)

const DURATION: Record<ToastTone, number> = { success: 4000, info: 5000, error: 8000 }

function ToastView({ item, onDismiss }: { item: ToastItem; onDismiss: (id: number) => void }) {
  const [paused, setPaused] = useState(false)
  const remaining = useRef(DURATION[item.tone])
  const startedAt = useRef(0)

  useEffect(() => {
    if (paused) return
    startedAt.current = Date.now()
    const timer = window.setTimeout(() => onDismiss(item.id), remaining.current)
    return () => {
      window.clearTimeout(timer)
      remaining.current -= Date.now() - startedAt.current
    }
  }, [paused, item.id, onDismiss])

  const Icon = item.tone === 'success' ? CircleCheck : item.tone === 'error' ? CircleAlert : Info
  return (
    <div
      className={`toast toast--${item.tone}`}
      role={item.tone === 'error' ? 'alert' : 'status'}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <Icon className="toast__icon" size={18} aria-hidden />
      <div className="toast__body">
        <p className="toast__title">{item.title}</p>
        {item.description ? <p className="toast__desc">{item.description}</p> : null}
      </div>
      <button type="button" className="toast__close" onClick={() => onDismiss(item.id)} aria-label="Dismiss notification">
        <X size={14} aria-hidden />
      </button>
    </div>
  )
}

/** Toast ids only need to be unique within the page. */
let nextToastId = 1

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: number) => setItems((list) => list.filter((t) => t.id !== id)), [])

  const push = useCallback((tone: ToastTone, title: string, description?: string) => {
    const id = nextToastId++
    setItems((list) => {
      // Collapse identical consecutive toasts (e.g. a poller failing repeatedly).
      const last = list[list.length - 1]
      if (last && last.title === title && last.description === description && last.tone === tone) return list
      return [...list.slice(-4), { id, tone, title, description }]
    })
  }, [])

  const api = useMemo(() => createToastApi(push), [push])

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" aria-live="polite" aria-relevant="additions">
        {items.map((item) => (
          <ToastView key={item.id} item={item} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

// oxlint-disable-next-line react/only-export-components
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside ToastProvider')
  return ctx
}
