/** ISO-8601 UTC timestamp string, e.g. "2026-10-16T17:05:00Z". */
export type IsoDateTime = string

/** Error codes the backend puts in the `error` field of every error body (docs/API.md). */
export const SERVER_ERROR_CODES = [
  'bad_request',
  'validation_failed',
  'unauthorized',
  'forbidden',
  'not_found',
  'conflict',
  'feature_disabled',
  'quota_exceeded',
  'rate_limited',
  'unavailable',
  'internal_error',
] as const

export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number]

/**
 * Codes produced on the client when there is no standard error body: the request was cancelled,
 * timed out or never reached the backend, the proxy answered with a bare status, or something other
 * than an HTTP failure was thrown. `unknown` is the fallback for anything unrecognised, including a
 * server code this console does not know yet (the original string stays available as
 * `ApiError.rawCode`).
 */
export type ClientErrorCode = 'cancelled' | 'timeout' | 'network_error' | 'http_error' | 'client_error' | 'unknown'

/** Closed union, so a `switch` over it can be checked for exhaustiveness. */
export type ApiErrorCode = ServerErrorCode | ClientErrorCode

const SERVER_CODES: ReadonlySet<string> = new Set(SERVER_ERROR_CODES)

export function isServerErrorCode(value: string): value is ServerErrorCode {
  return SERVER_CODES.has(value)
}

/** Shape of every error response: `{status, error, message, path, timestamp}`. */
export interface ApiErrorBody {
  status: number
  /** Normally a `ServerErrorCode`; typed as `string` because the wire value is not validated. */
  error: string
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
