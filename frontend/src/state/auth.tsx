import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from '../api/admin'
import { adminSession } from '../api/http'
import type { Admin } from '../types'

type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  status: AuthStatus
  admin: Admin | null
  /** True for OWNER and ADMIN; VIEWER is read-only. */
  canWrite: boolean
  /** Set when the session ended because the backend returned 401. */
  expired: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [admin, setAdmin] = useState<Admin | null>(null)
  const [status, setStatus] = useState<AuthStatus>(() => (adminSession.getToken() ? 'loading' : 'anonymous'))
  const [expired, setExpired] = useState(false)

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
      // token so the console recovers once the backend is reachable again.
      if (adminSession.getToken()) {
        setStatus('authenticated')
      } else {
        setAdmin(null)
        setStatus('anonymous')
      }
    }
  }, [])

  useEffect(() => {
    if (status === 'loading') void refresh()
    // Only on first mount.
    // eslint-disable-next-line react/exhaustive-deps
  }, [])

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
    setStatus('authenticated')
  }, [])

  const logout = useCallback(() => {
    adminSession.clear()
    setAdmin(null)
    setExpired(false)
    setStatus('anonymous')
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      admin,
      canWrite: admin ? admin.role !== 'VIEWER' : true,
      expired,
      login,
      logout,
      refresh,
    }),
    [status, admin, expired, login, logout, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
