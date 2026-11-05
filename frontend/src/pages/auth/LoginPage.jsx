import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { useAuth } from '../../context/useAuth.js'
import PasswordField from '../../components/PasswordField.jsx'
import { clearLockout, getLockout, saveLockout } from '../../api/lockoutStorage.js'

const LOCKOUT_MESSAGE = 'Your account is temporarily blocked. Try again after 5 minutes.'

function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

function formatRemaining(totalSeconds) {
  const safeSeconds = Math.max(0, totalSeconds)
  const minutes = String(Math.floor(safeSeconds / 60)).padStart(2, '0')
  const seconds = String(safeSeconds % 60).padStart(2, '0')
  return `${minutes}:${seconds}`
}

function secondsUntil(untilEpochMs) {
  if (!untilEpochMs) {
    return 0
  }
  return Math.max(0, Math.ceil((untilEpochMs - Date.now()) / 1000))
}

/** Reads the stored lockout once, discarding an expired record. */
function readStoredLockout() {
  const stored = getLockout()
  if (!stored) {
    return null
  }
  if (secondsUntil(stored.untilEpochMs) <= 0) {
    clearLockout()
    return null
  }
  return stored
}

export default function LoginPage() {
  const { signIn, authError, refreshCurrentUser, token } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState(() => readStoredLockout()?.email || '')
  const [password, setPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [lockout, setLockout] = useState(readStoredLockout)
  const [remainingSeconds, setRemainingSeconds] = useState(() =>
    (lockout ? secondsUntil(lockout.untilEpochMs) : 0))

  useEffect(() => {
    if (!lockout) {
      return undefined
    }
    const timer = setInterval(() => {
      const seconds = secondsUntil(lockout.untilEpochMs)
      setRemainingSeconds(seconds)
      if (seconds <= 0) {
        // The timer only stops the local countdown. Whether the account is
        // unlocked is decided by the backend on the next attempt.
        clearLockout()
        setLockout(null)
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [lockout])

  const lockedOut = Boolean(lockout) && remainingSeconds > 0
  const successMessage = location.state?.successMessage || ''
  const requestedPath = location.state?.from?.pathname
  const destination = requestedPath?.startsWith('/') && !requestedPath.startsWith('//')
    ? requestedPath
    : '/app'

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')

    if (lockedOut) {
      return
    }

    const normalizedEmail = email.trim()
    if (!isValidEmail(normalizedEmail)) {
      setErrorMessage('Enter a valid email address.')
      return
    }
    if (!password) {
      setErrorMessage('Enter your password.')
      return
    }

    setIsSubmitting(true)
    try {
      await signIn({ email: normalizedEmail, password })
      clearLockout()
      navigate(destination, { replace: true })
    } catch (error) {
      if (error?.response?.status === 423) {
        const retryAfterSeconds = Number(error.response.data?.retryAfterSeconds)
        const lockedUntilEpochSeconds = Number(error.response.data?.lockedUntilEpochSeconds)
        const untilEpochMs = Number.isFinite(lockedUntilEpochSeconds) && lockedUntilEpochSeconds > 0
          ? lockedUntilEpochSeconds * 1000
          : Date.now() + (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
            ? retryAfterSeconds * 1000
            : 5 * 60 * 1000)
        const record = { email: normalizedEmail, untilEpochMs }
        saveLockout(normalizedEmail, untilEpochMs)
        setLockout(record)
        setRemainingSeconds(secondsUntil(untilEpochMs))
        setErrorMessage('')
      } else if (error?.response?.status === 401 && error.response.data?.message) {
        // Keep the backend's generic invalid-credentials message. Without this the
        // shared 401 handler would report "session expired" on the login form.
        setErrorMessage(error.response.data.message)
      } else {
        setErrorMessage(getApiErrorMessage(error))
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleRetry() {
    setErrorMessage('')
    try {
      await refreshCurrentUser()
      navigate('/app', { replace: true })
    } catch (error) {
      setErrorMessage(getApiErrorMessage(error))
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="login-title">
        <div className="brand-mark" aria-hidden="true">E</div>
        <p className="eyebrow">PERFORMANCE, WITH PURPOSE</p>
        <h1 id="login-title">Welcome to Evalora</h1>
        <p className="intro">Sign in with your Evalora account to continue.</p>

        {(authError || errorMessage) && (
          <div className="form-message" role="alert">
            {errorMessage || authError}
          </div>
        )}

        {!errorMessage && !authError && successMessage && (
          <div className="form-message" role="status">{successMessage}</div>
        )}

        {lockedOut && (
          <div className="form-message lockout-message" role="alert" aria-live="polite">
            <strong>{LOCKOUT_MESSAGE}</strong>
            <span>Try again in {formatRemaining(remainingSeconds)}</span>
          </div>
        )}

        {authError && token && (
          <button className="text-button" type="button" onClick={handleRetry}>
            Retry connection
          </button>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="email">Email</label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="username"
            inputMode="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            readOnly={lockedOut}
            required
          />

          <PasswordField
            id="password"
            name="password"
            label="Password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            disabled={lockedOut || isSubmitting}
          />

          <button className="primary-button" type="submit" disabled={isSubmitting || lockedOut}>
            {isSubmitting ? 'Signing in…' : lockedOut ? 'Temporarily blocked' : 'Sign in'}
          </button>
        </form>

        <p className="auth-links">
          <Link to="/forgot-password">Forgot Password?</Link>
        </p>
        {lockedOut && (
          <p className="field-hint">
            The block is enforced by the server and ends automatically after five
            minutes from the last failed attempt.
          </p>
        )}
      </section>
    </main>
  )
}
