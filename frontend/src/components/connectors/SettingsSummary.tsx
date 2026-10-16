import type { ConnectorField, ConnectorSettings } from '../../types'
import { displaySetting } from './schema'

/** Read-only view of the active settings, labelled from the provider schema when available. */
export function SettingsSummary({ settings, fields }: { settings: ConnectorSettings; fields: ConnectorField[] | undefined }) {
  const keys = Object.keys(settings)
  if (keys.length === 0) return <p className="muted settings-summary__none">No settings. Defaults are in use.</p>
  const byKey = new Map((fields ?? []).map((f) => [f.key, f]))
  // Schema order first, then any extra keys the backend returned.
  const ordered = [...(fields ?? []).map((f) => f.key).filter((k) => keys.includes(k)), ...keys.filter((k) => !byKey.has(k))]
  return (
    <dl className="settings-summary">
      {ordered.map((key) => {
        const field = byKey.get(key)
        return (
          <div className="settings-summary__row" key={key}>
            <dt title={key}>{field?.label ?? key}</dt>
            <dd className={field?.type === 'SECRET' ? 'settings-summary__secret' : 'mono'}>{displaySetting(field, settings[key])}</dd>
          </div>
        )
      })}
    </dl>
  )
}
