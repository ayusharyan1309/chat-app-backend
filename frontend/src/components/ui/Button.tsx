import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { cx } from '../../lib/util'
import { buttonClass, type ButtonSize, type ButtonVariant } from './classes'
import { Spinner } from './Feedback'


export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  icon?: ReactNode
  /** Trailing element, e.g. a count or chevron. */
  trailing?: ReactNode
}

export function Button({
  variant = 'secondary',
  size = 'md',
  loading = false,
  icon,
  trailing,
  className,
  disabled,
  children,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={buttonClass(variant, size, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size={size === 'sm' ? 12 : 14} /> : icon}
      {children !== undefined && children !== null && children !== false ? <span className="btn__label">{children}</span> : null}
      {trailing}
    </button>
  )
}

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Required accessible name. */
  label: string
  icon: ReactNode
  variant?: 'ghost' | 'secondary' | 'danger-ghost'
  size?: ButtonSize
  active?: boolean
}

export function IconButton({ label, icon, variant = 'ghost', size = 'md', active, className, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button
      type={type}
      className={cx('btn', `btn--${variant}`, 'btn--icon', size === 'sm' && 'btn--sm', active && 'is-active', className)}
      aria-label={label}
      title={label}
      {...rest}
    >
      {icon}
    </button>
  )
}
