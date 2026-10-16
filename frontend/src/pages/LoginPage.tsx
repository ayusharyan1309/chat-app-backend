import { Eye, EyeOff, Moon, Sun } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { errorMessage } from '../api/http'
import { BrandMark } from '../components/layout/BrandMark'
import { Button, IconButton } from '../components/ui/Button'
import { Callout } from '../components/ui/Feedback'
import { Field, Input } from '../components/ui/Form'
import { useDocumentTitle } from '../hooks/misc'
import { useAuth } from '../state/auth'
import { useTheme } from '../state/theme'

interface LocationState {
  from?: string
}

export function LoginPage() {
  useDocumentTitle('Sign in')
  const { status, login, expired } = useAuth()
  const { theme, toggle } = useTheme()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as LocationState | null)?.from ?? '/'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (status === 'authenticated') return <Navigate to={from} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!email.trim() || !password) {
      setError('Enter your email and password.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(email.trim(), password)
      navigate(from === '/login' ? '/' : from, { replace: true })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <div className="login__theme">
        <IconButton
          label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
          icon={theme === 'dark' ? <Sun size={17} aria-hidden /> : <Moon size={17} aria-hidden />}
          onClick={toggle}
        />
      </div>
      <main className="login__panel">
        <div className="login__brand">
          <BrandMark size={36} />
          <div>
            <h1 className="login__title">Chat Platform</h1>
            <p className="login__subtitle">Operator console</p>
          </div>
        </div>
        <form className="login__form" onSubmit={submit} noValidate>
          {expired ? <Callout tone="warn">Your session expired. Sign in again to continue.</Callout> : null}
          <Field label="Email">
            {(c) => (
              <Input
                {...c}
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoFocus
                placeholder="you@company.com"
              />
            )}
          </Field>
          <Field label="Password">
            {(c) => (
              <Input
                {...c}
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                suffix={
                  <button type="button" className="input-reveal" onClick={() => setShowPassword((v) => !v)} aria-label={showPassword ? 'Hide password' : 'Show password'}>
                    {showPassword ? <EyeOff size={15} aria-hidden /> : <Eye size={15} aria-hidden />}
                  </button>
                }
              />
            )}
          </Field>
          {error ? <Callout tone="danger">{error}</Callout> : null}
          <Button type="submit" variant="primary" loading={busy} className="login__submit">
            Sign in
          </Button>
        </form>
        <p className="login__foot">
          First run? Sign in with the bootstrap admin configured on the backend (application.yml or environment), then
          create named admins under Admins.
        </p>
      </main>
    </div>
  )
}
