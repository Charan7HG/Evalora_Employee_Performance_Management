import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import {
  forgotPassword,
  resendOtp,
  resetPassword,
  verifyResetOtp,
} from '../../api/authApi.js'
import PasswordField from '../../components/PasswordField.jsx'

const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters long and contain a letter, a number and a special character.'

function passwordIsValid(password) {
  return /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,}$/.test(password)
}

/** Forgot Password page with OTP-based flow.
 *  1. Enter email → sends 6-digit OTP via Brevo.
 *  2. Enter OTP → verifies and receives a short-lived reset ticket.
 *  3. Set new password using the ticket.
 *
 * The response is identical whether the email belongs to an account,
 * an unknown email, or a disabled account (prevents email enumeration).
 */
export default function ForgotPasswordPage() {
  const navigate = useNavigate()
  const [step, setStep] = useState('email') // 'email' | 'otp' | 'reset'
  const [email, setEmail] = useState('')
  const [otp, setOtp] = useState('')
  const [resetTicket, setResetTicket] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [resendCooldown, setResendCooldown] = useState(0)

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')
    setIsSubmitting(true)

    try {
      if (step === 'email') {
        await forgotPassword({ email })
        setStep('otp')
      } else if (step === 'otp') {
        const result = await verifyResetOtp({ email, otp })
        if (result.valid) {
          setResetTicket(result.ticket)
          setStep('reset')
        } else {
          setErrorMessage(result.message || 'Invalid or expired OTP.')
        }
      } else if (step === 'reset') {
        if (newPassword !== confirmPassword) {
          setErrorMessage('New password and confirmation do not match.')
          return
        }

        if (!passwordIsValid(newPassword)) {
          setErrorMessage(PASSWORD_POLICY_MESSAGE)
          return
        }

        const result = await resetPassword({
          token: resetTicket,
          newPassword,
          confirmPassword,
        })

        // The request resolved, so the backend confirmed the reset. Only then
        // show the success state on the login page.
        navigate('/login', {
          replace: true,
          state: {
            successMessage: result?.message || 'Password changed successfully.',
          },
        })
      }
    } catch (requestError) {
      const message = getApiErrorMessage(requestError)
      setErrorMessage(/development|dev-reset|configured for this account/i.test(message)
        ? 'Password change is currently unavailable. Please contact your administrator.'
        : message)
    } finally {
      setIsSubmitting(false)
    }
  }

  function handleOtpChange(event) {
    setOtp(event.target.value)
  }

  async function handleResendClick() {
    if (resendCooldown > 0 || !email) {
      return
    }

    try {
      await resendOtp({ email })
      setResendCooldown(60)
      const interval = window.setInterval(() => {
        setResendCooldown((previous) => Math.max(0, previous - 1))
      }, 1000)
      window.setTimeout(() => window.clearInterval(interval), 60000)
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    }
  }

  if (step === 'email') {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="forgot-email-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT RECOVERY</p>
          <h1 id="forgot-email-title">Send OTP Email</h1>
          <p className="intro">Enter your email address to receive a 6-digit OTP.</p>

          {errorMessage && (
            <div className="form-message" role="alert">{errorMessage}</div>
          )}

          <form onSubmit={handleSubmit} noValidate>
            <PasswordField
              id="email"
              name="email"
              label="Email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Sending…' : 'Send OTP'}
            </button>
          </form>

          {resendCooldown > 0 && (
            <p className="field-hint">
              You can resend the OTP in {resendCooldown} seconds.
            </p>
          )}

          <p className="auth-links">
            <Link to="/login">Back to sign in</Link>
          </p>
        </section>
      </main>
    )
  }

  if (step === 'otp') {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="forgot-otp-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT RECOVERY</p>
          <h1 id="forgot-otp-title">Verify OTP</h1>
          <p className="intro">Enter the 6-digit OTP sent to your email.</p>

          {errorMessage && (
            <div className="form-message" role="alert">{errorMessage}</div>
          )}

          <form onSubmit={handleSubmit} noValidate>
            <PasswordField
              id="otp"
              name="otp"
              label="OTP Code"
              autoComplete="one-time-code"
              value={otp}
              onChange={handleOtpChange}
              required
              hint="6-digit code sent to your email"
            />
            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Verifying…' : 'Verify OTP'}
            </button>
          </form>

          {resendCooldown > 0 && (
            <p className="field-hint">
              Can't find the email? Resend OTP in {resendCooldown} seconds.
              <button className="inline-button" onClick={handleResendClick} type="button">
                Resend
              </button>
            </p>
          )}

          <p className="auth-links">
            <Link to="/login">Back to sign in</Link>
          </p>
        </section>
      </main>
    )
  }

  if (step === 'reset') {
    return (
      <main className="auth-page">
        <section className="auth-card" aria-labelledby="forgot-reset-title">
          <div className="brand-mark" aria-hidden="true">E</div>
          <p className="eyebrow">ACCOUNT RECOVERY</p>
          <h1 id="forgot-reset-title">Set New Password</h1>
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

  return null
}