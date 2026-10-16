import { CircleAlert, CircleCheck } from 'lucide-react'
import type { ReactNode } from 'react'
import { formatLatency } from '../../lib/format'
import { cx, stringifyDetails } from '../../lib/util'
import type { HealthResult, JsonObject } from '../../types'
import { Lamp } from '../ui/Feedback'

export function HealthLamp({ health, unknownLabel = 'Not checked' }: { health: HealthResult | null | undefined; unknownLabel?: string }) {
  if (!health) return <Lamp state="idle" label={unknownLabel} />
  return <Lamp state={health.healthy ? 'ok' : 'down'} label={health.healthy ? 'Healthy' : 'Down'} />
}

export function DetailsList({ details }: { details: JsonObject | null | undefined }) {
  const entries = Object.entries(details ?? {})
  if (entries.length === 0) return null
  return (
    <dl className="details-list">
      {entries.map(([key, value]) => (
        <div key={key} className="details-list__row">
          <dt>{key}</dt>
          <dd className="mono">{typeof value === 'object' && value !== null ? stringifyDetails(value) : String(value)}</dd>
        </div>
      ))}
    </dl>
  )
}

/** Inline result of POST …/test. */
export function TestResult({ result, title }: { result: HealthResult; title?: ReactNode }) {
  const Icon = result.healthy ? CircleCheck : CircleAlert
  return (
    // A live status region with block content (<output> may only hold phrasing content).
    // oxlint-disable-next-line jsx-a11y/prefer-tag-over-role
    <div className={cx('test-result', result.healthy ? 'is-ok' : 'is-down')} role="status">
      <div className="test-result__head">
        <Icon size={16} aria-hidden />
        <span className="test-result__title">{title ?? (result.healthy ? 'Connection works' : 'Connection failed')}</span>
        {result.latencyMs !== null && result.latencyMs !== undefined ? (
          <span className="test-result__latency tabular">{formatLatency(result.latencyMs)}</span>
        ) : null}
      </div>
      {result.message ? <p className="test-result__message">{result.message}</p> : null}
      <DetailsList details={result.details} />
    </div>
  )
}
