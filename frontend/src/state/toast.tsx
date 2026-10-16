import { CircleAlert, CircleCheck, Info, X } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { errorMessage } from '../api/http'

export type ToastTone = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  tone: ToastTone
  title: string
  description?: string
}

interface ToastApi {
  success: (title: string, description?: string) => void
  info: (title: string, description?: string) => void
  /** Accepts any thrown value; shows the backend `message` when available. */
  error: (errOrTitle: unknown, title?: string) => void
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

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const nextId = useRef(1)

  const dismiss = useCallback((id: number) => setItems((list) => list.filter((t) => t.id !== id)), [])

  const push = useCallback((tone: ToastTone, title: string, description?: string) => {
    const id = nextId.current++
    setItems((list) => {
      // Collapse identical consecutive toasts (e.g. a poller failing repeatedly).
      const last = list[list.length - 1]
      if (last && last.title === title && last.description === description && last.tone === tone) return list
      return [...list.slice(-4), { id, tone, title, description }]
    })
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      success: (title, description) => push('success', title, description),
      info: (title, description) => push('info', title, description),
      error: (errOrTitle, title) => {
        if (typeof errOrTitle === 'string' && title === undefined) push('error', errOrTitle)
        else if (title) push('error', title, errorMessage(errOrTitle))
        else push('error', errorMessage(errOrTitle))
      },
    }),
    [push],
  )

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

// eslint-disable-next-line react/only-export-components
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside ToastProvider')
  return ctx
}
