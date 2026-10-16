import { Check, Copy, Eye, EyeOff, KeyRound } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useClipboard } from '../../hooks/useClipboard'
import { cx } from '../../lib/util'
import { Button } from './Button'
import { Callout } from './Feedback'

export function CopyButton({ value, label = 'Copy', size = 'sm', variant = 'secondary' }: { value: string; label?: string; size?: 'sm' | 'md'; variant?: 'secondary' | 'ghost' | 'primary' }) {
  const { copied, copy } = useClipboard()
  return (
    <Button size={size} variant={variant} onClick={() => void copy(value)} icon={copied ? <Check size={14} aria-hidden /> : <Copy size={14} aria-hidden />} aria-live="polite">
      {copied ? 'Copied' : label}
    </Button>
  )
}

/** Inline mono value with a compact copy affordance (ids, prefixes, URLs). */
export function CopyText({ value, display, className }: { value: string; display?: ReactNode; className?: string }) {
  const { copied, copy } = useClipboard()
  return (
    <span className={cx('copy-text', className)}>
      <span className="mono copy-text__value">{display ?? value}</span>
      <button type="button" className="copy-text__btn" onClick={() => void copy(value)} aria-label={copied ? 'Copied' : `Copy ${value}`} title={copied ? 'Copied' : 'Copy'}>
        {copied ? <Check size={12} aria-hidden /> : <Copy size={12} aria-hidden />}
      </button>
    </span>
  )
}

/**
 * One-time secret reveal (API keys, webhook secrets). Large mono value, copy button, and a clear
 * warning that it will not be shown again.
 */
export function SecretReveal({ value, label, note }: { value: string; label: string; note?: ReactNode }) {
  const [visible, setVisible] = useState(true)
  return (
    <div className="secret-reveal">
      <div className="secret-reveal__head">
        <KeyRound size={16} aria-hidden />
        <span className="secret-reveal__label">{label}</span>
      </div>
      <div className="secret-reveal__value-row">
        <code className={cx('secret-reveal__value', !visible && 'is-masked')} aria-label={label}>
          {visible ? value : '•'.repeat(Math.min(value.length, 40))}
        </code>
        <div className="secret-reveal__actions">
          <Button size="sm" variant="ghost" onClick={() => setVisible((v) => !v)} icon={visible ? <EyeOff size={14} aria-hidden /> : <Eye size={14} aria-hidden />}>
            {visible ? 'Hide' : 'Show'}
          </Button>
          <CopyButton value={value} variant="primary" />
        </div>
      </div>
      <Callout tone="warn" title="Copy it now. It will not be shown again.">
        {note ?? 'Store it in your secret manager. If it is lost, create a new one and revoke this one.'}
      </Callout>
    </div>
  )
}
