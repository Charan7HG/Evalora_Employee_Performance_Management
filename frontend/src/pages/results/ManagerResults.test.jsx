import { render, screen } from '@testing-library/react'
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

const teamResult = {
  resultId: 71,
  assignmentId: 51,
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
  overallScore: 3.9,
  grade: 'GOOD',
  calculationMode: 'WEIGHTED',
  status: 'PUBLISHED',
  hrFinalizedAt: '2026-11-02T11:00:00Z',
  publishedAt: '2026-11-03T09:00:00Z',
  items: [
    {
      employeeKpiId: 100,
      kpiName: 'Delivery Quality',
      weightage: 100,
      selfRating: 4,
      selfComment: 'Delivered on time.',
      managerRating: 4,
      managerComment: 'Reliable delivery.',
      hrRating: 4,
      hrComment: 'Consistent.',
      finalScore: 4,
    },
  ],
}

function renderApp(path, role = 'MANAGER', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'manager-result-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

describe('manager performance results', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getManagerDashboard.mockResolvedValue({})
    resultApi.getManagerResults.mockResolvedValue([teamResult])
    resultApi.getManagerResult.mockResolvedValue(teamResult)
  })

  afterEach(() => vi.restoreAllMocks())

  it('lists published results using only the backend manager ownership scope', async () => {
    renderApp('/manager/results')

    expect(await screen.findByRole('heading', { name: 'Published results for your team' }))
      .toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(await screen.findByText('3.9')).toBeTruthy()
    expect(await screen.findByText('GOOD')).toBeTruthy()
    expect(screen.queryByText('DRAFT')).toBeNull()
    expect(screen.getByRole('link', { name: 'View result' }).getAttribute('href'))
      .toBe('/manager/results/71')
    expect(screen.getByRole('link', { name: 'Results' }).getAttribute('href'))
      .toBe('/manager/results')
    expect(resultApi.getManagerResults).toHaveBeenCalledWith()
  })

  it('shows an explicit empty team-result state', async () => {
    resultApi.getManagerResults.mockResolvedValueOnce([])
    renderApp('/manager/results')

    expect(await screen.findByText('No published performance results are available for your team yet.'))
      .toBeTruthy()
  })

  it('shows and retries a team-result loading error', async () => {
    const user = userEvent.setup()
    resultApi.getManagerResults
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce([teamResult])
    renderApp('/manager/results')

    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(resultApi.getManagerResults).toHaveBeenCalledTimes(2)
  })

  it('shows backend scores and ratings read-only without publication controls', async () => {
    renderApp('/manager/results/71')

    expect(await screen.findByRole('heading', { name: 'Performance result', level: 1 })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(await screen.findByText('3.9')).toBeTruthy()
    expect(await screen.findByText('GOOD')).toBeTruthy()
    expect(await screen.findByText('100%')).toBeTruthy()
    expect(await screen.findByText('Reliable delivery.')).toBeTruthy()
    expect(await screen.findByText('Consistent.')).toBeTruthy()
    expect(screen.getByText('Published performance results are read-only.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /publish/i })).toBeNull()
    expect(resultApi.getManagerResult).toHaveBeenCalledWith('71')
  })

  it('hides another manager or unpublished result as not found', async () => {
    resultApi.getManagerResult.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Performance result not found.' } },
    })
    renderApp('/manager/results/999')

    expect(await screen.findByText(
      'This performance result was not found or is not available to you.',
    )).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it('shows the shared permission message for a 403 team-result response', async () => {
    resultApi.getManagerResults.mockRejectedValueOnce({ response: { status: 403 } })
    renderApp('/manager/results')

    expect(await screen.findByText('You do not have permission to perform this action.')).toBeTruthy()
  })

  it('protects manager result routes from other roles and unauthenticated visitors', async () => {
    for (const role of ['HR', 'EMPLOYEE']) {
      const { unmount } = renderApp('/manager/results', role)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      unmount()
    }
    expect(resultApi.getManagerResults).not.toHaveBeenCalled()

    localStorage.clear()
    vi.resetAllMocks()
    renderApp('/manager/results', 'MANAGER', false)
    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(resultApi.getManagerResults).not.toHaveBeenCalled()
  })
})
