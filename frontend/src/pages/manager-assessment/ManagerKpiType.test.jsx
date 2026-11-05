import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as managerAssessmentApi from '../../api/managerAssessmentApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'
import { confirmDialog } from '../../test/dialogHelpers.js'

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

const assessment = {
  assignmentId: 31,
  assignmentStatus: 'MANAGER_REVIEW_PENDING',
  editable: true,
  cycleName: 'Annual 2026',
  managerSubmittedAt: null,
  employee: {
    employeeId: 21,
    employeeCode: 'EMP-021',
    fullName: 'Eve Employee',
    department: 'Engineering',
    designation: 'Engineer',
  },
  kpis: [
    {
      employeeKpiId: 100,
      name: 'Delivery Quality',
      description: 'Meets agreed deadlines.',
      category: 'Delivery',
      weightage: 60,
      kpiType: 'MANAGER',
      managerRating: null,
      managerComment: null,
      employeeSelfRating: 4,
      employeeComment: 'Delivered on time.',
    },
    {
      employeeKpiId: 102,
      name: 'Policy compliance',
      description: 'Follows company policy.',
      category: 'Compliance',
      weightage: 40,
      kpiType: 'HR',
      managerRating: null,
      managerComment: null,
      employeeSelfRating: 3,
      employeeComment: 'Compliant.',
    },
  ],
}

function renderApp() {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'manager.jwt')
  authApi.getCurrentUser.mockResolvedValue({ id: 2, email: 'manager@example.test', role: 'MANAGER' })
  return render(
    <MemoryRouter initialEntries={['/manager/assessments/31']}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('manager assessment with HR-assessed KPIs', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    managerAssessmentApi.getManagerAssessment.mockResolvedValue(assessment)
    managerAssessmentApi.getManagerAssessmentStatus.mockResolvedValue({
      assignmentId: 31,
      assignmentStatus: 'MANAGER_REVIEW_PENDING',
      submitted: false,
      ratedKpis: 0,
      totalKpis: 1,
    })
    managerAssessmentApi.saveManagerAssessmentDraft.mockResolvedValue(assessment)
    managerAssessmentApi.submitManagerAssessment.mockResolvedValue({
      assignmentId: 31,
      assignmentStatus: 'HR_REVIEW_PENDING',
      submitted: true,
      ratedKpis: 1,
      totalKpis: 1,
    })
    managerAssessmentApi.getManagerAssessmentQueue.mockResolvedValue([])
  })

  it('lets the manager rate every KPI type and includes HR KPIs in the payload', async () => {
    const user = userEvent.setup()
    renderApp()

    expect(await screen.findByText('Policy compliance')).toBeTruthy()
    expect(screen.getByLabelText('Manager rating for Policy compliance')).toBeTruthy()
    expect(screen.getByText('Manager ratings completed: 0 of 2')).toBeTruthy()

    await user.selectOptions(screen.getByLabelText('Manager rating for Delivery Quality'), '4')
    await user.selectOptions(screen.getByLabelText('Manager rating for Policy compliance'), '3')
    await user.click(screen.getByRole('button', { name: 'Submit assessment to HR' }))
    await confirmDialog(user, 'Submit to HR')

    await waitFor(() => expect(
      managerAssessmentApi.submitManagerAssessment,
    ).toHaveBeenCalledWith(31))
    expect(managerAssessmentApi.saveManagerAssessmentDraft).toHaveBeenCalledWith(31, [
      { employeeKpiId: 100, rating: 4, comment: '' },
      { employeeKpiId: 102, rating: 3, comment: '' },
    ])
  })
})
