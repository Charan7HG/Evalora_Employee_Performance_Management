import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { activateAccount } from '../../api/authApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'

const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,}$/
const PASSWORD_MESSAGE =
  'Password must be at least 8 characters long and contain a letter, a number and a special character.'
const MISSING_TOKEN_MESSAGE =
  'This activation link is invalid or incomplete. Ask your HR administrator to resend the activation email.'

export default function ActivateAccountPage() {
  const [searchParams] = useSearchParams()
  // The token stays in the URL. Removing it here would make a browser refresh
  // render the page with no token and falsely report a broken link.
  const token = searchParams.get('token')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [status, setStatus] = useState(token ? 'form' : 'invalid')
  const [message, setMessage] = useState(token ? '' : MISSING_TOKEN_MESSAGE)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    if (submitting) {
      return
    }
    if (newPassword !== confirmPassword) {
      setMessage('New password and confirmation do not match.')
      return
    }
    if (!PASSWORD_PATTERN.test(newPassword)) {
      setMessage(PASSWORD_MESSAGE)
      return
    }

    setSubmitting(true)
    setMessage('')
    try {
      const result = await activateAccount({ token, newPassword, confirmPassword })
      setStatus('success')
      setMessage(result?.message || 'Your account is activated. You can now sign in.')
    } catch (error) {
      setStatus('error')
      setMessage(getApiErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  if (!token) {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="activation-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT ACTIVATION</p>
          <h1 id="activation-title">Activation link needed</h1>
          <p role="alert">{MISSING_TOKEN_MESSAGE}</p>
          <Link className="primary-button" to="/login">Back to sign in</Link>
        </section>
      </main>
    )
  }

  if (status === 'success') {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="activation-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT ACTIVATION</p>
          <h1 id="activation-title">Account activated</h1>
          <p role="status">{message}</p>
          <Link className="primary-button" to="/login">Continue to sign in</Link>
        </section>
      </main>
    )
  }

  if (status === 'error') {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="activation-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT ACTIVATION</p>
          <h1 id="activation-title">Activation failed</h1>
          <p role="alert">{message}</p>
          <p>Ask your HR administrator to resend the activation email.</p>
          <Link className="primary-button" to="/login">Back to sign in</Link>
        </section>
      </main>
    )
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="activation-title">
        <div className="brand-mark" aria-hidden="true">E</div>
        <p className="eyebrow">ACCOUNT ACTIVATION</p>
        <h1 id="activation-title">Set your password</h1>
        <p className="intro">Choose a password to finish activating your Evalora account.</p>

        {message && <p role="alert">{message}</p>}

        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="newPassword">New password</label>
          <input
            id="newPassword"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            required
          />

          <label htmlFor="confirmPassword">Confirm new password</label>
          <input
            id="confirmPassword"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            required
          />

          <p className="field-hint">
            At least 8 characters with a letter, a number and a special character.
          </p>

          <button className="primary-button" type="submit" disabled={submitting}>
            {submitting ? 'Activating…' : 'Activate account'}
          </button>
        </form>

        <p className="auth-links">
          <Link to="/login">Back to sign in</Link>
        </p>
      </section>
    </main>
  )
}
