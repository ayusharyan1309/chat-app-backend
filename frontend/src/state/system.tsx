import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { systemApi } from '../api/admin'
import type { SystemOverview } from '../types'

interface SystemContextValue {
  overview: SystemOverview | null
  reachable: boolean | null
  /** Pages that poll the overview themselves publish fresh data here for the top bar. */
  publish: (overview: SystemOverview) => void
  markUnreachable: () => void
}

const SystemContext = createContext<SystemContextValue | null>(null)

const POLL_MS = 60_000

export function SystemProvider({ children }: { children: ReactNode }) {
  const [overview, setOverview] = useState<SystemOverview | null>(null)
  const [reachable, setReachable] = useState<boolean | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await systemApi.overview()
        if (!cancelled) {
          setOverview(data)
          setReachable(true)
        }
      } catch {
        if (!cancelled) setReachable(false)
      }
    }
    void load()
    const timer = window.setInterval(() => void load(), POLL_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  const publish = useCallback((data: SystemOverview) => {
    setOverview(data)
    setReachable(true)
  }, [])
  const markUnreachable = useCallback(() => setReachable(false), [])

  const value = useMemo(() => ({ overview, reachable, publish, markUnreachable }), [overview, reachable, publish, markUnreachable])
  return <SystemContext.Provider value={value}>{children}</SystemContext.Provider>
}

// eslint-disable-next-line react/only-export-components
export function useSystem(): SystemContextValue {
  const ctx = useContext(SystemContext)
  if (!ctx) throw new Error('useSystem must be used inside SystemProvider')
  return ctx
}
