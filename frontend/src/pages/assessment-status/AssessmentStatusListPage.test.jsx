import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as statusApi from '../../api/assessmentStatusApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(), getCurrentUser: vi.fn(), logout: vi.fn(), changePassword: vi.fn(),
}))
vi.mock('../../api/assessmentStatusApi.js', () => ({
  getHrAssessmentStatus: vi.fn(),
  getManagerAssessmentStatusList: vi.fn(),
  getManagerDirectReports: vi.fn(),
}))
vi.mock('../../api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(), getManagerDashboard: vi.fn(), getEmployeeDashboard: vi.fn(),
}))

function renderApp(role, path) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'status.jwt')
  authApi.getCurrentUser.mockResolvedValue({ id: 2, email: 'reviewer@example.test', role })
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>)
}

describe('dashboard assessment status destinations', () => {
  beforeEach(() => { localStorage.clear(); vi.resetAllMocks() })

  it('renders the corresponding HR manager-review records with manager and cycle data', async () => {
    statusApi.getHrAssessmentStatus.mockResolvedValue([{
      assignmentId: 10, employeeFullName: 'Ada Employee', employeeCode: 'E-10',
      department: 'Engineering', managerName: 'Mira Manager', cycleName: 'FY 2026',
      cycleStatus: 'PUBLISHED', assignmentStatus: 'MANAGER_REVIEW_PENDING',
    }])
    renderApp('HR', '/hr/assessment-status/manager-review-pending')

    expect(await screen.findByRole('heading', { name: 'Awaiting manager review' })).toBeTruthy()
    expect(await screen.findByText('Ada Employee')).toBeTruthy()
    expect(screen.getByText('Mira Manager')).toBeTruthy()
    expect(screen.getByText('FY 2026')).toBeTruthy()
    expect(statusApi.getHrAssessmentStatus).toHaveBeenCalledWith('MANAGER_REVIEW_PENDING')
  })

  it('renders a manager-only completed list', async () => {
    statusApi.getManagerAssessmentStatusList.mockResolvedValue([{
      assignmentId: 11, employeeFullName: 'Bea Report', employeeCode: 'E-11',
      managerName: 'Mira Manager', cycleName: 'FY 2025', cycleStatus: 'COMPLETED',
      assignmentStatus: 'COMPLETED',
    }])
    renderApp('MANAGER', '/manager/assessment-status/completed')

    expect(await screen.findByRole('heading', { name: 'Completed assessments' })).toBeTruthy()
    expect(await screen.findByText('Bea Report')).toBeTruthy()
    expect(screen.getByText('E-11')).toBeTruthy()
    expect(statusApi.getManagerAssessmentStatusList).toHaveBeenCalledWith('COMPLETED')
  })

  it('renders active direct reports with employee identifiers and team fields', async () => {
    statusApi.getManagerDirectReports.mockResolvedValue([{
      employeeId: 21, employeeCode: 'E-21', fullName: 'Chen Report',
      department: 'Product', designation: 'Analyst', status: 'ACTIVE',
    }])
    renderApp('MANAGER', '/manager/direct-reports')

    expect(await screen.findByRole('heading', { name: 'Active direct reports' })).toBeTruthy()
    expect(await screen.findByText('Chen Report')).toBeTruthy()
    expect(screen.getByText('E-21')).toBeTruthy()
    expect(screen.getByText('Product')).toBeTruthy()
  })
})
