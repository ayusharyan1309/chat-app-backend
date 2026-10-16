/** ISO-8601 UTC timestamp string, e.g. "2026-10-16T17:05:00Z". */
export type IsoDateTime = string

/** Error codes the backend uses in the `error` field of every error body. */
export type ApiErrorCode =
  | 'bad_request'
  | 'validation_failed'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'feature_disabled'
  | 'quota_exceeded'
  | 'rate_limited'
  | 'unavailable'
  | 'internal_error'

/** Shape of every error response: `{status, error, message, path, timestamp}`. */
export interface ApiErrorBody {
  status: number
  error: ApiErrorCode | string
  message: string
  path?: string
  timestamp?: IsoDateTime
}

/** Paged list used by user listings: `{items, total}`. */
export interface Page<T> {
  items: T[]
  total: number
}

/** Free-form JSON object. */
export type JsonObject = Record<string, unknown>
