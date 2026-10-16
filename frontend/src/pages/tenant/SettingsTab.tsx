import { useMemo, useState } from 'react'
import { tenantsApi } from '../../api/tenants'
import { Card } from '../../components/ui/Card'
import { Field, FormGrid, FormSection, Input, Toggle } from '../../components/ui/Form'
import { SaveBar } from '../../components/ui/SaveBar'
import { useAction } from '../../hooks/useAction'
import { initials } from '../../lib/format'
import { safeMediaUrl } from '../../lib/mediaUrl'
import type { TenantDetail } from '../../types'
import type { TenantTabProps } from './tenantTab'

const HEX = /^#[0-9a-f]{6}$/i

interface Draft {
  name: string
  contactEmail: string
  domain: string
  displayName: string
  logoUrl: string
  primaryColor: string
  accentColor: string
  maxMessageLength: string
  editWindowMinutes: string
  maxFileSizeMb: string
  allowUserSearch: boolean
}

function toDraft(t: TenantDetail): Draft {
  return {
    name: t.name,
    contactEmail: t.contactEmail ?? '',
    domain: t.domain ?? '',
    displayName: t.branding.displayName ?? '',
    logoUrl: t.branding.logoUrl ?? '',
    primaryColor: t.branding.primaryColor ?? '',
    accentColor: t.branding.accentColor ?? '',
    maxMessageLength: String(t.settings.maxMessageLength),
    editWindowMinutes: String(t.settings.editWindowMinutes),
    maxFileSizeMb: String(t.settings.maxFileSizeMb),
    allowUserSearch: t.settings.allowUserSearch,
  }
}

function ColorField({ label, value, onChange, fallback, disabled, hint }: { label: string; value: string; onChange: (v: string) => void; fallback: string; disabled?: boolean; hint?: string }) {
  const valid = value === '' || HEX.test(value)
  return (
    <Field label={label} hint={hint} error={valid ? undefined : 'Use a 6-digit hex colour like #0B6E4F.'}>
      {(c) => (
        <div className="color-field">
          <input
            type="color"
            className="input color-field__swatch"
            aria-label={`${label} picker`}
            value={HEX.test(value) ? value : fallback}
            onChange={(e) => onChange(e.target.value)}
            disabled={disabled}
          />
          <Input {...c} mono value={value} onChange={(e) => onChange(e.target.value.trim())} placeholder={fallback} disabled={disabled} maxLength={7} />
        </div>
      )}
    </Field>
  )
}

/** Live preview of the tenant's branding as a client app header and two bubbles. */
function BrandPreview({ draft, fallbackName }: { draft: Draft; fallbackName: string }) {
  const primary = HEX.test(draft.primaryColor) ? draft.primaryColor : '#0B6E4F'
  const accent = HEX.test(draft.accentColor) ? draft.accentColor : '#F2A541'
  const name = draft.displayName || fallbackName
  // Only http(s) (or relative) URLs are loaded; anything else falls back to the initials.
  const logo = safeMediaUrl(draft.logoUrl)
  const [logoFailed, setLogoFailed] = useState<string | null>(null)
  const showLogo = logo !== null && logoFailed !== logo
  return (
    <figure className="brand-preview" aria-label="Branding preview">
      <div className="brand-preview__bar" style={{ background: primary }}>
        <span className="brand-preview__logo">
          {showLogo ? <img src={logo} alt="" onError={() => setLogoFailed(logo)} /> : initials(name)}
        </span>
        <span className="brand-preview__name">{name}</span>
      </div>
      <div className="brand-preview__body">
        <span className="brand-preview__bubble brand-preview__bubble--in">Is the new build live?</span>
        <span className="brand-preview__bubble brand-preview__bubble--out" style={{ background: accent }}>
          Shipping now
        </span>
        <span className="brand-preview__chip" style={{ borderColor: primary, color: primary }}>
          Primary
        </span>
        <span className="brand-preview__chip" style={{ borderColor: accent, color: accent }}>
          Accent
        </span>
      </div>
      <figcaption className="muted">Preview of how client apps can apply this branding.</figcaption>
    </figure>
  )
}

export function SettingsTab({ tenant, onTenantChange, canWrite }: TenantTabProps) {
  const initial = useMemo(() => toDraft(tenant), [tenant])
  const [draft, setDraft] = useState<Draft>(initial)
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }))
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial)

  const intError = (raw: string, min: number) => (!/^\d+$/.test(raw) || Number(raw) < min ? `Enter a whole number of at least ${min}.` : undefined)
  const errors = {
    name: !draft.name.trim() ? 'Enter a name.' : undefined,
    logoUrl: draft.logoUrl.trim() && !safeMediaUrl(draft.logoUrl) ? 'Use an http(s) URL.' : undefined,
    primaryColor: draft.primaryColor && !HEX.test(draft.primaryColor) ? 'bad' : undefined,
    accentColor: draft.accentColor && !HEX.test(draft.accentColor) ? 'bad' : undefined,
    maxMessageLength: intError(draft.maxMessageLength, 1),
    editWindowMinutes: intError(draft.editWindowMinutes, 0),
    maxFileSizeMb: intError(draft.maxFileSizeMb, 1),
  }
  const invalid = Object.values(errors).some(Boolean)

  const save = useAction(
    async () => {
      const next = await tenantsApi.update(tenant.slug, {
        name: draft.name.trim(),
        contactEmail: draft.contactEmail.trim() || null,
        domain: draft.domain.trim() || null,
        branding: {
          displayName: draft.displayName.trim() || null,
          logoUrl: draft.logoUrl.trim() || null,
          primaryColor: draft.primaryColor || null,
          accentColor: draft.accentColor || null,
        },
        settings: {
          maxMessageLength: Number(draft.maxMessageLength),
          editWindowMinutes: Number(draft.editWindowMinutes),
          maxFileSizeMb: Number(draft.maxFileSizeMb),
          allowUserSearch: draft.allowUserSearch,
        },
      })
      onTenantChange(next)
      setDraft(toDraft(next))
      return next
    },
    { success: 'Settings saved' },
  )

  const ro = !canWrite

  return (
    <>
      <Card>
        <FormSection title="Profile" description="How the tenant appears in this console.">
          <FormGrid>
            <Field label="Name" required error={errors.name}>
              {(c) => <Input {...c} value={draft.name} onChange={(e) => set({ name: e.target.value })} disabled={ro} />}
            </Field>
            <Field label="Slug" hint="Permanent.">
              {(c) => <Input {...c} mono value={tenant.slug} readOnly />}
            </Field>
            <Field label="Contact email">
              {(c) => <Input {...c} type="email" value={draft.contactEmail} onChange={(e) => set({ contactEmail: e.target.value })} disabled={ro} />}
            </Field>
            <Field label="Domain">
              {(c) => <Input {...c} value={draft.domain} onChange={(e) => set({ domain: e.target.value })} disabled={ro} />}
            </Field>
          </FormGrid>
        </FormSection>

        <FormSection title="Branding" description="Returned to client apps by the public tenant config endpoint so they can theme themselves.">
          <div className="branding-grid">
            <FormGrid>
              <Field label="Display name" hint="Shown to end users. Defaults to the tenant name." className="span-all">
                {(c) => <Input {...c} value={draft.displayName} onChange={(e) => set({ displayName: e.target.value })} placeholder={tenant.name} disabled={ro} />}
              </Field>
              <Field label="Logo URL" className="span-all" error={errors.logoUrl}>
                {(c) => <Input {...c} mono value={draft.logoUrl} onChange={(e) => set({ logoUrl: e.target.value })} placeholder="https://cdn.acme.io/logo.png" disabled={ro} />}
              </Field>
              <ColorField label="Primary colour" value={draft.primaryColor} onChange={(v) => set({ primaryColor: v })} fallback="#0B6E4F" disabled={ro} />
              <ColorField label="Accent colour" value={draft.accentColor} onChange={(v) => set({ accentColor: v })} fallback="#F2A541" disabled={ro} />
            </FormGrid>
            <BrandPreview draft={draft} fallbackName={tenant.name} />
          </div>
        </FormSection>

        <FormSection title="Messaging" description="Limits enforced for every user of this tenant.">
          <div className="stack">
            <FormGrid columns={3}>
              <Field label="Max message length" hint="Characters." error={errors.maxMessageLength}>
                {(c) => <Input {...c} type="number" min={1} value={draft.maxMessageLength} onChange={(e) => set({ maxMessageLength: e.target.value })} disabled={ro} />}
              </Field>
              <Field label="Edit window" hint="Minutes after sending. 0 disables editing." error={errors.editWindowMinutes}>
                {(c) => <Input {...c} type="number" min={0} value={draft.editWindowMinutes} onChange={(e) => set({ editWindowMinutes: e.target.value })} disabled={ro} />}
              </Field>
              <Field label="Max file size" hint="Megabytes per upload." error={errors.maxFileSizeMb}>
                {(c) => <Input {...c} type="number" min={1} value={draft.maxFileSizeMb} onChange={(e) => set({ maxFileSizeMb: e.target.value })} disabled={ro} />}
              </Field>
            </FormGrid>
            <Toggle
              checked={draft.allowUserSearch}
              onChange={(v) => set({ allowUserSearch: v })}
              disabled={ro}
              label="Allow user search"
              description="Users can find each other by name with GET /api/v1/users. Turn off when your app controls who can talk to whom."
            />
          </div>
        </FormSection>
      </Card>
      <SaveBar dirty={dirty} saving={save.busy} onSave={() => void save.run()} onDiscard={() => setDraft(initial)} disabled={invalid || ro} />
    </>
  )
}
