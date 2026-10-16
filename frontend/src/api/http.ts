import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { API_BASE, backendLabel } from '../lib/config'
import { isRecord } from '../lib/util'
import type { ApiErrorBody } from '../types'

/**
 * Normalised error for every failed request. `message` is the backend's human readable message
 * when the response carries the standard error body, otherwise a useful client-side description.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly path: string | undefined
  readonly body: unknown

  constructor(message: string, status: number, code: string, path?: string, body?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.path = path
    this.body = body
  }

  get isNetwork(): boolean {
    return this.status === 0
  }
}

function isErrorBody(value: unknown): value is ApiErrorBody {
  return isRecord(value) && typeof value.message === 'string'
}

const STATUS_FALLBACK: Record<number, string> = {
  400: 'The request was rejected.',
  401: 'Your session has expired. Sign in again.',
  403: 'You do not have permission to do that.',
  404: 'Not found.',
  409: 'That conflicts with the current state.',
  413: 'The upload is too large.',
  429: 'Too many requests. Slow down and try again.',
  500: 'The server hit an internal error.',
  502: 'The backend is not reachable through the proxy.',
  503: 'The service is temporarily unavailable.',
  504: 'The backend did not respond in time.',
}

export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err
  if (axios.isAxiosError(err)) {
    const ax = err as AxiosError<unknown>
    if (axios.isCancel(ax)) return new ApiError('Request cancelled.', 0, 'cancelled')
    const res = ax.response
    if (!res) {
      const timeout = ax.code === 'ECONNABORTED' || ax.code === 'ETIMEDOUT'
      return new ApiError(
        timeout
          ? `The backend at ${backendLabel()} did not respond in time.`
          : `Cannot reach the backend at ${backendLabel()}. Check that it is running.`,
        0,
        timeout ? 'timeout' : 'network_error',
      )
    }
    const body = res.data
    if (isErrorBody(body)) {
      return new ApiError(body.message, res.status, String(body.error ?? 'error'), body.path, body)
    }
    // Proxy errors (backend down) come back as 5xx with an empty / HTML body.
    const fallback = STATUS_FALLBACK[res.status] ?? `Request failed with status ${res.status}.`
    return new ApiError(fallback, res.status, 'http_error', ax.config?.url, body)
  }
  if (err instanceof Error) return new ApiError(err.message, 0, 'client_error')
  return new ApiError('Something went wrong.', 0, 'unknown')
}

/** Human readable message for any thrown value — use this for toasts and inline errors. */
export function errorMessage(err: unknown): string {
  return toApiError(err).message
}

/* ------------------------------------------------------------ Admin session ---- */

const TOKEN_KEY = 'console.adminToken'
const EXPIRES_KEY = 'console.adminTokenExpiresAt'

type Listener = () => void
const unauthorizedListeners = new Set<Listener>()

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

export const adminSession = {
  getToken(): string | null {
    const token = readStorage(TOKEN_KEY)
    if (!token) return null
    const expiresAt = readStorage(EXPIRES_KEY)
    if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
      adminSession.clear()
      return null
    }
    return token
  },
  set(token: string, expiresAt: string | null): void {
    try {
      window.localStorage.setItem(TOKEN_KEY, token)
      if (expiresAt) window.localStorage.setItem(EXPIRES_KEY, expiresAt)
      else window.localStorage.removeItem(EXPIRES_KEY)
    } catch {
      /* storage unavailable: session lasts for this tab only via memory of axios headers */
    }
  },
  clear(): void {
    try {
      window.localStorage.removeItem(TOKEN_KEY)
      window.localStorage.removeItem(EXPIRES_KEY)
    } catch {
      /* ignore */
    }
  },
  /** Called whenever an admin request returns 401. */
  onUnauthorized(listener: Listener): () => void {
    unauthorizedListeners.add(listener)
    return () => unauthorizedListeners.delete(listener)
  },
}

/* ---------------------------------------------------------------- Instances ---- */

function withJsonDefaults(instance: AxiosInstance): AxiosInstance {
  instance.defaults.headers.common.Accept = 'application/json'
  return instance
}

/** Admin API (`/api/admin/**`): admin JWT attached, 401 ends the session. */
export const adminHttp = withJsonDefaults(axios.create({ baseURL: API_BASE, timeout: 30_000 }))

adminHttp.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = adminSession.getToken()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

adminHttp.interceptors.response.use(
  (response) => response,
  (err: unknown) => {
    const apiError = toApiError(err)
    const url = axios.isAxiosError(err) ? (err.config?.url ?? '') : ''
    if (apiError.status === 401 && !url.includes('/api/admin/auth/login')) {
      adminSession.clear()
      unauthorizedListeners.forEach((listener) => listener())
    }
    return Promise.reject(apiError)
  },
)

/** Public endpoints (no credentials). */
export const publicHttp = withJsonDefaults(axios.create({ baseURL: API_BASE, timeout: 30_000 }))
publicHttp.interceptors.response.use(
  (response) => response,
  (err: unknown) => Promise.reject(toApiError(err)),
)

/**
 * Client API instance acting as an end user (used by the chat playground). A 401 here never touches
 * the admin session; `onUnauthorized` lets the caller mint a fresh impersonation token.
 */
export function createUserHttp(getToken: () => string | null, onUnauthorized?: () => void): AxiosInstance {
  const instance = withJsonDefaults(axios.create({ baseURL: API_BASE, timeout: 60_000 }))
  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = getToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    return config
  })
  instance.interceptors.response.use(
    (response) => response,
    (err: unknown) => {
      const apiError = toApiError(err)
      if (apiError.status === 401) onUnauthorized?.()
      return Promise.reject(apiError)
    },
  )
  return instance
}

/** Encode a single path segment. */
export function seg(value: string | number): string {
  return encodeURIComponent(String(value))
}

/** Build a query object without empty values. */
export function query(params: Record<string, string | number | boolean | null | undefined>): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    out[key] = value
  }
  return out
}
