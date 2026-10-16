import { useCallback, useEffect, useRef, useState } from 'react'
import { toApiError, type ApiError } from '../api/http'

export type PageCursor = string | number

export interface PagedOptions<T> {
  /** A page shorter than this is the last one. */
  pageSize: number
  /** Cursor that requests the page after `item` (e.g. its id for `before=`). */
  cursorOf: (item: T) => PageCursor
  /** Stable identity used to drop duplicates when pages overlap. */
  idOf: (item: T) => string
}

export interface PagedState<T> {
  items: T[]
  error: ApiError | null
  /** True while the first page for the current key has not arrived yet. */
  loading: boolean
  /** True while a first-page request (initial load or reload) is in flight. */
  refreshing: boolean
  loadingMore: boolean
  hasMore: boolean
  /** Reset to the first page of the current key. */
  reload: () => Promise<void>
  /** Append the next page; ignored while one is already loading or when there is none. */
  loadMore: () => Promise<void>
}

interface Snapshot<T> {
  key: string | null
  items: T[]
  hasMore: boolean
  error: ApiError | null
  /** Key whose first page finished (success or failure). */
  settled: string | null
}

/**
 * Cursor-paginated list, following `useApi`'s key/sequence rules: every key change or reload starts
 * a new generation, aborts the previous generation's requests (first page and "load more") and
 * discards anything they return, so an older response can never overwrite or extend a newer list.
 */
export function usePagedApi<T>(
  fetchPage: (cursor: PageCursor | undefined, signal: AbortSignal) => Promise<T[]>,
  key: string | null,
  options: PagedOptions<T>,
): PagedState<T> {
  const [snap, setSnap] = useState<Snapshot<T>>({ key: null, items: [], hasMore: false, error: null, settled: null })
  const [refreshing, setRefreshing] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const fetchRef = useRef(fetchPage)
  const optionsRef = useRef(options)
  const keyRef = useRef(key)
  const snapRef = useRef(snap)
  const genRef = useRef(0)
  const controllerRef = useRef<AbortController | null>(null)
  const moreGenRef = useRef<number | null>(null)

  useEffect(() => {
    fetchRef.current = fetchPage
    optionsRef.current = options
    keyRef.current = key
    snapRef.current = snap
  })

  const start = useCallback(async (forKey: string) => {
    const gen = ++genRef.current
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    moreGenRef.current = null
    setLoadingMore(false)
    setRefreshing((n) => n + 1)
    try {
      const page = await fetchRef.current(undefined, controller.signal)
      if (gen !== genRef.current) return
      setSnap({ key: forKey, items: page, hasMore: page.length >= optionsRef.current.pageSize, error: null, settled: forKey })
    } catch (err) {
      if (gen !== genRef.current) return
      const error = toApiError(err)
      setSnap((prev) => (prev.key === forKey ? { ...prev, error, settled: forKey } : { key: forKey, items: [], hasMore: false, error, settled: forKey }))
    } finally {
      setRefreshing((n) => Math.max(0, n - 1))
    }
  }, [])

  useEffect(() => {
    keyRef.current = key
    if (key === null) {
      genRef.current += 1
      controllerRef.current?.abort()
      return
    }
    // Fetching on key change is the purpose of this effect: `start` only resets the loading flags
    // synchronously, the page is applied after the request resolves.
    // oxlint-disable-next-line react/set-state-in-effect
    void start(key)
  }, [key, start])

  useEffect(() => () => controllerRef.current?.abort(), [])

  const reload = useCallback(async () => {
    const current = keyRef.current
    if (current !== null) await start(current)
  }, [start])

  const loadMore = useCallback(async () => {
    const forKey = keyRef.current
    const current = snapRef.current
    const controller = controllerRef.current
    const last = current.items[current.items.length - 1]
    if (forKey === null || current.key !== forKey || !current.hasMore || !last || !controller || moreGenRef.current !== null) return
    const gen = genRef.current
    const { cursorOf, idOf, pageSize } = optionsRef.current
    moreGenRef.current = gen
    setLoadingMore(true)
    try {
      const page = await fetchRef.current(cursorOf(last), controller.signal)
      if (gen !== genRef.current) return
      setSnap((prev) => {
        if (prev.key !== forKey) return prev
        const seen = new Set(prev.items.map(idOf))
        return { ...prev, items: [...prev.items, ...page.filter((item) => !seen.has(idOf(item)))], hasMore: page.length >= pageSize, error: null }
      })
    } catch (err) {
      if (gen !== genRef.current) return
      const error = toApiError(err)
      setSnap((prev) => (prev.key === forKey ? { ...prev, error } : prev))
    } finally {
      if (moreGenRef.current === gen) {
        moreGenRef.current = null
        setLoadingMore(false)
      }
    }
  }, [])

  const matches = snap.key === key
  return {
    items: matches ? snap.items : [],
    error: matches ? snap.error : null,
    loading: key !== null && snap.settled !== key,
    refreshing: refreshing > 0,
    loadingMore,
    hasMore: matches && snap.hasMore,
    reload,
    loadMore,
  }
}
