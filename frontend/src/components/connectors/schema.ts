import { SECRET_MASK, type ConnectorField, type ConnectorSettings, type FieldValue } from '../../types'

/**
 * Internal form state. Text-like and INTEGER fields hold strings (so "" is representable while
 * editing); BOOLEAN fields hold booleans. Serialisation converts back to the wire types.
 */
export type FormValues = Record<string, string | boolean>

export type FormErrors = Record<string, string>

function toFormValue(field: ConnectorField, raw: FieldValue | undefined): string | boolean {
  if (field.type === 'BOOLEAN') {
    if (typeof raw === 'boolean') return raw
    if (typeof raw === 'string') return raw.toLowerCase() === 'true'
    if (typeof raw === 'number') return raw !== 0
    return false
  }
  if (raw === null || raw === undefined) return ''
  return String(raw)
}

/** Initial values: current settings when editing the active provider, otherwise schema defaults. */
export function initialValues(fields: ConnectorField[], current?: ConnectorSettings | null): FormValues {
  const values: FormValues = {}
  for (const field of fields) {
    const fromCurrent = current && Object.prototype.hasOwnProperty.call(current, field.key) ? current[field.key] : undefined
    let value = toFormValue(field, fromCurrent !== undefined ? fromCurrent : field.defaultValue)
    if (field.type === 'SELECT' && value === '' && field.required && field.options && field.options.length > 0) {
      value = field.options[0] ?? ''
    }
    values[field.key] = value
  }
  return values
}

export function isStoredSecret(field: ConnectorField, value: string | boolean | undefined): boolean {
  return field.type === 'SECRET' && value === SECRET_MASK
}

/** Convert form state to the `settings` map sent to the backend. Empty optional values are omitted. */
export function serializeValues(fields: ConnectorField[], values: FormValues): ConnectorSettings {
  const settings: ConnectorSettings = {}
  for (const field of fields) {
    const value = values[field.key]
    if (field.type === 'BOOLEAN') {
      settings[field.key] = value === true
      continue
    }
    const text = typeof value === 'string' ? value : ''
    if (field.type === 'SECRET') {
      // "********" tells the backend to keep the stored secret.
      if (text === SECRET_MASK) settings[field.key] = SECRET_MASK
      else if (text !== '') settings[field.key] = text
      continue
    }
    const trimmed = field.type === 'TEXTAREA' ? text.replace(/\s+$/, '') : text.trim()
    if (trimmed === '') continue
    if (field.type === 'INTEGER') {
      settings[field.key] = Number(trimmed)
      continue
    }
    settings[field.key] = trimmed
  }
  return settings
}

export function validateValues(fields: ConnectorField[], values: FormValues): FormErrors {
  const errors: FormErrors = {}
  for (const field of fields) {
    const value = values[field.key]
    if (field.type === 'BOOLEAN') continue
    const text = typeof value === 'string' ? value.trim() : ''
    if (text === '') {
      if (field.required) errors[field.key] = `${field.label} is required.`
      continue
    }
    if (field.type === 'SECRET' && text === SECRET_MASK) continue
    if (field.type === 'INTEGER' && !/^-?\d+$/.test(text)) {
      errors[field.key] = 'Enter a whole number.'
    } else if (field.type === 'URL' && /\s/.test(text)) {
      errors[field.key] = 'A URL cannot contain spaces.'
    } else if (field.type === 'SELECT' && field.options && field.options.length > 0 && !field.options.includes(text)) {
      errors[field.key] = 'Pick one of the listed options.'
    }
  }
  return errors
}

export function valuesEqual(fields: ConnectorField[], a: FormValues, b: FormValues): boolean {
  return fields.every((field) => a[field.key] === b[field.key])
}

/** Field types that get the full row in the two-column grid. */
export function isWideField(field: ConnectorField): boolean {
  return field.type === 'TEXTAREA' || field.type === 'URL' || field.type === 'BOOLEAN' || (field.description?.length ?? 0) > 90
}

/** Human summary of a stored settings value for read-only display. */
export function displaySetting(field: ConnectorField | undefined, value: FieldValue | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  if (field?.type === 'SECRET' || value === SECRET_MASK) return 'Stored secret'
  if (typeof value === 'boolean') return value ? 'On' : 'Off'
  const text = String(value)
  return text.length > 80 ? `${text.slice(0, 77)}…` : text
}
