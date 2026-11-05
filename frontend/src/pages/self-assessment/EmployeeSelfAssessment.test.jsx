import { render, screen, waitFor, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as selfAssessmentApi from '../../api/selfAssessmentApi.js'
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

vi.mock('../../api/selfAssessmentApi.js', () => ({
  getSelfAssessmentCycles: vi.fn(),
  getSelfAssessment: vi.fn(),
  getSelfAssessmentStatus: vi.fn(),
  saveSelfAssessmentDraft: vi.fn(),
  submitSelfAssessment: vi.fn(),
}))

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE' },
}

const cycles = [{
  assignmentId: 21,
  cycleId: 8,
  cycleName: 'Annual 2026',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  selfAssessmentDeadline: '2026-11-30',
  cycleStatus: 'PUBLISHED',
  assignmentStatus: 'SELF_ASSESSMENT_PENDING',
  selfSubmittedAt: null,
}]

const assessment = {
  assignmentId: 21,
  cycleId: 8,
  cycleName: 'Annual 2026',
  cycleStatus: 'PUBLISHED',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  selfAssessmentDeadline: '2026-11-30',
  assignmentStatus: 'SELF_ASSESSMENT_PENDING',
  selfSubmittedAt: null,
  editable: true,
  kpis: [
    {
      employeeKpiId: 91,
      kpiId: 7,
      name: 'Delivery Quality',
      description: 'Meets agreed deadlines.',
      category: 'Delivery',
      weightage: 60,
      rating: null,
      comment: null,
    },
    {
      employeeKpiId: 92,
      kpiId: 8,
      name: 'Quality',
      description: 'Work meets the quality bar.',
      category: 'Quality',
      weightage: 40,
      rating: null,
      comment: null,
    },
  ],
}

function renderApp(path, role = 'EMPLOYEE', authenticated = true) {
  if (authenticated) {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'employee-test.jwt')
    authApi.getCurrentUser.mockResolvedValue(users[role])
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

async function openAssessment(user) {
  await user.selectOptions(screen.getByLabelText('Rating for Delivery Quality'), '4')
  await user.selectOptions(screen.getByLabelText('Rating for Quality'), '5')
}

describe('employee self-assessment workflow', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getEmployeeDashboard.mockResolvedValue({})
    selfAssessmentApi.getSelfAssessmentCycles.mockResolvedValue(cycles)
    selfAssessmentApi.getSelfAssessment.mockResolvedValue(assessment)
    selfAssessmentApi.getSelfAssessmentStatus.mockResolvedValue({
      assignmentId: 21,
      cycleId: 8,
      cycleStatus: 'PUBLISHED',
      assignmentStatus: 'MANAGER_REVIEW_PENDING',
      submitted: true,
      selfSubmittedAt: '2026-11-02T10:00:00Z',
      totalKpis: 2,
      ratedKpis: 2,
    })
    selfAssessmentApi.saveSelfAssessmentDraft.mockImplementation(async (_id, kpis) => ({
      ...assessment,
      kpis: assessment.kpis.map((kpi) => ({
        ...kpi,
        ...kpis.find((entry) => entry.employeeKpiId === kpi.employeeKpiId),
      })),
    }))
    selfAssessmentApi.submitSelfAssessment.mockResolvedValue({
      assignmentId: 21,
      cycleId: 8,
      cycleStatus: 'PUBLISHED',
      assignmentStatus: 'MANAGER_REVIEW_PENDING',
      submitted: true,
      selfSubmittedAt: '2026-11-02T10:00:00Z',
      totalKpis: 2,
      ratedKpis: 2,
    })
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads employee assignments and opens the matching assessment', async () => {
    const user = userEvent.setup()
    renderApp('/employee/self-assessments')

    expect(await screen.findByRole('heading', { name: 'Your self-assessments' })).toBeTruthy()
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(screen.getByText('2026-01-01 – 2026-12-31')).toBeTruthy()
    expect(screen.getByText('SELF ASSESSMENT PENDING')).toBeTruthy()
    expect(selfAssessmentApi.getSelfAssessmentCycles).toHaveBeenCalledOnce()
    expect(selfAssessmentApi.getSelfAssessmentCycles).toHaveBeenCalledWith()

    await user.click(screen.getByRole('link', { name: 'Open self-assessment' }))
    expect(await screen.findByRole('heading', { name: 'Self-assessment' })).toBeTruthy()
    expect(selfAssessmentApi.getSelfAssessment).toHaveBeenCalledWith('21')
  })

  it('shows an explicit empty assignment state', async () => {
    selfAssessmentApi.getSelfAssessmentCycles.mockResolvedValueOnce([])
    renderApp('/employee/self-assessments')

    expect(await screen.findByText('You do not have any published performance cycle assignments.'))
      .toBeTruthy()
  })

  it('shows loading while the assignment list request is pending', async () => {
    let resolveList
    selfAssessmentApi.getSelfAssessmentCycles.mockReturnValueOnce(
      new Promise((resolve) => { resolveList = resolve }),
    )
    renderApp('/employee/self-assessments')

    expect(await screen.findByText('Loading…')).toBeTruthy()
    resolveList(cycles)
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
  })

  it('shows loading and retries an assignment-list error', async () => {
    const user = userEvent.setup()
    selfAssessmentApi.getSelfAssessmentCycles
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce(cycles)
    renderApp('/employee/self-assessments')

    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(selfAssessmentApi.getSelfAssessmentCycles).toHaveBeenCalledTimes(2)
  })

  it('shows frozen KPI details, an optional comment and only ratings one through five', async () => {
    renderApp('/employee/self-assessments/21')

    expect(await screen.findByText('Meets agreed deadlines.')).toBeTruthy()
    expect(screen.getByText('Delivery')).toBeTruthy()
    expect(screen.getByText('60%')).toBeTruthy()
    const rating = screen.getByLabelText('Rating for Delivery Quality')
    expect(within(rating).getAllByRole('option').map((option) => option.value))
      .toEqual(['', '1', '2', '3', '4', '5'])
    expect(screen.getByLabelText('Comment for Delivery Quality')).toBeTruthy()
    expect(screen.getByText(/do not contribute to your final performance score/)).toBeTruthy()
  })

  it('prevents incomplete submission and explains the required ratings', async () => {
    const user = userEvent.setup()
    renderApp('/employee/self-assessments/21')
    await user.click(await screen.findByRole('button', { name: 'Submit self-assessment' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Choose a rating from 1 to 5 for every KPI')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(selfAssessmentApi.saveSelfAssessmentDraft).not.toHaveBeenCalled()
    expect(selfAssessmentApi.submitSelfAssessment).not.toHaveBeenCalled()
  })

  it('saves a partial draft and preserves rating and comment values', async () => {
    const user = userEvent.setup()
    renderApp('/employee/self-assessments/21')
    await screen.findByRole('heading', { name: 'Annual 2026' })
    await user.selectOptions(screen.getByLabelText('Rating for Delivery Quality'), '3')
    await user.type(screen.getByLabelText('Comment for Delivery Quality'), 'On schedule')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Draft saved. You can return and continue before submitting.'))
      .toBeTruthy()
    expect(selfAssessmentApi.saveSelfAssessmentDraft).toHaveBeenCalledWith(21, [
      { employeeKpiId: 91, rating: 3, comment: 'On schedule' },
      { employeeKpiId: 92, rating: null, comment: '' },
    ])
    expect(screen.getByLabelText('Rating for Delivery Quality').value).toBe('3')
    expect(screen.getByLabelText('Comment for Delivery Quality').value).toBe('On schedule')
  })

  it('requires explicit confirmation and does not call APIs when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/employee/self-assessments/21')
    await screen.findByRole('heading', { name: 'Annual 2026' })
    await openAssessment(user)
    await user.click(screen.getByRole('button', { name: 'Submit self-assessment' }))

    expect(dialogText()).toContain('sent to your assigned manager')
    await cancelDialog(user)
    expect(selfAssessmentApi.saveSelfAssessmentDraft).not.toHaveBeenCalled()
    expect(selfAssessmentApi.submitSelfAssessment).not.toHaveBeenCalled()
  })

  it('saves current values, submits separately, refreshes status and becomes read-only', async () => {
    const user = userEvent.setup()
    renderApp('/employee/self-assessments/21')
    await screen.findByRole('heading', { name: 'Annual 2026' })
    await openAssessment(user)
    await user.type(screen.getByLabelText('Comment for Delivery Quality'), 'Delivered')
    await user.click(screen.getByRole('button', { name: 'Submit self-assessment' }))
    await confirmDialog(user, 'Submit self-assessment')

    expect(await screen.findByText('Self-assessment submitted to your assigned manager.')).toBeTruthy()
    expect(selfAssessmentApi.saveSelfAssessmentDraft).toHaveBeenCalledWith(21, [
      { employeeKpiId: 91, rating: 4, comment: 'Delivered' },
      { employeeKpiId: 92, rating: 5, comment: '' },
    ])
    expect(selfAssessmentApi.submitSelfAssessment).toHaveBeenCalledOnce()
    expect(selfAssessmentApi.submitSelfAssessment).toHaveBeenCalledWith(21)
    expect(selfAssessmentApi.getSelfAssessmentStatus).toHaveBeenCalledWith(21)
    expect(screen.getByText('MANAGER REVIEW PENDING')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Submit self-assessment' })).toBeNull()
    expect(screen.getByLabelText('Rating for Delivery Quality').closest('fieldset').disabled).toBe(true)
  })

  it('blocks duplicate submit while the draft-saving request is pending', async () => {
    const user = userEvent.setup()
    let resolveSave
    selfAssessmentApi.saveSelfAssessmentDraft.mockReturnValueOnce(
      new Promise((resolve) => { resolveSave = resolve }),
    )
    renderApp('/employee/self-assessments/21')
    await screen.findByRole('heading', { name: 'Annual 2026' })
    await openAssessment(user)
    const submitButton = screen.getByRole('button', { name: 'Submit self-assessment' })
    await user.click(submitButton)
    await user.click(submitButton)
    await confirmDialog(user, 'Submit self-assessment')

    expect(selfAssessmentApi.saveSelfAssessmentDraft).toHaveBeenCalledOnce()
    expect(selfAssessmentApi.submitSelfAssessment).not.toHaveBeenCalled()
    resolveSave(assessment)
    await waitFor(() => expect(selfAssessmentApi.submitSelfAssessment).toHaveBeenCalledOnce())
  })

  it('preserves form values and reports a failed save', async () => {
    const user = userEvent.setup()
    selfAssessmentApi.saveSelfAssessmentDraft.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'The self-assessment has already been submitted.' } },
    })
    renderApp('/employee/self-assessments/21')
    await screen.findByRole('heading', { name: 'Annual 2026' })
    await user.selectOptions(screen.getByLabelText('Rating for Delivery Quality'), '2')
    await user.type(screen.getByLabelText('Comment for Delivery Quality'), 'Keep my entry')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The self-assessment has already been submitted.')
    expect(screen.getByLabelText('Rating for Delivery Quality').value).toBe('2')
    expect(screen.getByLabelText('Comment for Delivery Quality').value).toBe('Keep my entry')
  })

  it('handles inaccessible assignment details and protects routes from non-employees', async () => {
    selfAssessmentApi.getSelfAssessment.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Self-assessment not found.' } },
    })
    renderApp('/employee/self-assessments/999')
    expect((await screen.findByRole('alert')).textContent).toContain('Self-assessment not found.')
    expect(selfAssessmentApi.getSelfAssessment).toHaveBeenCalledWith('999')

    for (const role of ['HR', 'MANAGER']) {
      const { unmount } = renderApp('/employee/self-assessments', role)
      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      unmount()
    }
    expect(selfAssessmentApi.getSelfAssessmentCycles).not.toHaveBeenCalled()
  })

  it('redirects unauthenticated visits to the login page', async () => {
    renderApp('/employee/self-assessments', 'EMPLOYEE', false)
    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(selfAssessmentApi.getSelfAssessmentCycles).not.toHaveBeenCalled()
  })
})
