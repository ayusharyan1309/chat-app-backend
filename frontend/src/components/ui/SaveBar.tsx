import type { ReactNode } from 'react'
import { Button } from './Button'

/** Sticky bar shown while a form has unsaved changes. */
export function SaveBar({
  dirty,
  saving,
  onSave,
  onDiscard,
  message,
  saveLabel = 'Save changes',
  disabled,
}: {
  dirty: boolean
  saving: boolean
  onSave: () => void
  onDiscard: () => void
  message?: ReactNode
  saveLabel?: string
  disabled?: boolean
}) {
  if (!dirty && !saving) return null
  return (
    <section className="save-bar" aria-label="Unsaved changes">
      <span className="save-bar__status">{message ?? 'You have unsaved changes.'}</span>
      <Button variant="ghost" onClick={onDiscard} disabled={saving}>
        Discard
      </Button>
      <Button variant="primary" onClick={onSave} loading={saving} disabled={disabled}>
        {saveLabel}
      </Button>
    </section>
  )
}
