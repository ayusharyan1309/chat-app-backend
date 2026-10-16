import { Check, ChevronDown, Search, X } from 'lucide-react'
import {
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { cx } from '../../lib/util'

/* ----------------------------------------------------------------- Field ---- */

export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
  required?: boolean
}

export interface FieldProps {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  required?: boolean
  /** Extra content on the label row (e.g. a "Stored" badge). */
  aside?: ReactNode
  className?: string
  /** Render-prop receives id + aria wiring for the control. */
  children: (control: FieldControlProps) => ReactNode
}

/** Label, help text and error wired to a single control via a render prop. */
export function Field({ label, hint, error, required, aside, className, children }: FieldProps) {
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [errorId, hintId].filter(Boolean).join(' ') || undefined
  return (
    <div className={cx('field', error ? 'field--invalid' : undefined, className)}>
      <div className="field__label-row">
        <label className="field__label" htmlFor={id}>
          {label}
          {required ? (
            <span className="field__required" aria-hidden>
              *
            </span>
          ) : null}
          {required ? <span className="sr-only"> (required)</span> : null}
        </label>
        {aside ? <span className="field__aside">{aside}</span> : null}
      </div>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined, required })}
      {error ? (
        <p className="field__error" id={errorId}>
          {error}
        </p>
      ) : null}
      {hint ? (
        <p className="field__hint" id={hintId}>
          {hint}
        </p>
      ) : null}
    </div>
  )
}

/* ---------------------------------------------------------------- Inputs ---- */

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  mono?: boolean
  /** Element rendered inside the input on the right (e.g. a show/hide button). */
  suffix?: ReactNode
  prefixIcon?: ReactNode
}

export function Input({ mono, suffix, prefixIcon, className, ...rest }: InputProps) {
  if (!suffix && !prefixIcon) {
    return <input className={cx('input', mono && 'input--mono', className)} {...rest} />
  }
  return (
    <div className={cx('input-wrap', prefixIcon ? 'input-wrap--prefix' : undefined, suffix ? 'input-wrap--suffix' : undefined, className)}>
      {prefixIcon ? <span className="input-wrap__prefix">{prefixIcon}</span> : null}
      <input className={cx('input', mono && 'input--mono')} {...rest} />
      {suffix ? <span className="input-wrap__suffix">{suffix}</span> : null}
    </div>
  )
}

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  mono?: boolean
}

export function Textarea({ mono, className, rows = 4, ...rest }: TextareaProps) {
  return <textarea className={cx('input', 'textarea', mono && 'input--mono', className)} rows={rows} {...rest} />
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className={cx('select', className)}>
      <select className="input select__control" {...rest}>
        {children}
      </select>
      <ChevronDown className="select__chevron" size={14} aria-hidden />
    </div>
  )
}

export interface SearchInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> {
  value: string
  onChange: (value: string) => void
  label: string
}

export function SearchInput({ value, onChange, label, className, placeholder, ...rest }: SearchInputProps) {
  return (
    <Input
      type="search"
      className={cx('search-input', className)}
      prefixIcon={<Search size={14} aria-hidden />}
      aria-label={label}
      placeholder={placeholder ?? label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && value) {
          e.preventDefault()
          onChange('')
        }
      }}
      suffix={
        value ? (
          <button type="button" className="input-clear" onClick={() => onChange('')} aria-label="Clear search">
            <X size={13} aria-hidden />
          </button>
        ) : undefined
      }
      {...rest}
    />
  )
}

/* ---------------------------------------------------------------- Toggle ---- */

export interface ToggleProps {
  checked: boolean
  onChange: (checked: boolean) => void
  /** Visible label rendered next to the switch. Omit and pass aria-label when labelled elsewhere. */
  label?: ReactNode
  description?: ReactNode
  disabled?: boolean
  id?: string
  'aria-label'?: string
  'aria-describedby'?: string
  size?: 'sm' | 'md'
  className?: string
}

/** Accessible switch (`role="switch"`). */
export function Toggle({
  checked,
  onChange,
  label,
  description,
  disabled,
  id,
  size = 'md',
  className,
  ...aria
}: ToggleProps) {
  const autoId = useId()
  const switchId = id ?? autoId
  const descId = description ? `${switchId}-desc` : undefined
  const button = (
    <button
      type="button"
      role="switch"
      id={switchId}
      aria-checked={checked}
      aria-label={aria['aria-label']}
      aria-describedby={[aria['aria-describedby'], descId].filter(Boolean).join(' ') || undefined}
      disabled={disabled}
      className={cx('toggle', size === 'sm' && 'toggle--sm', checked && 'is-on')}
      onClick={() => onChange(!checked)}
    >
      <span className="toggle__thumb" />
    </button>
  )
  if (!label) return button
  return (
    <div className={cx('toggle-row', disabled && 'is-disabled', className)}>
      {button}
      <div className="toggle-row__text">
        <label className="toggle-row__label" htmlFor={switchId}>
          {label}
        </label>
        {description ? (
          <p className="toggle-row__desc" id={descId}>
            {description}
          </p>
        ) : null}
      </div>
    </div>
  )
}

/* -------------------------------------------------------------- Checkbox ---- */

export interface CheckboxProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label: ReactNode
  description?: ReactNode
  disabled?: boolean
  className?: string
}

export function Checkbox({ checked, onChange, label, description, disabled, className }: CheckboxProps) {
  const id = useId()
  return (
    <div className={cx('checkbox', disabled && 'is-disabled', className)}>
      <span className="checkbox__box">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          aria-describedby={description ? `${id}-desc` : undefined}
        />
        <Check className="checkbox__tick" size={12} strokeWidth={3} aria-hidden />
      </span>
      <span className="checkbox__text">
        <label htmlFor={id} className="checkbox__label">
          {label}
        </label>
        {description ? (
          <span className="checkbox__desc" id={`${id}-desc`}>
            {description}
          </span>
        ) : null}
      </span>
    </div>
  )
}

/* ---------------------------------------------------------- Form helpers ---- */

export function FormGrid({ children, columns = 2, className }: { children: ReactNode; columns?: 1 | 2 | 3; className?: string }) {
  return <div className={cx('form-grid', `form-grid--${columns}`, className)}>{children}</div>
}

export function FormSection({
  title,
  description,
  children,
  actions,
}: {
  title: ReactNode
  description?: ReactNode
  children: ReactNode
  actions?: ReactNode
}) {
  return (
    <section className="form-section">
      <header className="form-section__head">
        <div>
          <h3 className="form-section__title">{title}</h3>
          {description ? <p className="form-section__desc">{description}</p> : null}
        </div>
        {actions ? <div className="form-section__actions">{actions}</div> : null}
      </header>
      <div className="form-section__body">{children}</div>
    </section>
  )
}
