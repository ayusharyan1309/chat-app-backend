import { useCallback, useEffect, useRef, useState } from 'react'
import { toApiError, type ApiError } from '../api/http'
import { useToast } from '../state/toast'

interface ActionOptions<R> {
  /** Toast on success (string or derived from the result). */
  success?: string | ((result: R) => string)
  /** Toast the backend message on failure (default true). Set false to handle inline via `error`. */
  toastError?: boolean
  errorTitle?: string
}

export interface Action<A extends unknown[], R> {
  run: (...args: A) => Promise<R | undefined>
  busy: boolean
  error: ApiError | null
  clearError: () => void
}

/** Wrap an async mutation with busy/error state and centralised toasts. */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>, options: ActionOptions<R> = {}): Action<A, R> {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const fnRef = useRef(fn)
  const optsRef = useRef(options)
  useEffect(() => {
    fnRef.current = fn
    optsRef.current = options
  })

  const run = useCallback(
    async (...args: A): Promise<R | undefined> => {
      setBusy(true)
      setError(null)
      try {
        const result = await fnRef.current(...args)
        const { success } = optsRef.current
        if (success) toast.success(typeof success === 'function' ? success(result) : success)
        return result
      } catch (err) {
        const apiError = toApiError(err)
        setError(apiError)
        const { toastError = true, errorTitle } = optsRef.current
        if (toastError) {
          if (errorTitle) toast.error(apiError, errorTitle)
          else toast.error(apiError)
        }
        return undefined
      } finally {
        setBusy(false)
      }
    },
    [toast],
  )

  const clearError = useCallback(() => setError(null), [])
  return { run, busy, error, clearError }
}
