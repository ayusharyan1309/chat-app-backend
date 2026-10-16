import { cx } from '../../lib/util'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost'
export type ButtonSize = 'sm' | 'md'

export function buttonClass(variant: ButtonVariant = 'secondary', size: ButtonSize = 'md', extra?: string): string {
  return cx('btn', `btn--${variant}`, size === 'sm' && 'btn--sm', extra)
}
