import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AppShell, PageFallback } from './components/layout/AppShell'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { AuthProvider, useAuth } from './state/auth'
import { ConfirmProvider } from './state/confirm'
import { SystemProvider } from './state/system'
import { ThemeProvider } from './state/theme'
import { ToastProvider } from './state/toast'

const OverviewPage = lazy(() => import('./pages/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const InfrastructurePage = lazy(() => import('./pages/InfrastructurePage').then((m) => ({ default: m.InfrastructurePage })))
const TenantsPage = lazy(() => import('./pages/TenantsPage').then((m) => ({ default: m.TenantsPage })))
const TenantDetailPage = lazy(() => import('./pages/tenant/TenantDetailPage').then((m) => ({ default: m.TenantDetailPage })))
const PlansPage = lazy(() => import('./pages/PlansPage').then((m) => ({ default: m.PlansPage })))
const AuditPage = lazy(() => import('./pages/AuditPage').then((m) => ({ default: m.AuditPage })))
const AdminsPage = lazy(() => import('./pages/AdminsPage').then((m) => ({ default: m.AdminsPage })))
const PlaygroundPage = lazy(() => import('./pages/playground/PlaygroundPage').then((m) => ({ default: m.PlaygroundPage })))

function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <PageFallback />
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />
  }
  return (
    <SystemProvider>
      <ConfirmProvider>{children}</ConfirmProvider>
    </SystemProvider>
  )
}

export function App() {
  return (
    <ThemeProvider>
      <ToastProvider>
        <AuthProvider>
          <BrowserRouter>
            <Suspense fallback={<PageFallback />}>
              <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route
                  element={
                    <RequireAuth>
                      <AppShell />
                    </RequireAuth>
                  }
                >
                  <Route index element={<OverviewPage />} />
                  <Route path="infrastructure" element={<InfrastructurePage />} />
                  <Route path="tenants" element={<TenantsPage />} />
                  <Route path="tenants/:slug" element={<TenantDetailPage />} />
                  <Route path="tenants/:slug/:tab" element={<TenantDetailPage />} />
                  <Route path="plans" element={<PlansPage />} />
                  <Route path="audit" element={<AuditPage />} />
                  <Route path="admins" element={<AdminsPage />} />
                  <Route path="playground" element={<PlaygroundPage />} />
                  <Route path="*" element={<NotFoundPage />} />
                </Route>
              </Routes>
            </Suspense>
          </BrowserRouter>
        </AuthProvider>
      </ToastProvider>
    </ThemeProvider>
  )
}
