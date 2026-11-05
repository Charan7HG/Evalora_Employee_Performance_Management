import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { devResetPassword } from '../../api/authApi.js'
import PasswordField from '../../components/PasswordField.jsx'

const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters long and contain a letter, a number and a special character.'

function passwordIsValid(password) {
  return /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,}$/.test(password)
}

/** Password reset screen. Backend reset eligibility remains server-controlled. */
export default function ForgotPasswordPage() {
  const navigate = useNavigate()
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')

    if (!passwordIsValid(newPassword)) {
      setErrorMessage(PASSWORD_POLICY_MESSAGE)
      return
    }
    if (newPassword !== confirmPassword) {
      setErrorMessage('New password and confirmation do not match.')
      return
    }

    setIsSubmitting(true)
    try {
      await devResetPassword({ newPassword, confirmPassword })
      navigate('/login', {
        replace: true,
        state: { successMessage: 'Password changed successfully.' },
      })
    } catch (requestError) {
      const message = getApiErrorMessage(requestError)
      setErrorMessage(/development|dev-reset|configured for this account/i.test(message)
        ? 'Password change is currently unavailable. Please contact your administrator.'
        : message)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="forgot-password-title">
        <div className="brand-mark" aria-hidden="true">E</div>
        <p className="eyebrow">ACCOUNT RECOVERY</p>
        <h1 id="forgot-password-title">Set New Password</h1>
        <p className="intro">Enter and confirm your new password.</p>

        {errorMessage && (
          <div className="form-message" role="alert">{errorMessage}</div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <PasswordField
            id="newPassword"
            name="newPassword"
            label="New Password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            required
            hint="At least 8 characters with a letter, number and special character."
          />
          <PasswordField
            id="confirmPassword"
            name="confirmPassword"
            label="Confirm Password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            required
          />
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Changing…' : 'Change Password'}
          </button>
        </form>

        <p className="auth-links">
          <Link to="/login">Back to sign in</Link>
        </p>
      </section>
    </main>
  )
}
