import { adminHttp, seg } from './http'
import type { Feature, Plan, PlanInput } from '../types'

export const featuresApi = {
  list: () => adminHttp.get<Feature[]>('/api/admin/features').then((r) => r.data),
}

export const plansApi = {
  list: () => adminHttp.get<Plan[]>('/api/admin/plans').then((r) => r.data),
  create: (plan: PlanInput) => adminHttp.post<Plan>('/api/admin/plans', plan).then((r) => r.data),
  update: (code: string, plan: PlanInput) => adminHttp.put<Plan>(`/api/admin/plans/${seg(code)}`, plan).then((r) => r.data),
  /** 409 when tenants use the plan or it is the default. */
  remove: (code: string) => adminHttp.delete<void>(`/api/admin/plans/${seg(code)}`).then(() => undefined),
}
