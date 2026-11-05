import { useState } from 'react'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { changePassword } from '../../api/authApi.js'
import PasswordField from '../../components/PasswordField.jsx'

const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters long and contain a letter, a number and a special character.'

function passwordIsValid(password) {
  return /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,}$/.test(password)
}

/** Change the password of the signed-in account (the backend derives the user). */
export default function ChangePasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')
    setSuccessMessage('')

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
      setSuccessMessage(result?.message || 'Password changed successfully.')
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
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
        error messages.
      </p>

      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}

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
        </div>
      </form>
    </section>
  )
}
