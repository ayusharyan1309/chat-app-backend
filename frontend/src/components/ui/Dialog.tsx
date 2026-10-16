import { X } from 'lucide-react'
import { useEffect, useId, useRef, type FormEvent, type ReactNode } from 'react'
import { cx } from '../../lib/util'

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  children?: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg' | 'xl'
  /** Render the body as a <form>; the footer's submit button submits it. */
  onSubmit?: (event: FormEvent<HTMLFormElement>) => void
  /** Prevent closing via Escape/backdrop (e.g. while a request is in flight). */
  dismissible?: boolean
  tone?: 'default' | 'danger'
  className?: string
}

/**
 * Modal built on the native <dialog> element: real focus trapping, Escape handling and an inert
 * background for free. Rendered only while open so state resets between openings.
 */
export function Dialog(props: DialogProps) {
  if (!props.open) return null
  return <DialogInner {...props} />
}

function DialogInner({
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
  onSubmit,
  dismissible = true,
  tone = 'default',
  className,
}: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descId = useId()
  const pressStartedOnBackdrop = useRef(false)
  const dismissibleRef = useRef(dismissible)
  const onCloseRef = useRef(onClose)

  useEffect(() => {
    dismissibleRef.current = dismissible
    onCloseRef.current = onClose
  })

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    if (!dialog.open) dialog.showModal()
    const onCancel = (event: Event) => {
      event.preventDefault()
      if (dismissibleRef.current) onCloseRef.current()
    }
    dialog.addEventListener('cancel', onCancel)
    return () => {
      dialog.removeEventListener('cancel', onCancel)
      if (dialog.open) dialog.close()
      previouslyFocused?.focus?.()
    }
  }, [])

  const body = (
    <>
      <header className="dialog__header">
        <div className="dialog__heading">
          <h2 className="dialog__title" id={titleId}>
            {title}
          </h2>
          {description ? (
            <p className="dialog__desc" id={descId}>
              {description}
            </p>
          ) : null}
        </div>
        <button type="button" className="btn btn--ghost btn--icon dialog__close" onClick={onClose} aria-label="Close dialog" disabled={!dismissible}>
          <X size={16} aria-hidden />
        </button>
      </header>
      {children ? <div className="dialog__body">{children}</div> : null}
      {footer ? <footer className="dialog__footer">{footer}</footer> : null}
    </>
  )

  return (
    <dialog
      ref={ref}
      className={cx('dialog', `dialog--${size}`, tone === 'danger' && 'dialog--danger', className)}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onMouseDown={(e) => {
        pressStartedOnBackdrop.current = e.target === e.currentTarget
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && pressStartedOnBackdrop.current && dismissible) onClose()
        pressStartedOnBackdrop.current = false
      }}
    >
      {onSubmit ? (
        <form
          className="dialog__surface"
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            onSubmit(e)
          }}
        >
          {body}
        </form>
      ) : (
        <div className="dialog__surface">{body}</div>
      )}
    </dialog>
  )
}
