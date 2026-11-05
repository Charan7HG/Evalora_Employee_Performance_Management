import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('../../api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(),
  getManagerDashboard: vi.fn(),
  getEmployeeDashboard: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE' },
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

function renderApp(path, role) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'dashboard.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('role dashboards', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getHrDashboard.mockResolvedValue(hrData)
    dashboardApi.getManagerDashboard.mockResolvedValue(managerData)
  })

  it('links HR counters to their matching lists and the Results by Cycle page', async () => {
    renderApp('/hr/dashboard', 'HR')

    expect(await screen.findByText('Organization-wide performance activity.')).toBeTruthy()
    expect((await screen.findByRole('link', { name: /Active employees/ })).getAttribute('href'))
      .toBe('/hr/employees')
    expect(screen.getByRole('link', { name: /Active managers/ }).getAttribute('href'))
      .toBe('/hr/managers')
    expect(screen.getByRole('link', { name: /Performance cycles/ }).getAttribute('href'))
      .toBe('/hr/cycles')
    expect(screen.getByRole('link', { name: /Pending HR reviews/ }).getAttribute('href'))
      .toBe('/hr/assessment-status/hr-review-pending')
    expect(screen.getByRole('link', { name: /Awaiting self-assessment/ }).getAttribute('href'))
      .toBe('/hr/assessment-status/self-assessment-pending')
    expect(screen.getByRole('link', { name: /Pending manager reviews/ }).getAttribute('href'))
      .toBe('/hr/assessment-status/manager-review-pending')
    expect(screen.getByRole('link', { name: /Completed assessments/ }).getAttribute('href'))
      .toBe('/hr/assessment-status/completed')
    expect(screen.getByRole('link', { name: /Published results/ }).getAttribute('href'))
      .toBe('/hr/results/browse')
  })

  it('links each manager counter to a manager-scoped list', async () => {
    renderApp('/manager/dashboard', 'MANAGER')

    expect(await screen.findByText('Your current team and assessment workload.')).toBeTruthy()
    expect((await screen.findByRole('link', { name: /Assessments awaiting manager review/ }))
      .getAttribute('href')).toBe('/manager/assessment-status/manager-review-pending')
    expect(screen.getByRole('link', { name: /Published results/ }).getAttribute('href'))
      .toBe('/manager/results')
    expect(screen.getByRole('link', { name: /Active direct reports/ }).getAttribute('href'))
      .toBe('/manager/direct-reports')
    expect(screen.getByRole('link', { name: /awaiting self-assessment/i }).getAttribute('href'))
      .toBe('/manager/assessment-status/self-assessment-pending')
    expect(screen.getByRole('link', { name: /awaiting HR review/i }).getAttribute('href'))
      .toBe('/manager/assessment-status/hr-review-pending')
    expect(screen.getByRole('link', { name: /Completed assessments/ }).getAttribute('href'))
      .toBe('/manager/assessment-status/completed')
  })

  it('shows the employee reporting manager from the dashboard API', async () => {
    dashboardApi.getEmployeeDashboard.mockResolvedValue({
      currentCycle: null,
      latestPublishedResult: null,
      reportingManager: { managerName: 'Rina Lead', employeeCode: 'MGR-777' },
    })
    renderApp('/employee/dashboard', 'EMPLOYEE')

    expect(await screen.findByRole('heading', { name: 'Reporting Manager' })).toBeTruthy()
    expect(screen.getByText('Rina Lead')).toBeTruthy()
    expect(screen.getByText('MGR-777')).toBeTruthy()
  })

  it('shows an empty state when no reporting manager is assigned', async () => {
    dashboardApi.getEmployeeDashboard.mockResolvedValue({
      currentCycle: null,
      latestPublishedResult: null,
      reportingManager: null,
    })
    renderApp('/employee/dashboard', 'EMPLOYEE')

    expect(await screen.findByText('No reporting manager is currently assigned to you.'))
      .toBeTruthy()
  })
})
