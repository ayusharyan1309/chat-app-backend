import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import type { ApiErrorCode } from '../types'
import { ApiError, toApiError } from './http'

function axiosFailure(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders(), url: '/api/x' }
  const response: AxiosResponse = { status, statusText: '', headers: {}, config, data }
  return new AxiosError('failed', 'ERR_BAD_RESPONSE', config, undefined, response)
}

/** Compile-time check that `code` is a closed union: this switch must stay exhaustive. */
function describeCode(code: ApiErrorCode): string {
  switch (code) {
    case 'bad_request':
    case 'validation_failed':
    case 'conflict':
      return 'fix-input'
    case 'unauthorized':
    case 'forbidden':
      return 'auth'
    case 'not_found':
      return 'missing'
    case 'feature_disabled':
    case 'quota_exceeded':
    case 'rate_limited':
      return 'limits'
    case 'unavailable':
    case 'internal_error':
    case 'http_error':
    case 'network_error':
    case 'timeout':
      return 'retry'
    case 'cancelled':
    case 'client_error':
    case 'unknown':
      return 'other'
    default: {
      const never: never = code
      return never
    }
  }
}

describe('toApiError', () => {
  it('maps a standard error body to a known code', () => {
    const err = toApiError(axiosFailure(404, { status: 404, error: 'not_found', message: 'Conversation not found' }))
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 404, code: 'not_found', rawCode: 'not_found', message: 'Conversation not found' })
    expect(describeCode(err.code)).toBe('missing')
  })

  it('keeps an unknown server code as rawCode and falls back to "unknown"', () => {
    const err = toApiError(axiosFailure(418, { status: 418, error: 'teapot', message: 'I am a teapot' }))
    expect(err).toMatchObject({ code: 'unknown', rawCode: 'teapot', message: 'I am a teapot' })
  })

  it('describes bare proxy failures', () => {
    const err = toApiError(axiosFailure(502, '<html>Bad gateway</html>'))
    expect(err).toMatchObject({ status: 502, code: 'http_error' })
    expect(err.isNetwork).toBe(false)
  })

  it('wraps other thrown values', () => {
    expect(toApiError(new Error('boom'))).toMatchObject({ code: 'client_error', message: 'boom' })
    expect(toApiError(42)).toMatchObject({ code: 'unknown' })
  })
})
