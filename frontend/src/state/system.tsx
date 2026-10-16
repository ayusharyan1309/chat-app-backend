import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { systemApi } from '../api/admin'
import type { ApiError } from '../api/http'
import { useApi } from '../hooks/useApi'
import type { SystemOverview } from '../types'

interface SystemContextValue {
  /** Last known overview (kept while a refresh fails). */
  overview: SystemOverview | null
  /** `null` until the first answer; `false` while the latest request failed. */
  reachable: boolean | null
  error: ApiError | null
  /** True while the first overview has not arrived yet. */
  loading: boolean
  refreshing: boolean
  reload: () => Promise<void>
  /** Poll at the live rate while the returned release function has not been called. */
  requestLive: () => () => void
}

const SystemContext = createContext<SystemContextValue | null>(null)

/** Top bar health indicator. */
const BACKGROUND_POLL_MS = 60_000
/** While a page showing live numbers (the overview) is open. */
export const LIVE_POLL_MS = 10_000

/** The single poller of `GET /api/admin/system/overview`; pages read it from here. */
export function SystemProvider({ children }: { children: ReactNode }) {
  const [live, setLive] = useState(0)
  const overview = useApi((signal) => systemApi.overview({ signal }), 'system:overview', {
    intervalMs: live > 0 ? LIVE_POLL_MS : BACKGROUND_POLL_MS,
  })
  const { data, error, loading, refreshing, reload } = overview

  const requestLive = useCallback(() => {
    setLive((n) => n + 1)
    return () => setLive((n) => Math.max(0, n - 1))
  }, [])

  const reachable = error ? false : data ? true : null
  const value = useMemo<SystemContextValue>(
    () => ({ overview: data ?? null, reachable, error, loading, refreshing, reload, requestLive }),
    [data, reachable, error, loading, refreshing, reload, requestLive],
  )
  return <SystemContext.Provider value={value}>{children}</SystemContext.Provider>
}

// oxlint-disable-next-line react/only-export-components
export function useSystem(): SystemContextValue {
  const ctx = useContext(SystemContext)
  if (!ctx) throw new Error('useSystem must be used inside SystemProvider')
  return ctx
}

/** The overview at the live polling rate while the calling component is mounted. */
// oxlint-disable-next-line react/only-export-components
export function useLiveSystemOverview(): SystemContextValue {
  const system = useSystem()
  const { requestLive, reload } = system
  useEffect(() => {
    const release = requestLive()
    void reload()
    return release
  }, [requestLive, reload])
  return system
}
