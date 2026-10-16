/** Pane slots of the playground, persisted in the URL as `?users=alice,,bob` (empty = user picker). */

export const MAX_PANES = 4

/**
 * Parse the `users` parameter. A user may occupy only one pane, so later duplicates are dropped;
 * that also makes `tenant:userId` a stable pane key. Missing parameter = two empty pickers.
 */
export function parseSlots(raw: string | null): string[] {
  if (raw === null) return ['', '']
  const seen = new Set<string>()
  const slots: string[] = []
  for (const part of raw.split(',')) {
    const id = part.trim()
    if (id && seen.has(id)) continue
    if (id) seen.add(id)
    slots.push(id)
  }
  const limited = slots.slice(0, MAX_PANES)
  return limited.length ? limited : ['']
}

/**
 * React keys for the slots. A signed-in pane is keyed by tenant and user, so closing a pane never
 * remounts the ones after it (which would open new sockets and mint new, audited impersonation
 * tokens). Pickers hold no session and are keyed by their position among the pickers.
 */
export function slotKeys(tenant: string, slots: readonly string[]): string[] {
  let picker = 0
  return slots.map((userId) => (userId ? `pane:${tenant}:${userId}` : `picker:${tenant}:${picker++}`))
}
