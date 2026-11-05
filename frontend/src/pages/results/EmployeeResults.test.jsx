import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as resultApi from '../../api/resultApi.js'
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

vi.mock('../../api/resultApi.js', () => ({
  getHrResultQueue: vi.fn(),
  getHrResult: vi.fn(),
  publishHrResult: vi.fn(),
  publishHrCycleResults: vi.fn(),
  getEmployeeResults: vi.fn(),
  getEmployeeResult: vi.fn(),
  getManagerResults: vi.fn(),
  getManagerResult: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE' },
}

const publishedResult = {
  resultId: 61,
  assignmentId: 41,
  employee: {
    employeeId: 41,
    employeeCode: 'EMP-041',
    fullName: 'Eve Employee',
    department: 'Engineering',
    designation: 'Engineer',
  },
  cycle: {
    cycleId: 8,
    cycleName: 'Annual 2026',
    startDate: '2026-01-01',
    endDate: '2026-12-31',
  },
  overallScore: 4.1,
  grade: 'EXCELLENT',
  calculationMode: 'AVERAGE',
  status: 'PUBLISHED',
  hrFinalizedAt: '2026-11-02T11:00:00Z',
  publishedAt: '2026-11-03T09:00:00Z',
  items: [
    {
      employeeKpiId: 100,
      kpiName: 'Delivery Quality',
      weightage: null,
      selfRating: 4,
      selfComment: 'Delivered on time.',
      managerRating: 4,
      managerComment: 'Reliable delivery.',
      hrRating: 5,
      hrComment: 'Excellent delivery.',
      finalScore: 4.5,
    },
  ],
}

function renderApp(path, role = 'EMPLOYEE', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'employee-result-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

describe('employee performance results', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getEmployeeDashboard.mockResolvedValue({})
    resultApi.getEmployeeResults.mockResolvedValue([publishedResult])
    resultApi.getEmployeeResult.mockResolvedValue(publishedResult)
  })

  afterEach(() => vi.restoreAllMocks())

  it('lists only the published results returned for the authenticated employee', async () => {
    renderApp('/employee/results')

    expect(await screen.findByRole('heading', { name: 'Your published results' })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(await screen.findByText('4.1')).toBeTruthy()
    expect(await screen.findByText('EXCELLENT')).toBeTruthy()
    expect(await screen.findByText('2026-11-03T09:00:00Z')).toBeTruthy()
    expect(screen.queryByText('DRAFT')).toBeNull()
    expect(await screen.findAllByRole('link', { name: 'View result' })).toHaveLength(1)
    expect((await screen.findByRole('link', { name: 'View result' })).getAttribute('href'))
      .toBe('/employee/results/61')
    expect(screen.getByRole('link', { name: 'Results' }).getAttribute('href'))
      .toBe('/employee/results')
    expect(resultApi.getEmployeeResults).toHaveBeenCalledWith()
  })

  it('shows an explicit empty published-result state', async () => {
    resultApi.getEmployeeResults.mockResolvedValueOnce([])
    renderApp('/employee/results')

    expect(await screen.findByText('You do not have any published performance results yet.'))
      .toBeTruthy()
  })

  it('shows loading and retries a failed result list request', async () => {
    const user = userEvent.setup()
    resultApi.getEmployeeResults
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce([publishedResult])
    renderApp('/employee/results')

    expect(await screen.findByText('Loading…')).toBeTruthy()
    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(resultApi.getEmployeeResults).toHaveBeenCalledTimes(2)
  })

  it.each([
    [401, 'Your session has expired. Please sign in again.'],
    [403, 'You do not have permission to perform this action.'],
  ])('shows the shared authorization message for HTTP %s', async (status, message) => {
    resultApi.getEmployeeResults.mockRejectedValueOnce({ response: { status } })
    renderApp('/employee/results')

    expect(await screen.findByText(message)).toBeTruthy()
  })

  it('shows backend-provided scores and KPI details as read-only', async () => {
    renderApp('/employee/results/61')

    expect(await screen.findByRole('heading', { name: 'Performance result', level: 1 })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(await screen.findByText('4.1')).toBeTruthy()
    expect(await screen.findByText('EXCELLENT')).toBeTruthy()
    expect(await screen.findByText('Average (unweighted)')).toBeTruthy()
    expect(await screen.findByText('Delivery Quality')).toBeTruthy()
    expect(await screen.findByText('Unweighted')).toBeTruthy()
    expect(await screen.findByText('4.5')).toBeTruthy()
    expect(await screen.findByText('Delivered on time.')).toBeTruthy()
    expect(await screen.findByText('Reliable delivery.')).toBeTruthy()
    expect(await screen.findByText('Excellent delivery.')).toBeTruthy()
    expect(screen.getByText('Published performance results are read-only.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /publish/i })).toBeNull()
    expect(screen.queryByRole('link', { name: /publish/i })).toBeNull()
    expect(resultApi.getEmployeeResult).toHaveBeenCalledWith('61')
  })

  it('hides unknown, unpublished or another employee result as not found', async () => {
    resultApi.getEmployeeResult.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Performance result not found.' } },
    })
    renderApp('/employee/results/999')

    expect(await screen.findByText(
      'This performance result was not found or is not available to you.',
    )).toBeTruthy()
    expect(screen.getAllByText(
      'This performance result was not found or is not available to you.',
    )).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it('protects employee result routes from other roles and unauthenticated visitors', async () => {
    for (const role of ['HR', 'MANAGER']) {
      const { unmount } = renderApp('/employee/results', role)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      unmount()
    }
    expect(resultApi.getEmployeeResults).not.toHaveBeenCalled()

    localStorage.clear()
    vi.resetAllMocks()
    renderApp('/employee/results', 'EMPLOYEE', false)
    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(resultApi.getEmployeeResults).not.toHaveBeenCalled()
  })

  it('refreshes a result detail after a retryable error', async () => {
    const user = userEvent.setup()
    resultApi.getEmployeeResult
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce(publishedResult)
    renderApp('/employee/results/61')

    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    await waitFor(() => expect(resultApi.getEmployeeResult).toHaveBeenCalledTimes(2))
  })
})
