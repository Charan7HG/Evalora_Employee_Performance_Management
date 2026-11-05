import { render, screen, waitFor, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as managerAssessmentApi from '../../api/managerAssessmentApi.js'
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

vi.mock('../../api/managerAssessmentApi.js', () => ({
  getManagerAssessmentQueue: vi.fn(),
  getManagerAssessment: vi.fn(),
  getManagerAssessmentStatus: vi.fn(),
  saveManagerAssessmentDraft: vi.fn(),
  submitManagerAssessment: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE' },
}

const queue = [{
  assignmentId: 31,
  employeeId: 41,
  employeeCode: 'EMP-041',
  employeeName: 'Eve Employee',
  cycleId: 8,
  cycleName: 'Annual 2026',
  selfSubmittedAt: '2026-11-02T10:00:00Z',
  assignmentStatus: 'MANAGER_REVIEW_PENDING',
}]

const assessment = {
  assignmentId: 31,
  cycleId: 8,
  cycleName: 'Annual 2026',
  cycleStatus: 'PUBLISHED',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  managerReviewDeadline: '2026-12-15',
  assignmentStatus: 'MANAGER_REVIEW_PENDING',
  selfSubmittedAt: '2026-11-02T10:00:00Z',
  managerSubmittedAt: null,
  editable: true,
  employee: {
    employeeId: 41,
    employeeCode: 'EMP-041',
    fullName: 'Eve Employee',
    department: 'Engineering',
    designation: 'Engineer',
  },
  kpis: [
    {
      employeeKpiId: 100,
      kpiId: 7,
      name: 'Delivery Quality',
      description: 'Meets agreed deadlines.',
      category: 'Delivery',
      weightage: 60,
      selfRating: 4,
      selfComment: 'Delivered on time.',
      managerRating: null,
      managerComment: null,
    },
    {
      employeeKpiId: 101,
      kpiId: 8,
      name: 'Quality',
      description: 'Work meets the quality bar.',
      category: 'Quality',
      weightage: 40,
      selfRating: 5,
      selfComment: 'Solid work.',
      managerRating: null,
      managerComment: null,
    },
  ],
}

function renderApp(path, role = 'MANAGER', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'manager-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

async function setAllRatings(user) {
  await user.selectOptions(screen.getByLabelText('Manager rating for Delivery Quality'), '3')
  await user.selectOptions(screen.getByLabelText('Manager rating for Quality'), '5')
  await user.click(screen.getByRole('button', { name: 'Save draft' }))
  await screen.findByText('Manager draft saved.')
}

describe('manager assessment workflow', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getManagerDashboard.mockResolvedValue({})
    managerAssessmentApi.getManagerAssessmentQueue.mockResolvedValue(queue)
    managerAssessmentApi.getManagerAssessment.mockResolvedValue(assessment)
    managerAssessmentApi.getManagerAssessmentStatus.mockResolvedValue({
      assignmentId: 31,
      cycleId: 8,
      assignmentStatus: 'HR_REVIEW_PENDING',
      totalKpis: 2,
      ratedKpis: 2,
      submitted: true,
      managerSubmittedAt: '2026-11-02T11:00:00Z',
    })
    managerAssessmentApi.saveManagerAssessmentDraft.mockResolvedValue(assessment)
    managerAssessmentApi.submitManagerAssessment.mockResolvedValue({
      assignmentId: 31,
      cycleId: 8,
      assignmentStatus: 'HR_REVIEW_PENDING',
      totalKpis: 2,
      ratedKpis: 2,
      submitted: true,
      managerSubmittedAt: '2026-11-02T11:00:00Z',
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads the manager queue and displays employee, cycle, status, and review action', async () => {
    const user = userEvent.setup()
    renderApp('/manager/assessments')

    expect(await screen.findByRole('heading', { name: 'Assessments awaiting your review' })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(screen.getByText('EMP-041')).toBeTruthy()
    expect(screen.getByText('Annual 2026')).toBeTruthy()
    expect(screen.getByText('MANAGER REVIEW PENDING')).toBeTruthy()
    expect(managerAssessmentApi.getManagerAssessmentQueue).toHaveBeenCalledOnce()
    expect(managerAssessmentApi.getManagerAssessmentQueue).toHaveBeenCalledWith()

    await user.click(screen.getByRole('link', { name: 'Review assessment' }))
    expect(await screen.findByRole('heading', { name: 'Manager assessment', level: 1 })).toBeTruthy()
    expect(managerAssessmentApi.getManagerAssessment).toHaveBeenCalledWith('31')
  })

  it('shows an explicit empty queue state', async () => {
    managerAssessmentApi.getManagerAssessmentQueue.mockResolvedValueOnce([])
    renderApp('/manager/assessments')

    expect(await screen.findByText('There are no employee assessments awaiting your review.'))
      .toBeTruthy()
  })

  it('shows queue loading and retries a retryable error', async () => {
    const user = userEvent.setup()
    let resolveQueue
    managerAssessmentApi.getManagerAssessmentQueue.mockReturnValueOnce(
      new Promise((resolve) => { resolveQueue = resolve }),
    )
    const { unmount: unmountInitial } = renderApp('/manager/assessments')
    expect(await screen.findByText('Loading…')).toBeTruthy()
    resolveQueue(queue)
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    unmountInitial()

    managerAssessmentApi.getManagerAssessmentQueue
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce(queue)
    const { unmount } = renderApp('/manager/assessments')
    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    unmount()
  })

  it('shows employee and cycle details with read-only self-ratings and manager inputs', async () => {
    renderApp('/manager/assessments/31')

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(screen.getByText('EMP-041')).toBeTruthy()
    expect(screen.getByText('2026-01-01 – 2026-12-31')).toBeTruthy()
    expect(screen.getByText('2026-12-15')).toBeTruthy()
    expect(screen.getByText('Meets agreed deadlines.')).toBeTruthy()
    expect(screen.getByText('60%')).toBeTruthy()
    expect(screen.getByText('Delivered on time.')).toBeTruthy()
    expect(screen.getAllByText('Employee self-rating (reference only)')).toHaveLength(2)
    expect(screen.getAllByText('Manager assessment')).toHaveLength(3)

    const managerRating = screen.getByLabelText('Manager rating for Delivery Quality')
    expect(within(managerRating).getAllByRole('option').map((option) => option.value))
      .toEqual(['', '1', '2', '3', '4', '5'])
    expect(screen.getByLabelText('Manager comment for Delivery Quality')).toBeTruthy()
    expect(screen.getByText(/Your ratings are stored separately from HR ratings/i)).toBeTruthy()
  })

  it('saves partial ratings and comments without changing read-only employee values', async () => {
    const user = userEvent.setup()
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await user.selectOptions(screen.getByLabelText('Manager rating for Delivery Quality'), '2')
    await user.type(screen.getByLabelText('Manager comment for Delivery Quality'), 'Needs follow-up')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Manager draft saved.')).toBeTruthy()
    expect(managerAssessmentApi.saveManagerAssessmentDraft).toHaveBeenCalledWith(31, [
      { employeeKpiId: 100, rating: 2, comment: 'Needs follow-up' },
      { employeeKpiId: 101, rating: null, comment: '' },
    ])
    expect(screen.getByLabelText('Manager rating for Delivery Quality').value).toBe('2')
    expect(screen.getByLabelText('Manager comment for Delivery Quality').value).toBe('Needs follow-up')
    expect(screen.getByText('Delivered on time.')).toBeTruthy()
  })

  it('requires ratings for manager KPIs and submits directly without a draft save', async () => {
    const user = userEvent.setup()
    managerAssessmentApi.submitManagerAssessment.mockResolvedValueOnce({
      assignmentId: 31,
      assignmentStatus: 'HR_REVIEW_PENDING',
      submitted: true,
      ratedKpis: 2,
      totalKpis: 2,
    })
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await user.selectOptions(screen.getByLabelText('Manager rating for Delivery Quality'), '3')
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))
    expect((await screen.findByRole('alert')).textContent).toContain('for every assigned KPI')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(managerAssessmentApi.submitManagerAssessment).not.toHaveBeenCalled()

    await user.selectOptions(screen.getByLabelText('Manager rating for Quality'), '4')
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))
    await confirmDialog(user, 'Submit to HR')

    await waitFor(() => expect(managerAssessmentApi.submitManagerAssessment).toHaveBeenCalledOnce())
    expect(managerAssessmentApi.saveManagerAssessmentDraft).toHaveBeenCalledOnce()
    expect(managerAssessmentApi.saveManagerAssessmentDraft).toHaveBeenCalledWith(31, [
      { employeeKpiId: 100, rating: 3, comment: '' },
      { employeeKpiId: 101, rating: 4, comment: '' },
    ])
    expect(await screen.findByText('Manager assessment submitted to HR for review.')).toBeTruthy()
  })

  it('requires explicit confirmation and does not submit when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await setAllRatings(user)
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))

    expect(dialogText()).toContain('Eve Employee')
    expect(dialogText()).toContain('sent to HR for review')
    await cancelDialog(user)
    expect(managerAssessmentApi.submitManagerAssessment).not.toHaveBeenCalled()
  })

  it('submits a saved draft separately, refreshes status and makes fields read-only', async () => {
    const user = userEvent.setup()
    const savedAssessment = {
      ...assessment,
      kpis: assessment.kpis.map((kpi, index) => ({
        ...kpi,
        managerRating: index === 0 ? 3 : 5,
        managerComment: index === 0 ? 'Delivered well' : '',
      })),
    }
    managerAssessmentApi.getManagerAssessment.mockResolvedValue(savedAssessment)
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))
    await confirmDialog(user, 'Submit to HR')

    expect(await screen.findByText('Manager assessment submitted to HR for review.')).toBeTruthy()
    expect(managerAssessmentApi.submitManagerAssessment).toHaveBeenCalledOnce()
    expect(managerAssessmentApi.submitManagerAssessment).toHaveBeenCalledWith(31)
    expect(managerAssessmentApi.saveManagerAssessmentDraft).not.toHaveBeenCalled()
    expect(managerAssessmentApi.getManagerAssessmentStatus).toHaveBeenCalledWith(31)
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Submit assessment to HR' })).toBeNull()
    expect(screen.getByLabelText('Manager rating for Delivery Quality').closest('fieldset').disabled)
      .toBe(true)
    managerAssessmentApi.getManagerAssessmentQueue.mockResolvedValueOnce([])
    await user.click(screen.getByRole('link', { name: 'Back to review queue' }))
    expect(await screen.findByText('There are no employee assessments awaiting your review.'))
      .toBeTruthy()
    expect(managerAssessmentApi.getManagerAssessmentQueue).toHaveBeenCalledOnce()
  })

  it('prevents duplicate submissions while the request is pending', async () => {
    const user = userEvent.setup()
    managerAssessmentApi.getManagerAssessment.mockResolvedValue({
      ...assessment,
      kpis: assessment.kpis.map((kpi, index) => ({
        ...kpi,
        managerRating: index === 0 ? 3 : 5,
      })),
    })
    let resolveSubmit
    managerAssessmentApi.submitManagerAssessment.mockReturnValueOnce(
      new Promise((resolve) => { resolveSubmit = resolve }),
    )
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    const submitButton = screen.getByRole('button', { name: 'Submit assessment to HR' })
    await user.click(submitButton)
    await user.click(submitButton)
    await confirmDialog(user, 'Submit to HR')
    expect(managerAssessmentApi.submitManagerAssessment).toHaveBeenCalledOnce()
    resolveSubmit({
      assignmentId: 31,
      assignmentStatus: 'HR_REVIEW_PENDING',
      submitted: true,
      ratedKpis: 2,
      totalKpis: 2,
    })
    await screen.findByText('Manager assessment submitted to HR for review.')
  })

  it('preserves form values and reports backend errors after a failed draft save', async () => {
    const user = userEvent.setup()
    managerAssessmentApi.saveManagerAssessmentDraft.mockRejectedValueOnce({
      response: { status: 400, data: { message: 'Manager assessment rating must be a whole number between 1 and 5.' } },
    })
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await user.selectOptions(screen.getByLabelText('Manager rating for Delivery Quality'), '4')
    await user.type(screen.getByLabelText('Manager comment for Delivery Quality'), 'Keep my note')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect((await screen.findByRole('alert')).textContent).toContain('whole number between 1 and 5')
    expect(screen.getByLabelText('Manager rating for Delivery Quality').value).toBe('4')
    expect(screen.getByLabelText('Manager comment for Delivery Quality').value).toBe('Keep my note')
  })

  it('handles a 409 conflict by refreshing assessment state without discarding edits', async () => {
    const user = userEvent.setup()
    managerAssessmentApi.submitManagerAssessment.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'The manager assessment has already been submitted.' } },
    })
    const savedAssessment = {
      ...assessment,
      editable: false,
      assignmentStatus: 'HR_REVIEW_PENDING',
    }
    managerAssessmentApi.getManagerAssessment
      .mockResolvedValueOnce(assessment)
      .mockResolvedValue(savedAssessment)
    renderApp('/manager/assessments/31')
    await screen.findByText('Eve Employee')
    await setAllRatings(user)
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))
    await confirmDialog(user, 'Submit to HR')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The manager assessment has already been submitted.')
    await waitFor(() => expect(managerAssessmentApi.getManagerAssessment).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'Submit assessment to HR' })).toBeNull()
  })

  it.each([
    [401, 'Your session has expired. Please sign in again.'],
    [403, 'You do not have permission to perform this action.'],
    [404, 'Manager assessment not found.'],
  ])('shows the existing error message for a %s assessment response', async (status, message) => {
    managerAssessmentApi.getManagerAssessment.mockRejectedValueOnce({
      response: { status, data: { message } },
    })
    renderApp('/manager/assessments/999')

    expect((await screen.findByRole('alert')).textContent).toContain(message)
  })

  it('returns a 404 for assessment IDs unavailable to this manager and protects manager routes', async () => {
    managerAssessmentApi.getManagerAssessment.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Manager assessment not found.' } },
    })
    renderApp('/manager/assessments/99')
    expect((await screen.findByRole('alert')).textContent).toContain('Manager assessment not found.')
    expect(managerAssessmentApi.getManagerAssessment).toHaveBeenCalledWith('99')

    for (const role of ['HR', 'EMPLOYEE']) {
      const { unmount } = renderApp('/manager/assessments', role)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      unmount()
    }
    expect(managerAssessmentApi.getManagerAssessmentQueue).not.toHaveBeenCalled()
  })

  it('redirects unauthenticated queue visits to login', async () => {
    renderApp('/manager/assessments', 'MANAGER', false)
    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(managerAssessmentApi.getManagerAssessmentQueue).not.toHaveBeenCalled()
  })

  it('uses only manager-assigned queue items as review links', async () => {
    managerAssessmentApi.getManagerAssessmentQueue.mockResolvedValueOnce([
      queue[0],
      { ...queue[0], assignmentId: 32, employeeName: 'Another Assigned Employee' },
    ])
    renderApp('/manager/assessments')

    const links = await screen.findAllByRole('link', { name: 'Review assessment' })
    expect(links.map((link) => link.getAttribute('href')))
      .toEqual(['/manager/assessments/31', '/manager/assessments/32'])
  })
})
