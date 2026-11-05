import { render, screen, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as hrAssessmentApi from '../../api/hrAssessmentApi.js'
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

vi.mock('../../api/hrAssessmentApi.js', () => ({
  getHrAssessmentQueue: vi.fn(),
  getHrAssessment: vi.fn(),
  getHrAssessmentStatus: vi.fn(),
  saveHrAssessmentDraft: vi.fn(),
  finalizeHrAssessment: vi.fn(),
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
  department: 'Engineering',
  designation: 'Engineer',
  cycleId: 8,
  cycleName: 'Annual 2026',
  managerSubmittedAt: '2026-11-02T10:00:00Z',
  assignmentStatus: 'HR_REVIEW_PENDING',
}]

const assessment = {
  assignmentId: 31,
  cycleId: 8,
  cycleName: 'Annual 2026',
  cycleStatus: 'PUBLISHED',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  hrReviewDeadline: '2026-12-20',
  assignmentStatus: 'HR_REVIEW_PENDING',
  selfSubmittedAt: '2026-11-01T10:00:00Z',
  managerSubmittedAt: '2026-11-02T10:00:00Z',
  hrFinalizedAt: null,
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
      kpiType: 'HR',
      selfRating: 4,
      selfComment: 'Delivered on time.',
      managerRating: 3,
      managerComment: 'Good delivery.',
      hrRating: null,
      hrComment: null,
    },
    {
      employeeKpiId: 101,
      kpiId: 8,
      name: 'Quality',
      description: 'Work meets the quality bar.',
      category: 'Quality',
      weightage: 40,
      kpiType: 'MANAGER',
      selfRating: 5,
      selfComment: 'Solid work.',
      managerRating: 4,
      managerComment: 'Consistently strong.',
      hrRating: null,
      hrComment: null,
    },
  ],
}

function renderApp(path, role = 'HR', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'hr-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

async function setAllRatings(user) {
  await user.selectOptions(screen.getByLabelText('HR rating for Delivery Quality'), '3')
}

describe('HR assessment workflow', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getHrDashboard.mockResolvedValue({})
    hrAssessmentApi.getHrAssessmentQueue.mockResolvedValue(queue)
    hrAssessmentApi.getHrAssessment.mockResolvedValue(assessment)
    hrAssessmentApi.getHrAssessmentStatus.mockResolvedValue({
      assignmentId: 31,
      cycleId: 8,
      assignmentStatus: 'COMPLETED',
      totalKpis: 2,
      ratedKpis: 2,
      submitted: true,
      hrFinalizedAt: '2026-11-02T11:00:00Z',
    })
    hrAssessmentApi.saveHrAssessmentDraft.mockResolvedValue(assessment)
    hrAssessmentApi.finalizeHrAssessment.mockResolvedValue({
      assignmentId: 31,
      cycleId: 8,
      assignmentStatus: 'COMPLETED',
      totalKpis: 2,
      ratedKpis: 2,
      submitted: true,
      hrFinalizedAt: '2026-11-02T11:00:00Z',
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads the authenticated HR queue and displays employee, cycle, status, and action', async () => {
    renderApp('/hr/assessments')

    expect(await screen.findByRole('heading', { name: 'Assessments awaiting HR review' })).toBeTruthy()
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(screen.getByText('EMP-041')).toBeTruthy()
    expect(screen.getByText('Engineering')).toBeTruthy()
    expect(screen.getByText('Annual 2026')).toBeTruthy()
    expect(screen.getByText('HR REVIEW PENDING')).toBeTruthy()
    expect(hrAssessmentApi.getHrAssessmentQueue).toHaveBeenCalledWith()
  })

  it('shows an explicit empty queue state', async () => {
    hrAssessmentApi.getHrAssessmentQueue.mockResolvedValueOnce([])
    renderApp('/hr/assessments')

    expect(await screen.findByText('There are no employee assessments awaiting HR review.')).toBeTruthy()
  })

  it('shows loading and retries a retryable queue error', async () => {
    const user = userEvent.setup()
    hrAssessmentApi.getHrAssessmentQueue.mockRejectedValueOnce({ response: { status: 500 } })
    renderApp('/hr/assessments')

    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(hrAssessmentApi.getHrAssessmentQueue).toHaveBeenCalledTimes(2)
  })

  it('shows one not-available message and still offers retry for a missing queue', async () => {
    hrAssessmentApi.getHrAssessmentQueue.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'HR assessment queue not found.' } },
    })
    renderApp('/hr/assessments')

    expect(await screen.findByText('The HR review queue is not available.')).toBeTruthy()
    expect(screen.getAllByText('The HR review queue is not available.')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it.each([
    [401, 'Your session has expired. Please sign in again.'],
    [403, 'You do not have permission to perform this action.'],
  ])('shows the shared authorization message for HTTP %s', async (status, message) => {
    hrAssessmentApi.getHrAssessmentQueue.mockRejectedValueOnce({ response: { status } })
    renderApp('/hr/assessments')

    expect(await screen.findByText(message)).toBeTruthy()
  })

  it('blocks non-HR users from the HR assessment route', async () => {
    renderApp('/hr/assessments', 'MANAGER')

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(hrAssessmentApi.getHrAssessmentQueue).not.toHaveBeenCalled()
  })

  it('shows employee and cycle details with employee and manager ratings read-only', async () => {
    renderApp('/hr/assessments/31')

    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(screen.getByText('EMP-041')).toBeTruthy()
    expect(screen.getByText('2026-01-01 – 2026-12-31')).toBeTruthy()
    expect(screen.getByText('2026-12-20')).toBeTruthy()
    expect(screen.getByText('Meets agreed deadlines.')).toBeTruthy()
    expect(screen.getByText('Delivered on time.')).toBeTruthy()
    expect(screen.getByText('Good delivery.')).toBeTruthy()
    expect(screen.getByText('Consistently strong.')).toBeTruthy()
    expect(screen.getByText('60%')).toBeTruthy()
    expect(screen.getAllByText('Employee self-rating (reference only)')).toHaveLength(2)
    expect(screen.getAllByText('Manager assessment (read-only)')).toHaveLength(2)
    const rating = screen.getByLabelText('HR rating for Delivery Quality')
    expect(within(rating).getAllByRole('option').map((option) => option.value))
      .toEqual(['', '1', '2', '3', '4', '5'])
    expect(screen.getByLabelText('HR comment for Delivery Quality')).toBeTruthy()
    expect(screen.queryByLabelText('HR rating for Quality')).toBeNull()
    expect(within(screen.getByRole('region', { name: 'Manager assessment for Quality' })).getByText('4'))
      .toBeTruthy()
  })

  it('saves incomplete drafts and preserves ratings/comments', async () => {
    const user = userEvent.setup()
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await user.selectOptions(screen.getByLabelText('HR rating for Delivery Quality'), '2')
    await user.type(screen.getByLabelText('HR comment for Delivery Quality'), 'Needs follow-up')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('HR draft saved.')).toBeTruthy()
    expect(hrAssessmentApi.saveHrAssessmentDraft).toHaveBeenCalledWith(31, [
      { employeeKpiId: 100, rating: 2, comment: 'Needs follow-up' },
    ])
    expect(screen.getByLabelText('HR rating for Delivery Quality').value).toBe('2')
    expect(screen.getByLabelText('HR comment for Delivery Quality').value).toBe('Needs follow-up')
  })

  it('prevents finalization when any KPI rating is missing', async () => {
    const user = userEvent.setup()
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await user.click(screen.getByRole('button', { name: 'Finalize assessment' }))

    expect((await screen.findByRole('alert')).textContent).toContain('for every HR-assessed KPI')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(hrAssessmentApi.finalizeHrAssessment).not.toHaveBeenCalled()
  })

  it('requires confirmation and does not finalize when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await setAllRatings(user)
    await user.click(screen.getByRole('button', { name: 'Finalize assessment' }))

    expect(dialogText()).toContain('Eve Employee')
    expect(dialogText()).toContain('completes the assessment')
    await cancelDialog(user)
    expect(hrAssessmentApi.finalizeHrAssessment).not.toHaveBeenCalled()
  })

  it('saves unsaved values, finalizes separately, and makes the form read-only', async () => {
    const user = userEvent.setup()
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await setAllRatings(user)
    await user.type(screen.getByLabelText('HR comment for Delivery Quality'), 'Reviewed')
    await user.click(screen.getByRole('button', { name: 'Finalize assessment' }))
    await confirmDialog(user, 'Finalize assessment')

    expect(await screen.findByText('HR assessment finalized. The assignment is complete.')).toBeTruthy()
    expect(hrAssessmentApi.saveHrAssessmentDraft).toHaveBeenCalledWith(31, [
      { employeeKpiId: 100, rating: 3, comment: 'Reviewed' },
    ])
    expect(hrAssessmentApi.finalizeHrAssessment).toHaveBeenCalledWith(31)
    expect(hrAssessmentApi.getHrAssessmentStatus).toHaveBeenCalledWith(31)
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Finalize assessment' })).toBeNull()
    expect(screen.getByLabelText('HR rating for Delivery Quality').closest('fieldset').disabled).toBe(true)
    expect(screen.queryByText(/Final score/i)).toBeNull()
    await user.click(screen.getByRole('link', { name: 'Back to HR review queue' }))
    expect(await screen.findByText('Eve Employee')).toBeTruthy()
    expect(hrAssessmentApi.getHrAssessmentQueue).toHaveBeenCalledOnce()
  })

  it('prevents duplicate finalization while the request is pending', async () => {
    const user = userEvent.setup()
    hrAssessmentApi.getHrAssessment.mockResolvedValue({
      ...assessment,
      kpis: assessment.kpis.map((kpi) => ({ ...kpi, hrRating: kpi.kpiType === 'HR' ? 3 : null })),
    })
    let resolveFinalize
    hrAssessmentApi.finalizeHrAssessment.mockReturnValueOnce(
      new Promise((resolve) => { resolveFinalize = resolve }),
    )
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    const button = screen.getByRole('button', { name: 'Finalize assessment' })
    await user.click(button)
    const dialog = await screen.findByRole('dialog')
    const confirmButton = within(dialog).getByRole('button', { name: 'Finalize assessment' })
    await user.click(confirmButton)
    await user.click(confirmButton)
    expect(hrAssessmentApi.finalizeHrAssessment).toHaveBeenCalledOnce()
    resolveFinalize({
      assignmentId: 31,
      assignmentStatus: 'COMPLETED',
      submitted: true,
      totalKpis: 1,
      ratedKpis: 1,
    })
    expect(await screen.findByText('HR assessment finalized. The assignment is complete.')).toBeTruthy()
  })

  it('preserves form values and reports draft errors', async () => {
    const user = userEvent.setup()
    hrAssessmentApi.saveHrAssessmentDraft.mockRejectedValueOnce({
      response: { status: 400, data: { message: 'HR assessment rating must be a whole number between 1 and 5.' } },
    })
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await user.selectOptions(screen.getByLabelText('HR rating for Delivery Quality'), '4')
    await user.type(screen.getByLabelText('HR comment for Delivery Quality'), 'Keep my note')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect((await screen.findByRole('alert')).textContent).toContain('whole number')
    expect(screen.getByLabelText('HR rating for Delivery Quality').value).toBe('4')
    expect(screen.getByLabelText('HR comment for Delivery Quality').value).toBe('Keep my note')
  })

  it('preserves form values when finalization fails', async () => {
    const user = userEvent.setup()
    hrAssessmentApi.finalizeHrAssessment.mockRejectedValueOnce({
      response: { status: 500 },
    })
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await setAllRatings(user)
    await user.type(screen.getByLabelText('HR comment for Delivery Quality'), 'Retain this note')
    await user.click(screen.getByRole('button', { name: 'Finalize assessment' }))
    await confirmDialog(user, 'Finalize assessment')

    expect((await screen.findByRole('alert')).textContent).toContain('could not complete your request')
    expect(screen.getByLabelText('HR rating for Delivery Quality').value).toBe('3')
    expect(screen.getByLabelText('HR comment for Delivery Quality').value).toBe('Retain this note')
    expect(hrAssessmentApi.finalizeHrAssessment).toHaveBeenCalledOnce()
  })

  it('renders an assessment read-only when the backend marks it finalized', async () => {
    hrAssessmentApi.getHrAssessment.mockResolvedValueOnce({
      ...assessment,
      assignmentStatus: 'COMPLETED',
      editable: false,
      hrFinalizedAt: '2026-11-02T11:00:00Z',
      kpis: assessment.kpis.map((kpi) => ({ ...kpi, hrRating: kpi.kpiType === 'HR' ? 3 : null, hrComment: 'Reviewed' })),
    })
    renderApp('/hr/assessments/31')

    expect(await screen.findByText('HR assessment finalized. This assignment is now read-only.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Finalize assessment' })).toBeNull()
    expect(screen.getByLabelText('HR rating for Delivery Quality').closest('fieldset').disabled).toBe(true)
  })

  it('shows a single not-found state with retry for inaccessible assignments', async () => {
    hrAssessmentApi.getHrAssessment.mockRejectedValueOnce({ response: { status: 404 } })
    renderApp('/hr/assessments/999')

    expect(await screen.findByText(/not found or is not available for HR review/i)).toBeTruthy()
    expect(screen.getAllByText(/not found or is not available for HR review/i)).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it('refreshes status after a finalization conflict and keeps the response read-only', async () => {
    const user = userEvent.setup()
    hrAssessmentApi.getHrAssessment.mockResolvedValue({
      ...assessment,
      kpis: assessment.kpis.map((kpi) => ({ ...kpi, hrRating: kpi.kpiType === 'HR' ? 3 : null })),
    })
    hrAssessmentApi.finalizeHrAssessment.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'The HR assessment has already been submitted.' } },
    })
    renderApp('/hr/assessments/31')
    await screen.findByText('Eve Employee')
    await user.click(screen.getByRole('button', { name: 'Finalize assessment' }))
    await confirmDialog(user, 'Finalize assessment')

    expect(await screen.findByText(/already been submitted/i)).toBeTruthy()
    expect(hrAssessmentApi.getHrAssessmentStatus).toHaveBeenCalledWith(31)
  })
})
