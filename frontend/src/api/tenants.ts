import { adminHttp, query, seg, type RequestOptions } from './http'
import type {
  ApiKey,
  ApiKeyCreated,
  ConnectorConfigRequest,
  CreateTenantRequest,
  CreateTenantResponse,
  HealthResult,
  Page,
  TenantAuthConfig,
  TenantConnectorKind,
  TenantConnectorState,
  TenantDetail,
  TenantFeature,
  TenantQuery,
  TenantSummary,
  UpdateTenantRequest,
  UpsertUserRequest,
  UsagePoint,
  User,
  UserQuery,
  UserToken,
  Webhook,
  WebhookDelivery,
  WebhookInput,
} from '../types'

const base = (slug: string) => `/api/admin/tenants/${seg(slug)}`

export const tenantsApi = {
  list: (params: TenantQuery = {}, options: RequestOptions = {}) =>
    adminHttp
      .get<TenantSummary[]>('/api/admin/tenants', { params: query({ q: params.q, status: params.status }), signal: options.signal })
      .then((r) => r.data),
  create: (body: CreateTenantRequest) =>
    adminHttp.post<CreateTenantResponse>('/api/admin/tenants', body).then((r) => r.data),
  get: (slug: string) => adminHttp.get<TenantDetail>(base(slug)).then((r) => r.data),
  update: (slug: string, body: UpdateTenantRequest) => adminHttp.put<TenantDetail>(base(slug), body).then((r) => r.data),
  suspend: (slug: string) => adminHttp.post<TenantDetail>(`${base(slug)}/suspend`).then((r) => r.data),
  activate: (slug: string) => adminHttp.post<TenantDetail>(`${base(slug)}/activate`).then((r) => r.data),
  remove: (slug: string) => adminHttp.delete<void>(base(slug)).then(() => undefined),
  /** Full replacement of the override map; omitted ids inherit the plan. */
  setFeatures: (slug: string, overrides: Record<string, boolean>) =>
    adminHttp.put<TenantFeature[]>(`${base(slug)}/features`, { overrides }).then((r) => r.data),
  usage: (slug: string, days = 30) =>
    adminHttp.get<UsagePoint[]>(`${base(slug)}/usage`, { params: { days } }).then((r) => r.data),
}

export const tenantConnectorsApi = {
  get: (slug: string, kind: TenantConnectorKind) =>
    adminHttp.get<TenantConnectorState>(`${base(slug)}/connectors/${seg(kind)}`).then((r) => r.data),
  apply: (slug: string, kind: TenantConnectorKind, body: ConnectorConfigRequest) =>
    adminHttp
      .put<TenantConnectorState>(`${base(slug)}/connectors/${seg(kind)}`, body, { timeout: 90_000 })
      .then((r) => r.data),
  test: (slug: string, kind: TenantConnectorKind, body: ConnectorConfigRequest) =>
    adminHttp
      .post<HealthResult>(`${base(slug)}/connectors/${seg(kind)}/test`, body, { timeout: 90_000 })
      .then((r) => r.data),
  revert: (slug: string, kind: TenantConnectorKind) =>
    adminHttp.delete<TenantConnectorState>(`${base(slug)}/connectors/${seg(kind)}`).then((r) => r.data),
}

export const tenantAuthApi = {
  get: (slug: string) => adminHttp.get<TenantAuthConfig>(`${base(slug)}/auth`).then((r) => r.data),
  update: (slug: string, body: TenantAuthConfig) =>
    adminHttp.put<TenantAuthConfig>(`${base(slug)}/auth`, body).then((r) => r.data),
}

export const apiKeysApi = {
  list: (slug: string) => adminHttp.get<ApiKey[]>(`${base(slug)}/api-keys`).then((r) => r.data),
  create: (slug: string, name: string) =>
    adminHttp.post<ApiKeyCreated>(`${base(slug)}/api-keys`, { name }).then((r) => r.data),
  revoke: (slug: string, id: string) =>
    adminHttp.delete<void>(`${base(slug)}/api-keys/${seg(id)}`).then(() => undefined),
}

export const webhooksApi = {
  list: (slug: string) => adminHttp.get<Webhook[]>(`${base(slug)}/webhooks`).then((r) => r.data),
  create: (slug: string, body: WebhookInput) =>
    adminHttp.post<Webhook>(`${base(slug)}/webhooks`, body).then((r) => r.data),
  update: (slug: string, id: string, body: WebhookInput) =>
    adminHttp.put<Webhook>(`${base(slug)}/webhooks/${seg(id)}`, body).then((r) => r.data),
  remove: (slug: string, id: string) =>
    adminHttp.delete<void>(`${base(slug)}/webhooks/${seg(id)}`).then(() => undefined),
  test: (slug: string, id: string) =>
    adminHttp.post<WebhookDelivery>(`${base(slug)}/webhooks/${seg(id)}/test`, undefined, { timeout: 30_000 }).then((r) => r.data),
  deliveries: (slug: string, id: string) =>
    adminHttp.get<WebhookDelivery[]>(`${base(slug)}/webhooks/${seg(id)}/deliveries`).then((r) => r.data),
}

export const tenantUsersApi = {
  list: (slug: string, params: UserQuery = {}) =>
    adminHttp
      .get<Page<User>>(`${base(slug)}/users`, {
        params: query({ q: params.q, offset: params.offset ?? 0, limit: params.limit ?? 50 }),
      })
      .then((r) => r.data),
  upsert: (slug: string, body: UpsertUserRequest) =>
    adminHttp.post<User>(`${base(slug)}/users`, body).then((r) => r.data),
  /** Impersonation token for testing (audited). */
  token: (slug: string, userId: string) =>
    adminHttp.post<UserToken>(`${base(slug)}/users/${seg(userId)}/token`).then((r) => r.data),
}
