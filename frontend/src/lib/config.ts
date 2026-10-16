/** REST base. Empty string = same origin (the dev server proxies /api). */
export const API_BASE: string = (import.meta.env.VITE_API_BASE ?? '').trim().replace(/\/+$/, '')

/** Resolve a backend-relative path (e.g. a signed media URL "/api/v1/media/...") against API_BASE. */
export function resolveBackendUrl(pathOrUrl: string): string {
  if (/^[a-z][a-z0-9+.-]*:/i.test(pathOrUrl)) return pathOrUrl
  if (!API_BASE) return pathOrUrl
  return `${API_BASE}${pathOrUrl.startsWith('/') ? '' : '/'}${pathOrUrl}`
}

/** Native WebSocket STOMP endpoint, derived from VITE_WS_URL, VITE_API_BASE or the page origin. */
export function realtimeUrl(): string {
  const explicit = (import.meta.env.VITE_WS_URL ?? '').trim()
  if (explicit) return explicit
  if (API_BASE) {
    const url = new URL(API_BASE, window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.pathname = `${url.pathname.replace(/\/+$/, '')}/ws`
    url.search = ''
    url.hash = ''
    return url.toString()
  }
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

/** Human-readable description of where the backend is expected, for connection errors. */
export function backendLabel(): string {
  return API_BASE || `${window.location.origin} (proxied)`
}
