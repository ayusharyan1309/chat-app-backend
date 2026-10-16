import { useEffect, useState } from 'react'

/**
 * Re-render on an interval so relative timestamps stay fresh. Call it in the smallest component that
 * shows such a timestamp, so the tick does not re-render a whole page or list.
 */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs])
  return now
}

/**
 * True until `deadline` (ms epoch) passes, then re-renders once and stays false. Costs a single
 * timer only while the deadline is in the future (e.g. an edit window), and none afterwards.
 */
export function useBeforeDeadline(deadline: number | null): boolean {
  // Render stays pure: compare against the mount time, and let the timer below (which reads the
  // real clock) close the window, immediately if a changed deadline already lies in the past.
  const [mountedAt] = useState(Date.now)
  // The deadline this component has seen pass (a timer fired for it).
  const [passed, setPassed] = useState<number | null>(null)
  const open = deadline !== null && passed !== deadline && mountedAt < deadline
  useEffect(() => {
    if (!open || deadline === null) return
    const delay = Math.min(Math.max(0, deadline - Date.now()), MAX_TIMEOUT_MS)
    const timer = window.setTimeout(() => setPassed(deadline), delay)
    return () => window.clearTimeout(timer)
  }, [open, deadline])
  return open
}

/** Browsers clamp longer timeouts to 1 ms; 24 days is far beyond any edit window anyway. */
const MAX_TIMEOUT_MS = 2_000_000_000
