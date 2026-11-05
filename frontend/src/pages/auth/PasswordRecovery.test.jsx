import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import { LOCKOUT_STORAGE_KEY, saveLockout } from '../../api/lockoutStorage.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  devResetPassword: vi.fn(),
}))

vi.mock('../../api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(),
  getManagerDashboard: vi.fn(),
  getEmployeeDashboard: vi.fn(),
}))

function renderApp(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('password recovery flow', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('opens a clean password change form from the login page', async () => {
    const user = userEvent.setup()
    renderApp('/login')

    await user.click(await screen.findByRole('link', { name: 'Forgot Password?' }))

    expect(await screen.findByRole('heading', { name: 'Set New Password' })).toBeTruthy()
    expect(screen.getByLabelText('New Password')).toBeTruthy()
    expect(screen.getByLabelText('Confirm Password')).toBeTruthy()
    expect(screen.getByText('Enter and confirm your new password.')).toBeTruthy()
    expect(screen.queryByText(/development|local account|evalora\.dev-reset|no email|reset link/i)).toBeNull()
    expect(screen.queryByLabelText('Email')).toBeNull()
    expect(screen.getAllByRole('button', { name: 'Show password' })).toHaveLength(2)
  })

  it('rejects mismatched passwords before calling the backend', async () => {
    const user = userEvent.setup()
    renderApp('/forgot-password')

    await user.type(screen.getByLabelText('New Password'), 'N3w-Pass!')
    await user.type(screen.getByLabelText('Confirm Password'), 'Different1!')
    await user.click(screen.getByRole('button', { name: 'Change Password' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('New password and confirmation do not match.')
    expect(authApi.devResetPassword).not.toHaveBeenCalled()
  })

  it('supports standard Tab and Shift+Tab field navigation', async () => {
    const user = userEvent.setup()
    renderApp('/forgot-password')

    await user.tab()
    expect(document.activeElement).toBe(screen.getByLabelText('New Password'))
    await user.tab()
    await user.tab()
    expect(document.activeElement).toBe(screen.getByLabelText('Confirm Password'))
    await user.tab({ shift: true })
    await user.tab({ shift: true })
    expect(document.activeElement).toBe(screen.getByLabelText('New Password'))
  })

  it('rejects a password that breaks the policy before calling the backend', async () => {
    const user = userEvent.setup()
    renderApp('/forgot-password')

    await user.type(screen.getByLabelText('New Password'), 'short')
    await user.type(screen.getByLabelText('Confirm Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Change Password' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Password must be at least 8 characters long')
    expect(authApi.devResetPassword).not.toHaveBeenCalled()
  })

  it('resets the configured account directly and shows the success on login', async () => {
    const user = userEvent.setup()
    authApi.devResetPassword.mockResolvedValue({
      message: 'Password changed successfully.',
    })
    renderApp('/forgot-password')

    await user.type(screen.getByLabelText('New Password'), 'N3w-Pass!')
    await user.type(screen.getByLabelText('Confirm Password'), 'N3w-Pass!')
    await user.click(screen.getByRole('button', { name: 'Change Password' }))

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(await screen.findByText('Password changed successfully.')).toBeTruthy()
    expect(authApi.devResetPassword).toHaveBeenCalledWith({
      newPassword: 'N3w-Pass!',
      confirmPassword: 'N3w-Pass!',
    })
    expect(authApi.devResetPassword).toHaveBeenCalledOnce()
  })

  it('reports a backend failure without clearing the typed password', async () => {
    const user = userEvent.setup()
    authApi.devResetPassword.mockRejectedValue({
      response: {
        status: 400,
        data: { message: 'Development password reset is not configured for this account.' },
      },
    })
    renderApp('/forgot-password')

    await user.type(screen.getByLabelText('New Password'), 'N3w-Pass!')
    await user.type(screen.getByLabelText('Confirm Password'), 'N3w-Pass!')
    await user.click(screen.getByRole('button', { name: 'Change Password' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Password change is currently unavailable')
    expect(screen.getByLabelText('New Password').value).toBe('N3w-Pass!')
  })

  it('redirects the retired token-based reset URL to the direct form', async () => {
    renderApp('/reset-password?token=old-token')

    expect(await screen.findByRole('heading', { name: 'Set New Password' })).toBeTruthy()
    expect(screen.queryByLabelText('Email')).toBeNull()
  })
})

describe('temporary login lockout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function attemptLockedLogin(user) {
    await user.type(screen.getByLabelText('Email'), 'hr@example.test')
    await user.type(screen.getByLabelText('Password'), 'WrongPass1!')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
  }

  it('shows the server lockout message and blocks the login form', async () => {
    const user = userEvent.setup()
    authApi.login.mockRejectedValue({
      response: {
        status: 423,
        data: {
          message: 'Account is temporarily locked. Please try again later.',
          retryAfterSeconds: 299,
          lockedUntilEpochSeconds: Math.floor(Date.now() / 1000) + 299,
        },
      },
    })
    renderApp('/login')
    await attemptLockedLogin(user)

    expect(await screen.findByText(
      'Your account is temporarily blocked. Try again after 5 minutes.',
    )).toBeTruthy()
    expect(screen.getByText(/Try again in 0[45]:\d{2}/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Temporarily blocked' }).disabled).toBe(true)
    expect(screen.getByLabelText('Password').disabled).toBe(true)
    expect(authApi.login).toHaveBeenCalledOnce()
  })

  it('counts down visibly and unlocks the form when the server deadline passes', async () => {
    vi.useFakeTimers()
    saveLockout('hr@example.test', Date.now() + 5000)
    renderApp('/login')

    expect(screen.getByText('Try again in 00:05')).toBeTruthy()
    expect(screen.getByLabelText('Email').value).toBe('hr@example.test')
    expect(screen.getByRole('button', { name: 'Temporarily blocked' }).disabled).toBe(true)

    await act(async () => {
      vi.advanceTimersByTime(2000)
    })
    expect(screen.getByText('Try again in 00:03')).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(4000)
    })

    expect(screen.queryByText(/Try again in/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Sign in' }).disabled).toBe(false)
    expect(localStorage.getItem(LOCKOUT_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    // The timer only releases the form; the backend still owns the lock.
    expect(authApi.login).not.toHaveBeenCalled()
  })

  it('shows a generic invalid-credentials message instead of revealing the account', async () => {
    const user = userEvent.setup()
    authApi.login.mockRejectedValue({
      response: { status: 401, data: { message: 'Invalid email or password.' } },
    })
    renderApp('/login')
    await attemptLockedLogin(user)

    expect((await screen.findByRole('alert')).textContent).toContain('Invalid email or password.')
    expect(screen.queryByText(/Try again in/)).toBeNull()
    expect(localStorage.getItem(LOCKOUT_STORAGE_KEY)).toBeNull()
  })
})
