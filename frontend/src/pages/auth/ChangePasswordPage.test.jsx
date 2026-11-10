import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  forgotPassword: vi.fn(),
  verifyResetOtp: vi.fn(),
  resetPassword: vi.fn(),
  resendOtp: vi.fn(),
}))

vi.mock('../../api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(),
  getManagerDashboard: vi.fn(),
  getEmployeeDashboard: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR', fullName: 'Hana Reed' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER', fullName: 'Mona Boss' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE', fullName: 'Asha Rao' },
}

const dashboards = {
  HR: '/hr/dashboard',
  MANAGER: '/manager/dashboard',
  EMPLOYEE: '/employee/dashboard',
}

function renderChangePassword(role = 'EMPLOYEE') {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'existing.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
  return render(
    <MemoryRouter initialEntries={['/change-password']}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

async function fillForm(user, { current = 'Old@2026!', next = 'New@2026!', confirm = 'New@2026!' } = {}) {
  await user.type(await screen.findByLabelText('Current password'), current)
  await user.type(screen.getByLabelText('New password'), next)
  await user.type(screen.getByLabelText('Confirm new password'), confirm)
  await user.click(screen.getByRole('button', { name: 'Change password' }))
}

describe('change-password page', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.resetAllMocks()
  })

  it.each(['HR', 'MANAGER', 'EMPLOYEE'])(
    'is reachable directly and offers a dashboard return for %s',
    async (role) => {
      renderChangePassword(role)

      expect(await screen.findByRole('heading', { name: 'Change password' })).toBeTruthy()
      expect(screen.getByLabelText('Current password')).toBeTruthy()
      expect(screen.getByLabelText('New password')).toBeTruthy()
      expect(screen.getByLabelText('Confirm new password')).toBeTruthy()
      expect(screen.getByRole('link', { name: 'Back to dashboard' }).getAttribute('href'))
        .toBe(dashboards[role])
      expect(screen.getByRole('link', { name: 'Change password' }).getAttribute('href'))
        .toBe('/change-password')
    },
  )

  it('submits the existing DTO, clears the invalidated token and shows a green success message', async () => {
    const user = userEvent.setup()
    authApi.changePassword.mockResolvedValue({ message: 'Password changed successfully.' })
    renderChangePassword('EMPLOYEE')

    await fillForm(user)

    await waitFor(() => expect(authApi.changePassword).toHaveBeenCalledWith({
      currentPassword: 'Old@2026!',
      newPassword: 'New@2026!',
      confirmPassword: 'New@2026!',
    }))
    const status = await screen.findByRole('status')
    expect(status.textContent).toBe('Password changed successfully.')
    expect(status.className).toContain('form-message-success')
    expect(status.getAttribute('role')).toBe('status')
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(screen.getByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
  })

  it('shows a red error for an incorrect current password and keeps the session', async () => {
    const user = userEvent.setup()
    authApi.changePassword.mockRejectedValue({
      response: { status: 400, data: { message: 'Current password is incorrect.' } },
    })
    renderChangePassword('MANAGER')

    await fillForm(user)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('Current password is incorrect.')
    expect(alert.className).toContain('management-error')
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('existing.jwt')
    expect(screen.getByRole('heading', { name: 'Change password' })).toBeTruthy()
  })

  it('rejects a mismatched confirmation without calling the API', async () => {
    const user = userEvent.setup()
    renderChangePassword('HR')

    await fillForm(user, { confirm: 'Different@2026!' })

    expect((await screen.findByRole('alert')).textContent)
      .toBe('New password and confirmation do not match.')
    expect(authApi.changePassword).not.toHaveBeenCalled()
  })

  it('enforces the password policy without calling the API', async () => {
    const user = userEvent.setup()
    renderChangePassword('HR')

    await fillForm(user, { next: 'weak', confirm: 'weak' })

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Password must be at least 8 characters long and contain a letter, a number and a special character.',
    )
    expect(authApi.changePassword).not.toHaveBeenCalled()
  })
})
