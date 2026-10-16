import { Check } from 'lucide-react'
import { useRef, type KeyboardEvent } from 'react'
import { cx } from '../../lib/util'
import type { ProviderDescriptor } from '../../types'
import { Badge } from '../ui/Feedback'
import { maturityLabel, maturityTone } from './labels'

export function MaturityBadge({ maturity }: { maturity: string | null | undefined }) {
  return (
    <Badge tone={maturityTone(maturity)} title="Maturity">
      {maturityLabel(maturity)}
    </Badge>
  )
}

/**
 * Provider cards as a radio group. Arrow keys move between providers; each card shows display name,
 * category, maturity, compatible products and description.
 */
export function ProviderPicker({
  providers,
  value,
  onChange,
  activeProvider,
  label,
  disabled,
}: {
  providers: ProviderDescriptor[]
  value: string | null
  onChange: (id: string) => void
  activeProvider?: string | null
  label: string
  disabled?: boolean
}) {
  const refs = useRef<Array<HTMLButtonElement | null>>([])

  const onKeyDown = (event: KeyboardEvent, index: number) => {
    const delta = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1 : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 0
    if (!delta) return
    event.preventDefault()
    const next = (index + delta + providers.length) % providers.length
    const provider = providers[next]
    if (!provider) return
    onChange(provider.id)
    refs.current[next]?.focus()
  }

  const selectedIndex = providers.findIndex((p) => p.id === value)

  return (
    <div className="provider-picker" role="radiogroup" aria-label={label}>
      {providers.map((provider, index) => {
        const selected = provider.id === value
        const active = provider.id === activeProvider
        return (
          <button
            key={provider.id}
            ref={(el) => {
              refs.current[index] = el
            }}
            type="button"
            // ARIA radio group with roving tabindex (WAI-ARIA APG): each option is a rich card.
            // oxlint-disable-next-line jsx-a11y/prefer-tag-over-role
            role="radio"
            aria-checked={selected}
            tabIndex={selected || (selectedIndex === -1 && index === 0) ? 0 : -1}
            disabled={disabled}
            className={cx('provider-card', selected && 'is-selected')}
            onClick={() => onChange(provider.id)}
            onKeyDown={(e) => onKeyDown(e, index)}
          >
            <span className="provider-card__top">
              <span className="provider-card__radio" aria-hidden>
                {selected ? <Check size={11} strokeWidth={3} /> : null}
              </span>
              <span className="provider-card__name">{provider.displayName}</span>
              <MaturityBadge maturity={provider.maturity} />
            </span>
            <span className="provider-card__meta">
              {provider.category ? <span>{provider.category}</span> : null}
              <span className="mono provider-card__id">{provider.id}</span>
              {active ? (
                <Badge tone="accent" className="provider-card__active">
                  Active
                </Badge>
              ) : null}
            </span>
            {provider.description ? <span className="provider-card__desc">{provider.description}</span> : null}
            {provider.compatibleWith.length > 0 ? (
              <span className="provider-card__compat">
                <span className="provider-card__compat-label">Works with</span>
                {provider.compatibleWith.map((name) => (
                  <span key={name} className="chip">
                    {name}
                  </span>
                ))}
              </span>
            ) : null}
          </button>
        )
      })}
    </div>
  )
}
