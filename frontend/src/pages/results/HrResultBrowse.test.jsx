import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as cycleApi from '../../api/cycleApi.js'
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

vi.mock('../../api/cycleApi.js', () => ({
  getCycles: vi.fn(),
  getCycleById: vi.fn(),
  getCycleKpis: vi.fn(),
  getCycleEmployees: vi.fn(),
  configureCycleKpis: vi.fn(),
  configureCycleEmployees: vi.fn(),
  publishCycle: vi.fn(),
  createCycle: vi.fn(),
  updateCycle: vi.fn(),
}))

vi.mock('../../api/resultApi.js', () => ({
  getHrResultQueue: vi.fn(),
  getHrResult: vi.fn(),
  getHrCycleResults: vi.fn(),
  publishHrResult: vi.fn(),
  publishHrCycleResults: vi.fn(),
  getEmployeeResults: vi.fn(),
  getEmployeeResult: vi.fn(),
  getManagerResults: vi.fn(),
  getManagerResult: vi.fn(),
}))

const cycles = [
  {
    id: 8,
    name: 'Annual 2026',
    status: 'PUBLISHED',
    startDate: '2026-01-01',
    endDate: '2026-12-31',
  },
  {
    id: 7,
    name: 'Annual 2025',
    status: 'COMPLETED',
    startDate: '2025-01-01',
    endDate: '2025-12-31',
  },
]

const currentCycleResults = [
  {
    resultId: 51,
    status: 'PUBLISHED',
    publishedAt: '2026-11-02T10:00:00Z',
    overallScore: 3.75,
    grade: 'VERY_GOOD',
    employee: {
      employeeId: 21,
      employeeCode: 'EMP-021',
      fullName: 'Eve Employee',
      department: 'Engineering',
      designation: 'Engineer',
    },
  },
  {
    resultId: 52,
    status: 'DRAFT',
    publishedAt: null,
    overallScore: 4.1,
    grade: 'EXCELLENT',
    employee: {
      employeeId: 22,
      employeeCode: 'EMP-022',
      fullName: 'Oscar Other',
      department: 'Engineering',
      designation: 'Engineer',
    },
  },
  {
    resultId: 53,
    status: 'PUBLISHED',
    publishedAt: '2026-11-02T10:00:00Z',
    overallScore: 3.2,
    grade: 'GOOD',
    employee: {
      employeeId: 23,
      employeeCode: 'QA-001',
      fullName: 'Quinn Tester',
      department: 'QA',
      designation: 'QA Analyst',
    },
  },
]

const historicalResults = [
  {
    resultId: 41,
    status: 'PUBLISHED',
    publishedAt: '2025-11-02T10:00:00Z',
    overallScore: 4.4,
    grade: 'EXCELLENT',
    employee: {
      employeeId: 21,
      employeeCode: 'EMP-021',
      fullName: 'Eve Employee',
      department: 'Engineering',
      designation: 'Engineer',
    },
  },
]

function renderApp(path = '/hr/results/browse') {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'hr.jwt')
  authApi.getCurrentUser.mockResolvedValue({ id: 1, email: 'hr@example.test', role: 'HR' })
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('HR results by cycle', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    cycleApi.getCycles.mockResolvedValue(cycles)
    resultApi.getHrCycleResults.mockImplementation(async (cycleId) => (
      String(cycleId) === '7' ? historicalResults : currentCycleResults
    ))
  })

  it('defaults to the current cycle and marks it in the cycle selector', async () => {
    renderApp()

    expect(await screen.findByRole('heading', { name: 'Results by cycle' })).toBeTruthy()
    await waitFor(() => expect(resultApi.getHrCycleResults).toHaveBeenCalledWith('8'))
    expect(screen.getByText(/Annual 2026 \(current cycle\)/)).toBeTruthy()
    const selector = screen.getByLabelText('Performance cycle')
    expect(selector.value).toBe('8')
    expect(within(selector).getByRole('option', { name: /Annual 2026.*current cycle/ })).toBeTruthy()
  })

  it('shows every result of the selected cycle with real data only', async () => {
    renderApp()

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(screen.getByText('Oscar Other')).toBeTruthy()
    expect(screen.getByText('Quinn Tester')).toBeTruthy()
    expect(screen.getByText('3.75')).toBeTruthy()
    expect(screen.getByText('VERY GOOD')).toBeTruthy()
    expect(screen.getAllByText('2026-11-02T10:00:00Z')).toHaveLength(2)
    expect(screen.getAllByText('DRAFT')).toHaveLength(1)
    const link = screen.getAllByRole('link', { name: 'View result' })[0]
    expect(link.getAttribute('href')).toBe('/hr/results/51')
  })

  it('filters by department using the departments returned by the backend', async () => {
    const user = userEvent.setup()
    renderApp()
    await screen.findByText('Eve Employee')

    const department = screen.getByLabelText('Department')
    expect(within(department).getAllByRole('option').map((option) => option.value))
      .toEqual(['', 'Engineering', 'QA'])

    await user.selectOptions(department, 'QA')

    expect(screen.queryByText('Eve Employee')).toBeNull()
    expect(screen.queryByText('Oscar Other')).toBeNull()
    expect(screen.getByText('Quinn Tester')).toBeTruthy()
    expect(resultApi.getHrCycleResults).toHaveBeenCalledTimes(1)
  })

  it('searches employees within the selected cycle', async () => {
    const user = userEvent.setup()
    renderApp()
    await screen.findByText('Eve Employee')

    await user.type(screen.getByLabelText('Search employees'), 'oscar')

    expect(screen.getByText('Oscar Other')).toBeTruthy()
    expect(screen.queryByText('Eve Employee')).toBeNull()
    expect(screen.queryByText('Quinn Tester')).toBeNull()
  })

  it('switches to a historical cycle without mixing results', async () => {
    const user = userEvent.setup()
    renderApp()
    await screen.findByText('Eve Employee')

    await user.selectOptions(screen.getByLabelText('Performance cycle'), '7')

    await waitFor(() => expect(resultApi.getHrCycleResults).toHaveBeenCalledWith('7'))
    expect(await screen.findByText(/Annual 2025 \(historical cycle\)/)).toBeTruthy()
    await waitFor(() => expect(screen.queryByText('Quinn Tester')).toBeNull())
    expect(screen.queryByText('3.75')).toBeNull()
    expect(screen.getByText('4.4')).toBeTruthy()
    expect(screen.getByText('2025-11-02T10:00:00Z')).toBeTruthy()
  })

  it('shows an empty state when the selected cycle has no results', async () => {
    resultApi.getHrCycleResults.mockResolvedValue([])
    renderApp()

    expect(await screen.findByText(
      'This cycle has no results yet. Results appear after HR finalizes an assessment.',
    )).toBeTruthy()
  })

  it('shows the backend failure with a retry action', async () => {
    resultApi.getHrCycleResults.mockRejectedValueOnce({ response: { status: 500 } })
    resultApi.getHrCycleResults.mockResolvedValue(currentCycleResults)
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
  })
})
