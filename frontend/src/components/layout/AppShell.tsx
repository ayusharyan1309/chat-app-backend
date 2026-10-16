import {
  Building2,
  Cable,
  Gauge,
  KeyRound,
  Layers,
  LogOut,
  Menu as MenuIcon,
  MessagesSquare,
  Moon,
  ScrollText,
  ShieldCheck,
  Sun,
  X,
} from 'lucide-react'
import { Suspense, useState, type FormEvent, type ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { authApi } from '../../api/admin'
import { useAction } from '../../hooks/useAction'
import { formatDuration } from '../../lib/format'
import { cx } from '../../lib/util'
import { useAuth } from '../../state/auth'
import { useSystem } from '../../state/system'
import { useTheme } from '../../state/theme'
import { Button, IconButton } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Badge, Callout, Lamp, Spinner } from '../ui/Feedback'
import { Field, Input } from '../ui/Form'
import { Avatar, Menu } from '../ui/Misc'
import { BrandMark } from './BrandMark'

interface NavItem {
  to: string
  label: string
  icon: ReactNode
  end?: boolean
}

const NAV: Array<{ group: string; items: NavItem[] }> = [
  {
    group: 'Platform',
    items: [
      { to: '/', label: 'Overview', icon: <Gauge size={17} aria-hidden />, end: true },
      { to: '/infrastructure', label: 'Infrastructure', icon: <Cable size={17} aria-hidden /> },
    ],
  },
  {
    group: 'Customers',
    items: [
      { to: '/tenants', label: 'Tenants', icon: <Building2 size={17} aria-hidden /> },
      { to: '/plans', label: 'Plans', icon: <Layers size={17} aria-hidden /> },
    ],
  },
  {
    group: 'Tools',
    items: [{ to: '/playground', label: 'Chat playground', icon: <MessagesSquare size={17} aria-hidden /> }],
  },
  {
    group: 'Governance',
    items: [
      { to: '/audit', label: 'Audit log', icon: <ScrollText size={17} aria-hidden /> },
      { to: '/admins', label: 'Admins', icon: <ShieldCheck size={17} aria-hidden /> },
    ],
  },
]

function Sidebar({ open, onNavigate }: { open: boolean; onNavigate: () => void }) {
  return (
    <aside className={cx('sidebar', open && 'is-open')} aria-label="Main navigation">
      <div className="sidebar__brand">
        <BrandMark size={28} />
        <div className="sidebar__brand-text">
          <span className="sidebar__product">Chat Platform</span>
          <span className="sidebar__sub">Operator console</span>
        </div>
      </div>
      <nav className="sidebar__nav">
        {NAV.map((section) => (
          <div className="sidebar__group" key={section.group}>
            <p className="sidebar__group-label">{section.group}</p>
            <ul>
              {section.items.map((item) => (
                <li key={item.to}>
                  <NavLink to={item.to} end={item.end} className={({ isActive }) => cx('nav-link', isActive && 'is-active')} title={item.label} onClick={onNavigate}>
                    {item.icon}
                    <span className="nav-link__label">{item.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
    </aside>
  )
}

function NodeStatus() {
  const { overview, reachable } = useSystem()
  if (reachable === false) {
    return (
      <span className="topbar__node" title="The console cannot reach the backend">
        <Lamp state="down" label="Backend unreachable" />
      </span>
    )
  }
  if (!overview) {
    return (
      <span className="topbar__node">
        <Spinner size={12} />
        <span className="muted">Connecting</span>
      </span>
    )
  }
  const unhealthy = overview.components.filter((c) => !c.healthy).length
  return (
    <span className="topbar__node" title={`Node ${overview.nodeId}, up ${formatDuration(overview.uptimeSeconds)}`}>
      <Lamp state={unhealthy ? 'warn' : 'ok'} label={<span className="mono">{overview.nodeId}</span>} />
      <Badge mono>v{overview.version}</Badge>
      {unhealthy ? <Badge tone="warn">{unhealthy} degraded</Badge> : null}
    </span>
  )
}

function ChangePasswordDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const action = useAction(
    async (body: { currentPassword: string; newPassword: string }) => {
      await authApi.changePassword(body)
      return true
    },
    { success: 'Password changed', toastError: false },
  )
  const mismatch = confirm.length > 0 && next !== confirm
  const tooShort = next.length > 0 && next.length < 8

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!current || !next || mismatch || tooShort) return
    const ok = await action.run({ currentPassword: current, newPassword: next })
    if (ok) onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="sm"
      title="Change password"
      onSubmit={submit}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={action.busy} disabled={!current || !next || mismatch || tooShort}>
            Change password
          </Button>
        </>
      }
    >
      <Field label="Current password" required>
        {(c) => <Input {...c} type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} autoFocus />}
      </Field>
      <Field label="New password" required error={tooShort ? 'Use at least 8 characters.' : undefined}>
        {(c) => <Input {...c} type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />}
      </Field>
      <Field label="Repeat new password" required error={mismatch ? 'The passwords do not match.' : undefined}>
        {(c) => <Input {...c} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      {action.error ? <Callout tone="danger">{action.error.message}</Callout> : null}
    </Dialog>
  )
}

function TopBar({ onToggleNav, navOpen }: { onToggleNav: () => void; navOpen: boolean }) {
  const { theme, toggle } = useTheme()
  const { admin, logout } = useAuth()
  const [pwOpen, setPwOpen] = useState(false)
  return (
    <header className="topbar">
      <IconButton
        className="topbar__nav-toggle"
        label={navOpen ? 'Close navigation' : 'Open navigation'}
        icon={navOpen ? <X size={18} aria-hidden /> : <MenuIcon size={18} aria-hidden />}
        onClick={onToggleNav}
        aria-expanded={navOpen}
      />
      <NodeStatus />
      <div className="spacer" />
      <IconButton
        label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        icon={theme === 'dark' ? <Sun size={17} aria-hidden /> : <Moon size={17} aria-hidden />}
        onClick={toggle}
      />
      {admin ? (
        <Menu
          label="Account"
          trigger={(props) => (
            <button type="button" className="topbar__account" {...props}>
              <Avatar name={admin.name || admin.email} id={admin.id} size={26} />
              <span className="topbar__account-text">
                <span className="topbar__account-name">{admin.name || admin.email}</span>
                <span className="topbar__account-role">{admin.role.toLowerCase()}</span>
              </span>
            </button>
          )}
          header={
            <div className="stack stack--sm">
              <span>
                <strong>{admin.name}</strong>
                <br />
                <span className="muted">{admin.email}</span>
              </span>
              <span>
                <Badge tone={admin.role === 'VIEWER' ? 'neutral' : 'accent'}>{admin.role}</Badge>
              </span>
            </div>
          }
          items={[
            { key: 'pw', label: 'Change password', icon: <KeyRound size={15} aria-hidden />, onSelect: () => setPwOpen(true) },
            { key: 'out', label: 'Sign out', icon: <LogOut size={15} aria-hidden />, onSelect: logout, danger: true },
          ]}
        />
      ) : (
        // Profile could not be loaded (e.g. backend unreachable): still allow signing out.
        <IconButton label="Sign out" icon={<LogOut size={17} aria-hidden />} onClick={logout} />
      )}
      <ChangePasswordDialog open={pwOpen} onClose={() => setPwOpen(false)} />
    </header>
  )
}

export function PageFallback() {
  return (
    <div className="page-fallback" aria-busy="true">
      <Spinner size={18} label="Loading page" />
    </div>
  )
}

export function AppShell() {
  const [navOpen, setNavOpen] = useState(false)
  const { admin } = useAuth()

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Sidebar open={navOpen} onNavigate={() => setNavOpen(false)} />
      {navOpen ? <div className="shell__scrim" onClick={() => setNavOpen(false)} aria-hidden /> : null}
      <div className="shell__main">
        <TopBar onToggleNav={() => setNavOpen((o) => !o)} navOpen={navOpen} />
        {admin?.role === 'VIEWER' ? (
          <div className="viewer-banner" role="note">
            You are signed in with the read-only Viewer role. Changes are disabled.
          </div>
        ) : null}
        <main id="main" className="shell__content" tabIndex={-1}>
          <Suspense fallback={<PageFallback />}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  )
}
