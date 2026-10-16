import { API_BASE } from './config'

/**
 * - `image`: avatars, logos and other pictures from tenant data. Any http(s) URL or a relative path.
 * - `attachment`: message attachments. Only the backend's own signed media endpoint
 *   (`/api/v1/media/…` on the API origin or the console's origin).
 */
export type MediaUrlPolicy = 'image' | 'attachment'

export interface UrlEnvironment {
  /** URL of the current page; relative references and the same-origin rule use it. */
  pageUrl: string
  /** REST base (`VITE_API_BASE`); empty means the backend is served from the page's origin. */
  apiBase: string
}

const MEDIA_PATH = '/api/v1/media/'
const HAS_SCHEME = /^[a-z][a-z0-9+.-]*:/i

function currentEnvironment(): UrlEnvironment {
  return { pageUrl: globalThis.location?.href ?? 'http://localhost/', apiBase: API_BASE }
}

/** Backend-relative paths (e.g. "/api/v1/media/…") are resolved against the API base, like every request. */
function resolveAgainstBackend(value: string, env: UrlEnvironment): URL {
  if (HAS_SCHEME.test(value)) return new URL(value)
  const joined = env.apiBase ? `${env.apiBase}${value.startsWith('/') ? '' : '/'}${value}` : value
  return new URL(joined, env.pageUrl)
}

function isBackendMedia(url: URL, env: UrlEnvironment): boolean {
  const page = new URL(env.pageUrl)
  if (url.origin === page.origin && url.pathname.startsWith(MEDIA_PATH)) return true
  if (!env.apiBase) return false
  const api = new URL(env.apiBase, page)
  const prefix = `${api.pathname.replace(/\/+$/, '')}${MEDIA_PATH}`
  return url.origin === api.origin && url.pathname.startsWith(prefix)
}

/**
 * Validate a URL that came from tenant or end-user data before it is put into `src` or `href`.
 * Returns the absolute URL to use, or `null` when it must not be rendered as a link or image
 * (non-http(s) schemes such as `javascript:` or `data:`, embedded credentials, unparsable input,
 * and, for attachments, anything that is not the backend's media endpoint). The URL parser
 * normalises `..` and `%2e%2e` segments before the path prefix is checked.
 */
export function safeMediaUrl(raw: string | null | undefined, policy: MediaUrlPolicy = 'image', env: UrlEnvironment = currentEnvironment()): string | null {
  const value = raw?.trim()
  if (!value) return null
  let url: URL
  try {
    url = resolveAgainstBackend(value, env)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  if (url.username || url.password) return null
  if (policy === 'attachment' && !isBackendMedia(url, env)) return null
  return url.href
}
