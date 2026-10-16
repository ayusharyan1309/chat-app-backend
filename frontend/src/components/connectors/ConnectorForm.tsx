import { Eye, EyeOff, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { cx } from '../../lib/util'
import { SECRET_MASK, type ConnectorField } from '../../types'
import { Badge } from '../ui/Feedback'
import { Field, Input, Select, Textarea, Toggle, type FieldControlProps } from '../ui/Form'
import { isStoredSecret, isWideField, type FormErrors, type FormValues } from './schema'

export interface ConnectorFormProps {
  fields: ConnectorField[]
  values: FormValues
  onChange: (key: string, value: string | boolean) => void
  /** Values the form started from; used for "stored secret" handling and change markers. */
  baseline?: FormValues
  errors?: FormErrors
  disabled?: boolean
  /** Prefix for DOM ids so several forms can coexist on a page. */
  idPrefix?: string
}

function SecretInput({
  control,
  field,
  value,
  stored,
  onChange,
  onRestore,
  disabled,
}: {
  control: FieldControlProps
  field: ConnectorField
  value: string
  stored: boolean
  onChange: (value: string) => void
  onRestore: (() => void) | null
  disabled?: boolean
}) {
  const [visible, setVisible] = useState(false)
  const showingMask = value === SECRET_MASK
  return (
    <Input
      {...control}
      type={visible ? 'text' : 'password'}
      mono
      autoComplete="new-password"
      spellCheck={false}
      disabled={disabled}
      value={showingMask ? '' : value}
      placeholder={stored ? 'Stored. Type to replace it.' : (field.placeholder ?? '')}
      onChange={(e) => onChange(e.target.value)}
      suffix={
        <>
          {onRestore ? (
            <button type="button" className="input-reveal" onClick={onRestore} aria-label="Keep the stored secret" title="Keep the stored secret">
              <RotateCcw size={14} aria-hidden />
            </button>
          ) : null}
          <button
            type="button"
            className="input-reveal"
            onClick={() => setVisible((v) => !v)}
            aria-label={visible ? `Hide ${field.label}` : `Show ${field.label}`}
            aria-pressed={visible}
            disabled={showingMask}
          >
            {visible ? <EyeOff size={15} aria-hidden /> : <Eye size={15} aria-hidden />}
          </button>
        </>
      }
    />
  )
}

/**
 * Renders a provider's configuration form entirely from its `fields` schema
 * (`GET /api/admin/connectors`). Used for platform infrastructure and tenant overrides alike.
 */
export function ConnectorForm({ fields, values, onChange, baseline, errors = {}, disabled, idPrefix = 'cf' }: ConnectorFormProps) {
  if (fields.length === 0) {
    return <p className="connector-form__none">This provider has no settings. Apply it as is.</p>
  }

  return (
    <div className="connector-form">
      {fields.map((field) => {
        const value = values[field.key]
        const base = baseline?.[field.key]
        const changed = baseline !== undefined && base !== value
        const storedSecret = isStoredSecret(field, base)
        const wide = isWideField(field)
        const aside = (
          <>
            {storedSecret && value === SECRET_MASK ? <Badge tone="ok">Stored</Badge> : null}
            {changed ? <span className="connector-form__changed">Changed</span> : null}
          </>
        )

        if (field.type === 'BOOLEAN') {
          return (
            <div key={field.key} className={cx('connector-form__item', 'span-all')} data-field={`${idPrefix}-${field.key}`}>
              <Toggle
                checked={value === true}
                onChange={(next) => onChange(field.key, next)}
                disabled={disabled}
                label={
                  <>
                    {field.label}
                    {changed ? <span className="connector-form__changed"> Changed</span> : null}
                  </>
                }
                description={field.description ?? undefined}
              />
            </div>
          )
        }

        return (
          <div key={field.key} className={cx('connector-form__item', wide && 'span-all')} data-field={`${idPrefix}-${field.key}`}>
            <Field
              label={field.label}
              required={field.required}
              hint={
                field.type === 'SECRET' && storedSecret
                  ? `${field.description ? `${field.description} ` : ''}Leave empty to keep the stored value.`
                  : (field.description ?? undefined)
              }
              error={errors[field.key]}
              aside={aside}
            >
              {(control) => {
                const text = typeof value === 'string' ? value : ''
                switch (field.type) {
                  case 'SECRET':
                    return (
                      <SecretInput
                        control={control}
                        field={field}
                        value={text}
                        stored={storedSecret}
                        disabled={disabled}
                        onChange={(next) => onChange(field.key, next === '' && storedSecret ? SECRET_MASK : next)}
                        onRestore={storedSecret && text !== SECRET_MASK ? () => onChange(field.key, SECRET_MASK) : null}
                      />
                    )
                  case 'INTEGER':
                    return (
                      <Input
                        {...control}
                        type="number"
                        inputMode="numeric"
                        step={1}
                        disabled={disabled}
                        value={text}
                        placeholder={field.placeholder ?? undefined}
                        onChange={(e) => onChange(field.key, e.target.value)}
                      />
                    )
                  case 'SELECT':
                    return (
                      <Select {...control} disabled={disabled} value={text} onChange={(e) => onChange(field.key, e.target.value)}>
                        {!field.required || text === '' ? <option value="">{field.placeholder ?? 'Not set'}</option> : null}
                        {(field.options ?? []).map((option) => (
                          <option key={option} value={option}>
                            {option}
                          </option>
                        ))}
                      </Select>
                    )
                  case 'TEXTAREA':
                    return (
                      <Textarea
                        {...control}
                        mono
                        rows={5}
                        spellCheck={false}
                        disabled={disabled}
                        value={text}
                        placeholder={field.placeholder ?? undefined}
                        onChange={(e) => onChange(field.key, e.target.value)}
                      />
                    )
                  case 'URL':
                    return (
                      <Input
                        {...control}
                        type="text"
                        inputMode="url"
                        mono
                        spellCheck={false}
                        autoComplete="off"
                        disabled={disabled}
                        value={text}
                        placeholder={field.placeholder ?? undefined}
                        onChange={(e) => onChange(field.key, e.target.value)}
                      />
                    )
                  case 'STRING':
                  default:
                    return (
                      <Input
                        {...control}
                        type="text"
                        spellCheck={false}
                        autoComplete="off"
                        disabled={disabled}
                        value={text}
                        placeholder={field.placeholder ?? undefined}
                        onChange={(e) => onChange(field.key, e.target.value)}
                      />
                    )
                }
              }}
            </Field>
          </div>
        )
      })}
    </div>
  )
}
