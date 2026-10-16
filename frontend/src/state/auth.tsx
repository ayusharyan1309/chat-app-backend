import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from '../api/admin'
import { adminSession } from '../api/http'
import type { Admin } from '../types'

type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  status: AuthStatus
  /** `null` while signed in but the profile could not be loaded yet (it is retried in the background). */
  admin: Admin | null
  /** True for OWNER and ADMIN. False for VIEWER and while the profile (and so the role) is unknown. */
  canWrite: boolean
  /** Set when the session ended because the backend returned 401. */
  expired: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

/** Back-off for re-fetching the profile after a non-401 failure (backend down, 5xx). */
const PROFILE_RETRY_MS = [5_000, 15_000, 30_000, 60_000] as const

export function AuthProvider({ children }: { children: ReactNode }) {
  const [admin, setAdmin] = useState<Admin | null>(null)
  const [status, setStatus] = useState<AuthStatus>(() => (adminSession.getToken() ? 'loading' : 'anonymous'))
  const [expired, setExpired] = useState(false)
  const [profileAttempt, setProfileAttempt] = useState(0)

  const refresh = useCallback(async () => {
    if (!adminSession.getToken()) {
      setAdmin(null)
      setStatus('anonymous')
      return
    }
    try {
      const me = await authApi.me()
      setAdmin(me)
      setStatus('authenticated')
    } catch {
      // 401 is handled by the unauthorized listener; any other failure (backend down) keeps the
      // token so the console recovers once the backend is reachable again (see the retry below).
      if (adminSession.getToken()) {
        setStatus('authenticated')
      } else {
        setAdmin(null)
        setStatus('anonymous')
      }
    }
  }, [])

  // Initial profile load when a stored token exists (state changes only after `/me` answers).
  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect
    if (adminSession.getToken()) void refresh()
  }, [refresh])

  // Signed in without a profile: the role is unknown, so writes stay disabled; keep retrying.
  useEffect(() => {
    if (status !== 'authenticated' || admin) return
    const delay = PROFILE_RETRY_MS[Math.min(profileAttempt, PROFILE_RETRY_MS.length - 1)]
    const timer = window.setTimeout(() => {
      setProfileAttempt((n) => n + 1)
      void refresh()
    }, delay)
    return () => window.clearTimeout(timer)
  }, [status, admin, profileAttempt, refresh])

  useEffect(
    () =>
      adminSession.onUnauthorized(() => {
        setAdmin(null)
        setExpired(true)
        setStatus('anonymous')
      }),
    [],
  )

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password })
    adminSession.set(res.token, res.expiresAt)
    setAdmin(res.admin)
    setExpired(false)
    setProfileAttempt(0)
    setStatus('authenticated')
  }, [])

  const logout = useCallback(() => {
    adminSession.clear()
    setAdmin(null)
    setExpired(false)
    setProfileAttempt(0)
    setStatus('anonymous')
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      admin,
      canWrite: admin !== null && admin.role !== 'VIEWER',
      expired,
      login,
      logout,
      refresh,
    }),
    [status, admin, expired, login, logout, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// oxlint-disable-next-line react/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
