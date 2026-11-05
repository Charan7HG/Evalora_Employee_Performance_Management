import { render, screen, waitFor, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
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

const draftResult = {
  resultId: 51,
  assignmentId: 31,
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
  overallScore: 3.75,
  grade: 'VERY_GOOD',
  calculationMode: 'WEIGHTED',
  status: 'DRAFT',
  hrFinalizedAt: '2026-11-02T11:00:00Z',
  publishedAt: null,
  items: [
    {
      employeeKpiId: 100,
      kpiName: 'Delivery Quality',
      weightage: 60,
      selfRating: 4,
      selfComment: 'Delivered on time.',
      managerRating: 3,
      managerComment: 'Good delivery.',
      hrRating: 4,
      hrComment: 'Consistent delivery.',
      finalScore: 3.55,
    },
    {
      employeeKpiId: 101,
      kpiName: 'Quality',
      weightage: 40,
      selfRating: 5,
      selfComment: 'Solid work.',
      managerRating: 4,
      managerComment: 'Strong quality.',
      hrRating: null,
      hrComment: null,
      finalScore: 4,
    },
  ],
}

const secondDraft = {
  ...draftResult,
  resultId: 52,
  assignmentId: 32,
  employee: {
    ...draftResult.employee,
    employeeId: 42,
    employeeCode: 'EMP-042',
    fullName: 'Sam Second',
  },
  overallScore: 3.5,
  grade: 'GOOD',
  items: [],
}

function renderApp(path, role = 'HR', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'hr-result-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

describe('HR performance results', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getHrDashboard.mockResolvedValue({})
    resultApi.getHrResultQueue.mockResolvedValue([draftResult, secondDraft])
    resultApi.getHrResult.mockResolvedValue(draftResult)
    resultApi.publishHrCycleResults.mockResolvedValue({
      cycleId: 8,
      publishedCount: 2,
      skippedCount: 1,
      publishedResultIds: [51, 52],
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('lists backend-eligible DRAFT results, grouped by performance cycle', async () => {
    renderApp('/hr/results')

    expect(await screen.findByRole('heading', { name: 'Results awaiting publication' })).toBeTruthy()
    expect(await screen.findByRole('heading', { name: 'Annual 2026' })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Sam Second')).toBeTruthy()
    expect(await screen.findByText('EMP-041')).toBeTruthy()
    expect(await screen.findByText('2 results awaiting publication')).toBeTruthy()
    expect(await screen.findAllByText('DRAFT')).toHaveLength(2)
    expect(await screen.findByText('3.75')).toBeTruthy()
    expect(await screen.findByText('VERY GOOD')).toBeTruthy()
    expect(await screen.findByText('GOOD')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Results awaiting publication' }).getAttribute('href')).toBe('/hr/results')
    expect(await screen.findAllByRole('link', { name: 'Review result' })).toHaveLength(2)
    expect((await screen.findAllByRole('link', { name: 'Review result' })).map((link) => (
      link.getAttribute('href')
    ))).toEqual(['/hr/results/51', '/hr/results/52'])
    expect(resultApi.getHrResultQueue).toHaveBeenCalledOnce()
  })

  it('shows an explicit empty publication queue', async () => {
    resultApi.getHrResultQueue.mockResolvedValueOnce([])
    renderApp('/hr/results')

    expect(await screen.findByText('There are no performance results awaiting publication.'))
      .toBeTruthy()
  })

  it('shows and retries a queue loading error', async () => {
    const user = userEvent.setup()
    resultApi.getHrResultQueue
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce([draftResult])

    renderApp('/hr/results')
    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(resultApi.getHrResultQueue).toHaveBeenCalledTimes(2)
  })

  it('requires confirmation before bulk publication and does nothing when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/hr/results')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', {
      name: 'Publish eligible results for Annual 2026',
    }))

    expect(dialogText()).toContain('Annual 2026')
    expect(dialogText()).toContain('cannot be unpublished')
    await cancelDialog(user)
    expect(resultApi.publishHrCycleResults).not.toHaveBeenCalled()
  })

  it('publishes a cycle, reports backend counts and refreshes the queue', async () => {
    const user = userEvent.setup()
    resultApi.getHrResultQueue
      .mockResolvedValueOnce([draftResult, secondDraft])
      .mockResolvedValueOnce([secondDraft])
    renderApp('/hr/results')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', {
      name: 'Publish eligible results for Annual 2026',
    }))
    await confirmDialog(user, 'Publish results')

    expect(await screen.findByText(/Published 2 result\(s\) from "Annual 2026"/)).toBeTruthy()
    expect(screen.getByText(/1 assignment\(s\) were skipped as ineligible/)).toBeTruthy()
    expect(resultApi.publishHrCycleResults).toHaveBeenCalledWith(8)
    await waitFor(() => expect(resultApi.getHrResultQueue).toHaveBeenCalledTimes(2))
  })

  it('prevents duplicate bulk publication while a request is pending', async () => {
    const user = userEvent.setup()
    let resolvePublication
    resultApi.publishHrCycleResults.mockReturnValueOnce(
      new Promise((resolve) => { resolvePublication = resolve }),
    )
    renderApp('/hr/results')
    await screen.findByText('Eve Employee')

    const button = screen.getByRole('button', {
      name: 'Publish eligible results for Annual 2026',
    })
    await user.click(button)
    await user.click(button)
    await confirmDialog(user, 'Publish results')

    expect(resultApi.publishHrCycleResults).toHaveBeenCalledOnce()
    resolvePublication({
      cycleId: 8,
      publishedCount: 2,
      skippedCount: 0,
      publishedResultIds: [51, 52],
    })
    expect(await screen.findByText(/Published 2 result\(s\)/)).toBeTruthy()
  })

  it('handles a bulk publication conflict and refreshes the queue', async () => {
    const user = userEvent.setup()
    resultApi.publishHrCycleResults.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { message: 'Only results in a published cycle can be published.' },
      },
    })
    renderApp('/hr/results')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', {
      name: 'Publish eligible results for Annual 2026',
    }))
    await confirmDialog(user, 'Publish results')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Only results in a published cycle can be published.')
    await waitFor(() => expect(resultApi.getHrResultQueue).toHaveBeenCalledTimes(2))
  })

  it('shows the backend score, ratings and KPI values without recalculating them', async () => {
    renderApp('/hr/results/51')

    expect(await screen.findByRole('heading', { name: 'Performance result', level: 1 })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(await screen.findByText('3.75')).toBeTruthy()
    expect(await screen.findByText('VERY GOOD')).toBeTruthy()
    expect(await screen.findByText('Weighted')).toBeTruthy()
    expect(await screen.findByText('Delivery Quality')).toBeTruthy()
    expect(await screen.findByText('60%')).toBeTruthy()
    expect(await screen.findByText('3.55')).toBeTruthy()
    expect(await screen.findByText('Delivered on time.')).toBeTruthy()
    expect(await screen.findByText('Good delivery.')).toBeTruthy()
    expect(await screen.findByText('Consistent delivery.')).toBeTruthy()
    expect(screen.getAllByText('Employee self-rating (reference only)')).toHaveLength(2)
    expect(screen.getByText(/does not calculate, reconstruct or approximate scores/i)).toBeTruthy()
    expect(resultApi.getHrResult).toHaveBeenCalledWith('51')
  })

  it('requires confirmation before individual publication and does nothing when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/hr/results/51')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', { name: 'Publish result' }))

    expect(dialogText()).toContain('Eve Employee')
    expect(dialogText()).toContain('Annual 2026')
    await cancelDialog(user)
    expect(resultApi.publishHrResult).not.toHaveBeenCalled()
  })

  it('publishes one result, refreshes it and removes the publication action', async () => {
    const user = userEvent.setup()
    resultApi.publishHrResult.mockResolvedValueOnce({
      ...draftResult,
      status: 'PUBLISHED',
      publishedAt: '2026-11-03T09:00:00Z',
    })
    renderApp('/hr/results/51')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', { name: 'Publish result' }))
    await confirmDialog(user, 'Publish result')

    expect(await screen.findByText(/Performance result published/i)).toBeTruthy()
    expect(resultApi.publishHrResult).toHaveBeenCalledWith(51)
    expect(screen.queryByRole('button', { name: 'Publish result' })).toBeNull()
    expect(screen.getByText('2026-11-03T09:00:00Z')).toBeTruthy()
    await waitFor(() => expect(resultApi.getHrResult).toHaveBeenCalledTimes(2))
  })

  it('prevents duplicate individual publication while a request is pending', async () => {
    const user = userEvent.setup()
    let resolvePublication
    resultApi.publishHrResult.mockReturnValueOnce(
      new Promise((resolve) => { resolvePublication = resolve }),
    )
    renderApp('/hr/results/51')
    await screen.findByText('Eve Employee')

    const button = screen.getByRole('button', { name: 'Publish result' })
    await user.click(button)
    await user.click(button)
    await confirmDialog(user, 'Publish result')

    expect(resultApi.publishHrResult).toHaveBeenCalledOnce()
    resolvePublication({ ...draftResult, status: 'PUBLISHED', publishedAt: '2026-11-03T09:00:00Z' })
    expect(await screen.findByText(/Performance result published/i)).toBeTruthy()
  })

  it('handles a publication conflict by refreshing the affected result', async () => {
    const user = userEvent.setup()
    resultApi.publishHrResult.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { message: 'The performance result has already been published or is inconsistent.' },
      },
    })
    renderApp('/hr/results/51')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', { name: 'Publish result' }))
    await confirmDialog(user, 'Publish result')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('already been published or is inconsistent')
    await waitFor(() => expect(resultApi.getHrResult).toHaveBeenCalledTimes(2))
  })

  it('shows a backend validation message for a 400 publication response', async () => {
    const user = userEvent.setup()
    resultApi.publishHrResult.mockRejectedValueOnce({
      response: { status: 400, data: { message: 'The result cannot be published.' } },
    })
    renderApp('/hr/results/51')
    await screen.findByText('Eve Employee')

    await user.click(screen.getByRole('button', { name: 'Publish result' }))
    await confirmDialog(user, 'Publish result')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The result cannot be published.')
    expect(resultApi.getHrResult).toHaveBeenCalledOnce()
  })

  it('shows a single not-found state with retry for an unknown result', async () => {
    resultApi.getHrResult.mockRejectedValueOnce({ response: { status: 404 } })
    renderApp('/hr/results/999')

    expect(await screen.findByText('This performance result was not found.')).toBeTruthy()
    expect(screen.getAllByText('This performance result was not found.')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it('protects HR result routes from other roles and unauthenticated visitors', async () => {
    for (const role of ['MANAGER', 'EMPLOYEE']) {
      const { unmount } = renderApp('/hr/results', role)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      unmount()
    }
    expect(resultApi.getHrResultQueue).not.toHaveBeenCalled()

    localStorage.clear()
    vi.resetAllMocks()
    renderApp('/hr/results', 'HR', false)
    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(resultApi.getHrResultQueue).not.toHaveBeenCalled()
  })

  it('renders a published result read-only when the backend returns it', async () => {
    resultApi.getHrResult.mockResolvedValueOnce({
      ...draftResult,
      status: 'PUBLISHED',
      publishedAt: '2026-11-03T09:00:00Z',
    })
    renderApp('/hr/results/51')

    expect(await screen.findByText(/This performance result is published and read-only/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Publish result' })).toBeNull()
  })

  it('shows unpublished KPI ratings as not provided instead of zero', async () => {
    renderApp('/hr/results/51')

    const quality = (await screen.findByText('Quality')).closest('article')
    expect(within(quality).getByText('HR rating')).toBeTruthy()
    expect(within(quality).getAllByText('Not provided').length).toBeGreaterThan(0)
  })
})
