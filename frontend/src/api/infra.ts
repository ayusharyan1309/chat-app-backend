import { adminHttp, seg } from './http'
import type {
  ConnectorConfigRequest,
  ConnectorKind,
  ConnectorKindDescriptor,
  ConnectorState,
  HealthResult,
} from '../types'

export const connectorsApi = {
  /** Catalogue of every pluggable slot and its providers (with field schemas). */
  catalogue: () => adminHttp.get<ConnectorKindDescriptor[]>('/api/admin/connectors').then((r) => r.data),
}

export const infraApi = {
  list: () => adminHttp.get<ConnectorState[]>('/api/admin/infrastructure').then((r) => r.data),
  get: (kind: ConnectorKind) => adminHttp.get<ConnectorState>(`/api/admin/infrastructure/${seg(kind)}`).then((r) => r.data),
  /** Connects first; 400 with a message on failure, nothing changes. */
  apply: (kind: ConnectorKind, body: ConnectorConfigRequest) =>
    adminHttp.put<ConnectorState>(`/api/admin/infrastructure/${seg(kind)}`, body, { timeout: 90_000 }).then((r) => r.data),
  test: (kind: ConnectorKind, body: ConnectorConfigRequest) =>
    adminHttp.post<HealthResult>(`/api/admin/infrastructure/${seg(kind)}/test`, body, { timeout: 90_000 }).then((r) => r.data),
  /** Reverts to the bootstrap configuration (application.yml / env). */
  reset: (kind: ConnectorKind) =>
    adminHttp.delete<ConnectorState>(`/api/admin/infrastructure/${seg(kind)}`).then((r) => r.data),
}
