import type { ConfigSource } from '../../types'
import type { Tone } from '../ui/Feedback'

export function maturityTone(maturity: string | null | undefined): Tone {
  switch ((maturity ?? '').toUpperCase()) {
    case 'GA':
    case 'STABLE':
      return 'ok'
    case 'BETA':
      return 'info'
    case 'PREVIEW':
    case 'ALPHA':
    case 'EXPERIMENTAL':
      return 'warn'
    case 'DEPRECATED':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function maturityLabel(maturity: string | null | undefined): string {
  const value = (maturity ?? '').trim()
  if (!value) return 'Unrated'
  if (value.toUpperCase() === 'GA') return 'GA'
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase()
}

export function sourceLabel(source: ConfigSource | null | undefined): string {
  return source === 'database' ? 'Set in console' : 'Bootstrap config'
}
