import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { tenantAuthApi } from '../../api/tenants'
import { ConnectorForm } from '../../components/connectors/ConnectorForm'
import { initialValues, serializeValues, validateValues, type FormErrors, type FormValues } from '../../components/connectors/schema'
import { Card } from '../../components/ui/Card'
import { Callout, LoadError, SkeletonLines } from '../../components/ui/Feedback'
import { Field, FormSection, Input, Toggle } from '../../components/ui/Form'
import { Segmented } from '../../components/ui/Misc'
import { SaveBar } from '../../components/ui/SaveBar'
import { useAction } from '../../hooks/useAction'
import { useApi } from '../../hooks/useApi'
import type { ConnectorField, ConnectorSettings, ExternalAuthConfig, ExternalAuthType, TenantAuthConfig } from '../../types'
import { featureEnabled, tenantPath, type TenantTabProps } from './types'

function field(key: string, label: string, type: ConnectorField['type'], extra: Partial<ConnectorField> = {}): ConnectorField {
  return { key, label, type, required: false, defaultValue: null, description: null, placeholder: null, options: null, ...extra }
}

/** External auth settings rendered with the same schema-driven form as connectors. */
const EXTERNAL_FIELDS: Record<Exclude<ExternalAuthType, 'none'>, ConnectorField[]> = {
  firebase: [
    field('firebaseProjectId', 'Firebase project ID', 'STRING', {
      required: true,
      placeholder: 'acme-chat-prod',
      description: 'ID tokens from this project are verified and exchanged for platform tokens.',
    }),
  ],
  jwt: [
    field('jwtIssuer', 'Issuer', 'STRING', { placeholder: 'https://auth.acme.io/', description: 'Expected iss claim.' }),
    field('jwtAudience', 'Audience', 'STRING', { placeholder: 'chat', description: 'Expected aud claim.' }),
    field('jwksUrl', 'JWKS URL', 'URL', {
      placeholder: 'https://auth.acme.io/.well-known/jwks.json',
      description: 'For RS256/ES256 tokens. Leave empty when using a shared secret.',
    }),
    field('jwtSecret', 'Shared secret', 'SECRET', { description: 'For HS256 tokens. Leave empty when using a JWKS URL.' }),
    field('userIdClaim', 'User ID claim', 'STRING', { defaultValue: 'sub', placeholder: 'sub' }),
    field('nameClaim', 'Name claim', 'STRING', { defaultValue: 'name', placeholder: 'name' }),
    field('emailClaim', 'Email claim', 'STRING', { defaultValue: 'email', placeholder: 'email' }),
  ],
}

const ALL_EXTERNAL_KEYS = ['firebaseProjectId', 'jwtIssuer', 'jwtAudience', 'jwksUrl', 'jwtSecret', 'userIdClaim', 'nameClaim', 'emailClaim'] as const

function externalToSettings(external: ExternalAuthConfig): ConnectorSettings {
  const settings: ConnectorSettings = {}
  for (const key of ALL_EXTERNAL_KEYS) {
    const v = external[key]
    if (v !== undefined && v !== null) settings[key] = v
  }
  return settings
}

interface Draft {
  devLoginEnabled: boolean
  tokenTtlMinutes: string
  type: ExternalAuthType
  values: Record<Exclude<ExternalAuthType, 'none'>, FormValues>
}

function toDraft(config: TenantAuthConfig): Draft {
  const settings = externalToSettings(config.external)
  return {
    devLoginEnabled: config.devLoginEnabled,
    tokenTtlMinutes: String(config.tokenTtlMinutes),
    type: config.external.type ?? 'none',
    values: {
      firebase: initialValues(EXTERNAL_FIELDS.firebase, settings),
      jwt: initialValues(EXTERNAL_FIELDS.jwt, settings),
    },
  }
}

function AuthEditor({ config, slug, externalAllowed, canWrite, onSaved }: { config: TenantAuthConfig; slug: string; externalAllowed: boolean; canWrite: boolean; onSaved: (c: TenantAuthConfig) => void }) {
  const initial = useMemo(() => toDraft(config), [config])
  const [draft, setDraft] = useState<Draft>(initial)
  const [errors, setErrors] = useState<FormErrors>({})

  const dirty = JSON.stringify(draft) !== JSON.stringify(initial)
  const ttl = Number(draft.tokenTtlMinutes)
  const ttlInvalid = !/^\d+$/.test(draft.tokenTtlMinutes) || ttl < 1

  const save = useAction(
    async () => {
      const external: ExternalAuthConfig = { type: draft.type }
      if (draft.type !== 'none') {
        const fields = EXTERNAL_FIELDS[draft.type]
        const found = validateValues(fields, draft.values[draft.type])
        if (Object.keys(found).length > 0) {
          setErrors(found)
          throw new Error('Fill in the required external auth settings.')
        }
        Object.assign(external, serializeValues(fields, draft.values[draft.type]))
      }
      const next = await tenantAuthApi.update(slug, { devLoginEnabled: draft.devLoginEnabled, tokenTtlMinutes: ttl, external })
      onSaved(next)
      return next
    },
    { success: 'Authentication settings saved' },
  )

  const setType = (type: ExternalAuthType) => {
    setDraft((d) => ({ ...d, type }))
    setErrors({})
  }

  return (
    <>
      <Card>
        <FormSection title="Platform tokens" description="Tokens your backend mints with its API key, and the development shortcut.">
          <div className="stack">
            <Toggle
              checked={draft.devLoginEnabled}
              onChange={(v) => setDraft((d) => ({ ...d, devLoginEnabled: v }))}
              disabled={!canWrite}
              label="Allow dev login"
              description="Any client can sign in as any user ID with POST /api/v1/auth/dev-login. Use only for development tenants."
            />
            {draft.devLoginEnabled ? <Callout tone="warn">Dev login trusts the caller completely. Turn it off for production tenants.</Callout> : null}
            <Field label="Token lifetime" hint="Minutes until user tokens issued by the platform expire." error={ttlInvalid ? 'Enter a whole number of minutes, at least 1.' : undefined}>
              {(c) => (
                <Input
                  {...c}
                  type="number"
                  min={1}
                  inputMode="numeric"
                  value={draft.tokenTtlMinutes}
                  onChange={(e) => setDraft((d) => ({ ...d, tokenTtlMinutes: e.target.value }))}
                  disabled={!canWrite}
                  className="input--narrow"
                />
              )}
            </Field>
          </div>
        </FormSection>

        <FormSection
          title="External identity"
          description="Let client apps sign in with tokens from your own identity provider. They are exchanged for platform tokens at /api/v1/auth/exchange and users are created on first sign-in."
        >
          <div className="stack">
            <Segmented
              label="External identity provider"
              value={draft.type}
              onChange={setType}
              options={[
                { value: 'none', label: 'None' },
                { value: 'firebase', label: 'Firebase' },
                { value: 'jwt', label: 'Your own JWT' },
              ]}
            />
            {draft.type !== 'none' && !externalAllowed ? (
              <Callout tone="warn" title="External auth is not enabled for this tenant">
                Turn on the external auth feature on the <Link to={tenantPath(slug, 'features')}>Features tab</Link> first, or the backend will reject
                this change.
              </Callout>
            ) : null}
            {draft.type === 'none' ? <p className="secondary">Only platform tokens{draft.devLoginEnabled ? ' and dev login' : ''} are accepted.</p> : null}
            {draft.type !== 'none' ? (
              <ConnectorForm
                key={draft.type}
                idPrefix={`auth-${draft.type}`}
                fields={EXTERNAL_FIELDS[draft.type]}
                values={draft.values[draft.type]}
                baseline={initial.type === draft.type ? initial.values[draft.type] : undefined}
                errors={errors}
                disabled={!canWrite}
                onChange={(key, value) => {
                  const type = draft.type
                  if (type === 'none') return
                  setDraft((d) => ({ ...d, values: { ...d.values, [type]: { ...d.values[type], [key]: value } } }))
                  setErrors((e) => {
                    const next = { ...e }
                    delete next[key]
                    return next
                  })
                }}
              />
            ) : null}
          </div>
        </FormSection>
      </Card>
      <SaveBar dirty={dirty} saving={save.busy} onSave={() => void save.run()} onDiscard={() => setDraft(initial)} disabled={ttlInvalid || !canWrite} />
    </>
  )
}

export function AuthTab({ tenant, canWrite }: TenantTabProps) {
  const auth = useApi(() => tenantAuthApi.get(tenant.slug), `auth:${tenant.slug}`)
  if (auth.error) return <LoadError error={auth.error} onRetry={() => void auth.reload()} />
  if (!auth.data) return <SkeletonLines lines={6} />
  return (
    <AuthEditor
      key={JSON.stringify(auth.data)}
      config={auth.data}
      slug={tenant.slug}
      externalAllowed={featureEnabled(tenant, 'external-auth')}
      canWrite={canWrite}
      onSaved={(c) => auth.setData(c)}
    />
  )
}
