import { useCallback, useEffect, useRef, useState, type SetStateAction } from 'react'
import { toApiError, type ApiError } from '../api/http'

export interface ApiState<T> {
  data: T | undefined
  error: ApiError | null
  /** True while there is no data for the current key yet. */
  loading: boolean
  /** True during any request, including silent background refreshes. */
  refreshing: boolean
  reload: () => Promise<void>
  setData: (update: SetStateAction<T | undefined>) => void
}

interface Options {
  /** Re-fetch silently on this interval (ms). */
  intervalMs?: number
  /** Keep showing the previous key's data while the next key loads. */
  keepPrevious?: boolean
  /** Skip interval refreshes while the tab is hidden (default true). */
  pauseWhenHidden?: boolean
}

interface Snapshot<T> {
  /** Key the data/error belong to. */
  key: string | null
  data: T | undefined
  error: ApiError | null
  /** Key of the last request that finished (success or failure). */
  settled: string | null
}

/**
 * Minimal data-loading hook. `key` identifies the request; when it changes the fetcher runs again.
 * Pass `null` to skip. Responses for an outdated key are discarded, and loading/data are derived from
 * the key so nothing has to be reset inside an effect.
 */
export function useApi<T>(fetcher: () => Promise<T>, key: string | null, options: Options = {}): ApiState<T> {
  const { intervalMs, keepPrevious = false, pauseWhenHidden = true } = options
  const [snap, setSnap] = useState<Snapshot<T>>({ key: null, data: undefined, error: null, settled: null })
  const [inflight, setInflight] = useState(0)
  const fetcherRef = useRef(fetcher)
  const keyRef = useRef(key)
  const keepPreviousRef = useRef(keepPrevious)
  const seqRef = useRef(0)

  useEffect(() => {
    fetcherRef.current = fetcher
    keyRef.current = key
    keepPreviousRef.current = keepPrevious
  })

  const run = useCallback(async (forKey: string) => {
    const seq = ++seqRef.current
    setInflight((n) => n + 1)
    try {
      const result = await fetcherRef.current()
      if (seq !== seqRef.current || keyRef.current !== forKey) return
      setSnap({ key: forKey, data: result, error: null, settled: forKey })
    } catch (err) {
      if (seq !== seqRef.current || keyRef.current !== forKey) return
      const error = toApiError(err)
      setSnap((prev) => ({ key: forKey, data: prev.key === forKey || keepPreviousRef.current ? prev.data : undefined, error, settled: forKey }))
    } finally {
      setInflight((n) => Math.max(0, n - 1))
    }
  }, [])

  useEffect(() => {
    if (key === null) {
      seqRef.current += 1
      return
    }
    keyRef.current = key
    // Fetching on key change is the purpose of this effect; results are applied asynchronously.
    // oxlint-disable-next-line react/set-state-in-effect
    void run(key)
  }, [key, run])

  useEffect(() => {
    if (!intervalMs || key === null) return
    const timer = window.setInterval(() => {
      if (pauseWhenHidden && document.visibilityState === 'hidden') return
      void run(key)
    }, intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs, key, pauseWhenHidden, run])

  const reload = useCallback(async () => {
    const current = keyRef.current
    if (current !== null) await run(current)
  }, [run])

  const setData = useCallback((update: SetStateAction<T | undefined>) => {
    setSnap((prev) => {
      const nextData = typeof update === 'function' ? (update as (p: T | undefined) => T | undefined)(prev.data) : update
      return { ...prev, data: nextData }
    })
  }, [])

  const matches = snap.key === key
  const data = matches || keepPrevious ? snap.data : undefined
  const error = matches ? snap.error : null
  const loading = key !== null && snap.settled !== key && data === undefined

  return { data, error, loading, refreshing: inflight > 0, reload, setData }
}
