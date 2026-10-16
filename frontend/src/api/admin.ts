import { adminHttp, query, seg } from './http'
import type {
  Admin,
  AuditEntry,
  AuditQuery,
  ChangePasswordRequest,
  CreateAdminRequest,
  LoginRequest,
  LoginResponse,
  SystemOverview,
  ThroughputPoint,
} from '../types'

export const authApi = {
  login: (body: LoginRequest) => adminHttp.post<LoginResponse>('/api/admin/auth/login', body).then((r) => r.data),
  me: () => adminHttp.get<Admin>('/api/admin/auth/me').then((r) => r.data),
  changePassword: (body: ChangePasswordRequest) =>
    adminHttp.post<void>('/api/admin/auth/change-password', body).then(() => undefined),
}

export const adminsApi = {
  list: () => adminHttp.get<Admin[]>('/api/admin/admins').then((r) => r.data),
  create: (body: CreateAdminRequest) => adminHttp.post<Admin>('/api/admin/admins', body).then((r) => r.data),
  remove: (id: string) => adminHttp.delete<void>(`/api/admin/admins/${seg(id)}`).then(() => undefined),
}

export const systemApi = {
  overview: () => adminHttp.get<SystemOverview>('/api/admin/system/overview').then((r) => r.data),
  metrics: (minutes = 60) =>
    adminHttp
      .get<ThroughputPoint[]>('/api/admin/system/metrics', { params: { minutes: Math.min(Math.max(minutes, 1), 1440) } })
      .then((r) => r.data),
}

export const auditApi = {
  list: (params: AuditQuery = {}) =>
    adminHttp
      .get<AuditEntry[]>('/api/admin/audit', {
        params: query({ tenant: params.tenant, limit: params.limit ?? 100, before: params.before }),
      })
      .then((r) => r.data),
}
