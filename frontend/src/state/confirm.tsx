import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { errorMessage } from '../api/http'
import { Button } from '../components/ui/Button'
import { Dialog } from '../components/ui/Dialog'
import { Callout } from '../components/ui/Feedback'
import { Checkbox, Field, Input } from '../components/ui/Form'

export interface ConfirmOptions {
  title: ReactNode
  body?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'default' | 'danger'
  /** Operator must type this exact text (e.g. a tenant slug) to enable the confirm button. */
  requireText?: string
  /** Operator must tick this acknowledgement checkbox. */
  acknowledge?: ReactNode
  /**
   * Run the action inside the dialog: it stays open with a spinner, shows the backend error inline
   * on failure, and resolves `true` only after success.
   */
  action?: () => Promise<unknown>
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmFn | null>(null)

interface Pending extends ConfirmOptions {
  resolve: (value: boolean) => void
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  const [typed, setTyped] = useState('')
  const [acked, setAcked] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pendingRef = useRef<Pending | null>(null)

  const confirm = useCallback<ConfirmFn>(
    (options) =>
      new Promise<boolean>((resolve) => {
        pendingRef.current?.resolve(false)
        const next = { ...options, resolve }
        pendingRef.current = next
        setTyped('')
        setAcked(false)
        setBusy(false)
        setError(null)
        setPending(next)
      }),
    [],
  )

  const close = (result: boolean) => {
    pendingRef.current?.resolve(result)
    pendingRef.current = null
    setPending(null)
  }

  const onConfirm = async () => {
    if (!pending) return
    if (!pending.action) {
      close(true)
      return
    }
    setBusy(true)
    setError(null)
    try {
      await pending.action()
      close(true)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const blocked =
    !!pending && ((pending.requireText !== undefined && typed.trim() !== pending.requireText) || (pending.acknowledge !== undefined && !acked))

  const value = useMemo(() => confirm, [confirm])

  return (
    <ConfirmContext.Provider value={value}>
      {children}
      <Dialog
        open={pending !== null}
        onClose={() => close(false)}
        dismissible={!busy}
        size="sm"
        tone={pending?.tone === 'danger' ? 'danger' : 'default'}
        title={pending?.title ?? ''}
        onSubmit={() => {
          if (!blocked && !busy) void onConfirm()
        }}
        footer={
          <>
            <Button variant="ghost" onClick={() => close(false)} disabled={busy}>
              {pending?.cancelLabel ?? 'Cancel'}
            </Button>
            <Button type="submit" variant={pending?.tone === 'danger' ? 'danger' : 'primary'} disabled={blocked} loading={busy}>
              {pending?.confirmLabel ?? 'Confirm'}
            </Button>
          </>
        }
      >
        {pending?.body ? <div className="confirm__body">{pending.body}</div> : null}
        {pending?.requireText !== undefined ? (
          <Field label={<>Type <span className="mono">{pending.requireText}</span> to confirm</>}>
            {(control) => (
              <Input {...control} mono autoComplete="off" spellCheck={false} value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus />
            )}
          </Field>
        ) : null}
        {pending?.acknowledge !== undefined ? <Checkbox checked={acked} onChange={setAcked} label={pending.acknowledge} /> : null}
        {error ? (
          <Callout tone="danger" title="Not completed">
            {error}
          </Callout>
        ) : null}
      </Dialog>
    </ConfirmContext.Provider>
  )
}

// eslint-disable-next-line react/only-export-components
export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext)
  if (!ctx) throw new Error('useConfirm must be used inside ConfirmProvider')
  return ctx
}
