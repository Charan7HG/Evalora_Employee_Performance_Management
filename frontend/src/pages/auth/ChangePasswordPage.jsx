import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { changePassword } from '../../api/authApi.js'
import PasswordField from '../../components/PasswordField.jsx'
import { useAuth } from '../../context/useAuth.js'
import { dashboardPathForRole } from '../../utils/roles.js'

const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters long and contain a letter, a number and a special character.'

function passwordIsValid(password) {
  return /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,}$/.test(password)
}

/**
 * Change the password of the signed-in account (the backend derives the user).
 *
 * <p>Changing a password increments the account token version, which
 * invalidates every JWT issued before the change. The page therefore clears
 * the now-stale local session and returns to the login page with a success
 * message, instead of leaving the browser holding a token the API will reject.</p>
 */
export default function ChangePasswordPage() {
  const navigate = useNavigate()
  const { user, signOutLocally } = useAuth()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')

    if (!currentPassword) {
      setErrorMessage('Enter your current password.')
      return
    }
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
      const result = await changePassword({ currentPassword, newPassword, confirmPassword })
      const successMessage = result?.message || 'Password changed successfully.'
      window.sessionStorage.setItem('evalora.login.success-message', successMessage)
      // The backend has already invalidated the old token by incrementing the
      // token version, so clear it locally before returning to sign in.
      signOutLocally()
      navigate('/login', {
        replace: true,
        state: { successMessage },
      })
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <section aria-labelledby="change-password-title">
      <p className="dashboard-eyebrow">Account</p>
      <h1 id="change-password-title">Change password</h1>
      <p className="dashboard-intro">
        Your current password is required. Passwords are never shown in logs or
        error messages. Changing your password signs out other sessions, so you
        will sign in again with the new password.
      </p>

      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}

      <form className="staff-form" onSubmit={handleSubmit} noValidate>
        <PasswordField
          id="currentPassword"
          name="currentPassword"
          label="Current password"
          autoComplete="current-password"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          required
        />
        <PasswordField
          id="changeNewPassword"
          name="newPassword"
          label="New password"
          autoComplete="new-password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          required
          hint="At least 8 characters with a letter, number and special character."
        />
        <PasswordField
          id="changeConfirmPassword"
          name="confirmPassword"
          label="Confirm new password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          required
        />
        <div className="management-actions">
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Saving…' : 'Change password'}
          </button>
          <Link className="secondary-button button-link" to={dashboardPathForRole(user.role)}>
            Back to dashboard
          </Link>
        </div>
      </form>
    </section>
  )
}
