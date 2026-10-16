import type { ConfigSource, ConnectorKind } from './admin'
import type { IsoDateTime, JsonObject } from './common'

/** The value the backend uses for every stored secret; sending it back keeps the stored value. */
export const SECRET_MASK = '********'

export type FieldType = 'STRING' | 'SECRET' | 'INTEGER' | 'BOOLEAN' | 'SELECT' | 'TEXTAREA' | 'URL'

export type FieldValue = string | number | boolean | null

export interface ConnectorField {
  key: string
  label: string
  type: FieldType
  required: boolean
  defaultValue: FieldValue
  description: string | null
  placeholder: string | null
  options: string[] | null
}

/** Free text from the backend: "GA", "BETA", "PREVIEW", "EXPERIMENTAL", ... */
export type ProviderMaturity = string

export interface ProviderDescriptor {
  id: string
  displayName: string
  description: string | null
  category: string | null
  maturity: ProviderMaturity | null
  compatibleWith: string[]
  fields: ConnectorField[]
}

export interface ConnectorKindDescriptor {
  kind: ConnectorKind
  displayName: string
  description: string | null
  tenantOverridable: boolean
  providers: ProviderDescriptor[]
}

export type ConnectorSettings = Record<string, FieldValue>

export interface HealthResult {
  healthy: boolean
  message: string | null
  latencyMs: number | null
  details: JsonObject | null
}

export interface ConnectorState {
  kind: ConnectorKind
  provider: string
  settings: ConnectorSettings
  source: ConfigSource
  version: number | null
  updatedAt: IsoDateTime | null
  updatedBy: string | null
  health: HealthResult | null
  lastError: string | null
}

export interface ConnectorConfigRequest {
  provider: string
  settings: ConnectorSettings
}

/** Tenant override kinds (`message-store`, `push`). */
export type TenantConnectorKind = 'message-store' | 'push'

export type TenantConnectorState =
  | { inherited: true }
  | {
      inherited: false
      provider: string
      settings: ConnectorSettings
      health: HealthResult | null
    }
