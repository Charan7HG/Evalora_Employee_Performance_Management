import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from './api/authApi.js'
import * as dashboardApi from './api/dashboardApi.js'
import {
  AUTHENTICATION_FAILURE_EVENT,
  TOKEN_STORAGE_KEY,
} from './api/tokenStorage.js'
import App from './App.jsx'
import { AuthProvider } from './context/AuthContext.jsx'
import { hasAnyRole, USER_ROLES } from './utils/roles.js'

vi.mock('./api/authApi.js', () => ({
  login: vi.fn(),
  activateAccount: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('./api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(),
  getManagerDashboard: vi.fn(),
  getEmployeeDashboard: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR', fullName: 'Hana Reed' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER', fullName: 'Mona Boss' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE', fullName: 'Asha Rao' },
}

const dashboardPaths = {
  HR: '/hr/dashboard',
  MANAGER: '/manager/dashboard',
  EMPLOYEE: '/employee/dashboard',
}

const hrData = {
  totalActiveEmployees: 18,
  totalActiveManagers: 4,
  totalPerformanceCycles: 3,
  totalPublishedCycles: 2,
  employeesAwaitingSelfAssessment: 5,
  employeesAwaitingManagerReview: 6,
  employeesAwaitingHrReview: 7,
  completedAssessments: 12,
  publishedResults: 9,
}

const managerData = {
  totalActiveDirectReports: 8,
  employeesAwaitingSelfAssessment: 2,
  assessmentsAwaitingManagerReview: 3,
  assessmentsAwaitingHrReview: 1,
  completedAssessments: 5,
  publishedResults: 4,
}

const employeeData = {
  currentCycle: {
    cycleId: 7,
    cycleName: 'FY 2026',
    cycleStatus: 'PUBLISHED',
    assessmentStatus: 'SELF_ASSESSMENT_PENDING',
    dueDate: '2026-08-15',
  },
  latestPublishedResult: {
    score: 4.25,
    publishedAt: '2026-06-02T10:00:00Z',
    cycle: {
      cycleId: 6,
      cycleName: 'FY 2025',
      startDate: '2025-01-01',
      endDate: '2025-12-31',
    },
  },
}

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

function authenticateAs(role) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'existing.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
}

describe('role-based dashboard routes', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getHrDashboard.mockResolvedValue(hrData)
    dashboardApi.getManagerDashboard.mockResolvedValue(managerData)
    dashboardApi.getEmployeeDashboard.mockResolvedValue(employeeData)
  })

  it.each([
    ['HR', dashboardApi.getHrDashboard],
    ['MANAGER', dashboardApi.getManagerDashboard],
    ['EMPLOYEE', dashboardApi.getEmployeeDashboard],
  ])('%s is restricted to their own dashboard', async (role, loadDashboard) => {
    authenticateAs(role)
    const ownRolePath = dashboardPaths[role]
    const { unmount } = renderApp(ownRolePath)

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeTruthy()
    expect(screen.getByText(users[role].email)).toBeTruthy()
    expect(await screen.findByText(`Hi, ${users[role].fullName}!`)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Dashboard' }).getAttribute('href'))
      .toBe(ownRolePath)
    await waitFor(() => expect(loadDashboard).toHaveBeenCalledOnce())

    unmount()
    for (const [otherRole, otherPath] of Object.entries(dashboardPaths)) {
      if (otherRole === role) {
        continue
      }

      const forbiddenRoute = renderApp(otherPath)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      expect(screen.getByRole('link', { name: 'Go to your dashboard' })
        .getAttribute('href')).toBe(ownRolePath)
      forbiddenRoute.unmount()
    }
  })

  it('redirects an unauthenticated dashboard visit to login', async () => {
    renderApp('/employee/dashboard')

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(authApi.getCurrentUser).not.toHaveBeenCalled()
  })

  it('redirects an authenticated root visit to the backend-provided role dashboard', async () => {
    authenticateAs('HR')
    renderApp('/')

    expect(await screen.findByText('Organization-wide performance activity.')).toBeTruthy()
    await waitFor(() => expect(dashboardApi.getHrDashboard).toHaveBeenCalledOnce())
    expect(dashboardApi.getManagerDashboard).not.toHaveBeenCalled()
    expect(dashboardApi.getEmployeeDashboard).not.toHaveBeenCalled()
  })

  it('submits the backend login fields, stores its JWT and enters the role dashboard', async () => {
    const user = userEvent.setup()
    authApi.login.mockResolvedValue({
      token: 'new.jwt.token',
      tokenType: 'Bearer',
      expiresIn: 28800,
      user: users.EMPLOYEE,
    })

    renderApp('/login')

    await user.type(screen.getByLabelText('Email'), users.EMPLOYEE.email)
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Your current assessment and published performance result.')).toBeTruthy()
    expect(await screen.findByText('Hi, Asha Rao!')).toBeTruthy()
    expect(authApi.login).toHaveBeenCalledWith({
      email: users.EMPLOYEE.email,
      password: 'secret',
    })
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('new.jwt.token')
  })

  it('routes activation links through the frontend activation flow', async () => {
    const user = userEvent.setup()
    authApi.activateAccount.mockResolvedValue({ message: 'Account activated successfully.' })
    renderApp('/activate?token=activation-test-value')

    await user.type(await screen.findByLabelText('New password'), 'Valid@2026!')
    await user.type(screen.getByLabelText('Confirm new password'), 'Valid@2026!')
    await user.click(screen.getByRole('button', { name: 'Activate account' }))

    expect(await screen.findByText('Account activated successfully.')).toBeTruthy()
    expect(authApi.activateAccount).toHaveBeenCalledWith({
      token: 'activation-test-value',
      newPassword: 'Valid@2026!',
      confirmPassword: 'Valid@2026!',
    })
    expect(screen.getByRole('link', { name: 'Continue to sign in' }).getAttribute('href')).toBe('/login')
  })

  it('validates an invalid email before making a login request', async () => {
    const user = userEvent.setup()
    renderApp('/login')

    await user.type(screen.getByLabelText('Email'), 'invalid')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect((await screen.findByRole('alert')).textContent).toContain('Enter a valid email address.')
    expect(authApi.login).not.toHaveBeenCalled()
  })

  it('shows a not-found page for unknown routes', async () => {
    renderApp('/not-a-route')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeTruthy()
  })

  it('renders the HR dashboard counters supplied by the API', async () => {
    authenticateAs('HR')
    renderApp('/hr/dashboard')

    expect(await screen.findByText('18')).toBeTruthy()
    expect(screen.getByText('Active employees')).toBeTruthy()
    expect(screen.getByText('Active managers')).toBeTruthy()
    expect(screen.getByText('Pending manager reviews')).toBeTruthy()
    expect(screen.getByText('Pending HR reviews')).toBeTruthy()
    expect(screen.getByText('9')).toBeTruthy()
  })

  it('renders the manager dashboard counters supplied by the API', async () => {
    authenticateAs('MANAGER')
    renderApp('/manager/dashboard')

    expect(await screen.findByText('8')).toBeTruthy()
    expect(screen.getByText('Active direct reports')).toBeTruthy()
    expect(screen.getByText('Assessments awaiting manager review')).toBeTruthy()
    expect(screen.getByText('Published results')).toBeTruthy()
  })

  it('renders the employee cycle and latest published result', async () => {
    authenticateAs('EMPLOYEE')
    renderApp('/employee/dashboard')

    expect(await screen.findByText('FY 2026')).toBeTruthy()
    expect(screen.getByText('SELF_ASSESSMENT_PENDING')).toBeTruthy()
    expect(screen.getByText('2026-08-15')).toBeTruthy()
    expect(screen.getByText('FY 2025')).toBeTruthy()
    expect(screen.getByText('4.25')).toBeTruthy()
  })

  it('shows empty states when the employee has no cycle or published result', async () => {
    authenticateAs('EMPLOYEE')
    dashboardApi.getEmployeeDashboard.mockResolvedValue({
      currentCycle: null,
      latestPublishedResult: null,
    })
    renderApp('/employee/dashboard')

    expect(await screen.findByText('No current cycle or assessment is assigned.')).toBeTruthy()
    expect(screen.getByText('No published result is available yet.')).toBeTruthy()
  })

  it('handles a missing due date and optional published-result details', async () => {
    authenticateAs('EMPLOYEE')
    dashboardApi.getEmployeeDashboard.mockResolvedValue({
      currentCycle: {
        cycleId: 7,
        cycleName: 'FY 2026',
        cycleStatus: 'PUBLISHED',
        assessmentStatus: 'COMPLETED',
        dueDate: null,
      },
      latestPublishedResult: {
        score: 4.1,
        publishedAt: null,
        cycle: null,
      },
    })
    renderApp('/employee/dashboard')

    expect(await screen.findByText('No due date')).toBeTruthy()
    expect(screen.getByText('Cycle information unavailable')).toBeTruthy()
    expect(screen.getByText('Publication date unavailable')).toBeTruthy()
  })

  it('shows a loading state and retries a failed dashboard request', async () => {
    const user = userEvent.setup()
    authenticateAs('HR')
    dashboardApi.getHrDashboard
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(hrData)
    renderApp('/hr/dashboard')

    expect(await screen.findByRole('status')).toBeTruthy()
    expect(await screen.findByText(/whether the backend is running/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('18')).toBeTruthy()
    await waitFor(() => expect(dashboardApi.getHrDashboard).toHaveBeenCalledTimes(2))
  })

  it('does not log the user out when a dashboard request returns 403', async () => {
    authenticateAs('MANAGER')
    dashboardApi.getManagerDashboard.mockRejectedValue({
      response: { status: 403 },
    })
    renderApp('/manager/dashboard')

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(screen.getByText(users.MANAGER.email)).toBeTruthy()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('existing.jwt')
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy()
  })

  it('redirects to login when a protected API request reports 401', async () => {
    authenticateAs('EMPLOYEE')
    renderApp('/employee/dashboard')
    expect(await screen.findByText('FY 2026')).toBeTruthy()

    window.dispatchEvent(new Event(AUTHENTICATION_FAILURE_EVENT))

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('clears local authentication on sign out', async () => {
    const user = userEvent.setup()
    authenticateAs('EMPLOYEE')
    authApi.logout.mockResolvedValue({ message: 'Logged out successfully.' })
    renderApp('/employee/dashboard')

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(authApi.logout).toHaveBeenCalledOnce()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('keeps the supported role set and role checks backend-driven', () => {
    expect(USER_ROLES).toEqual(['HR', 'MANAGER', 'EMPLOYEE'])
    expect(hasAnyRole(users.EMPLOYEE, ['EMPLOYEE'])).toBe(true)
    expect(hasAnyRole(users.EMPLOYEE, ['HR'])).toBe(false)
  })
})
