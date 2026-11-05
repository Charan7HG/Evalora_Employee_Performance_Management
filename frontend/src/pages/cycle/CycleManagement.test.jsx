import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as cycleApi from '../../api/cycleApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as employeeApi from '../../api/employeeApi.js'
import * as kpiApi from '../../api/kpiApi.js'
import * as managerApi from '../../api/managerApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('../../api/cycleApi.js', () => ({
  getCycles: vi.fn(),
  getCycleById: vi.fn(),
  getCycleKpis: vi.fn(),
  configureCycleKpis: vi.fn(),
  getCycleEmployees: vi.fn(),
  configureCycleEmployees: vi.fn(),
  publishCycle: vi.fn(),
  createCycle: vi.fn(),
  updateCycle: vi.fn(),
}))

vi.mock('../../api/employeeApi.js', () => ({
  createEmployee: vi.fn(),
  listEmployees: vi.fn(),
  getEmployee: vi.fn(),
  updateEmployee: vi.fn(),
  assignEmployeeManager: vi.fn(),
  deactivateEmployee: vi.fn(),
  reactivateEmployee: vi.fn(),
}))

vi.mock('../../api/managerApi.js', () => ({
  createManager: vi.fn(),
  listManagers: vi.fn(),
  getManager: vi.fn(),
  updateManager: vi.fn(),
  deactivateManager: vi.fn(),
  reactivateManager: vi.fn(),
}))

vi.mock('../../api/kpiApi.js', () => ({
  getKpis: vi.fn(),
  getKpiById: vi.fn(),
  createKpi: vi.fn(),
  updateKpi: vi.fn(),
  deactivateKpi: vi.fn(),
  reactivateKpi: vi.fn(),
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

const cycle = {
  id: 8,
  name: 'Annual 2026',
  description: 'Annual performance review.',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  selfAssessmentDeadline: '2026-11-30',
  managerReviewDeadline: '2026-12-15',
  hrReviewDeadline: '2026-12-31',
  status: 'DRAFT',
  createdById: 1,
  publishedAt: null,
  completedAt: null,
  createdAt: '2026-01-02T10:00:00Z',
  updatedAt: '2026-01-03T10:00:00Z',
}

const assignedKpis = [{
  id: 2,
  kpiId: 8,
  name: 'Communication',
  description: 'Clear communication.',
  category: 'Collaboration',
  weightage: 100,
}]

const activeKpis = [
  { id: 8, name: 'Communication', category: 'Collaboration', description: 'Clear communication.', active: true },
  { id: 9, name: 'Technical skills', category: 'Delivery', description: 'Technical expertise.', active: true },
  { id: 10, name: 'Teamwork', category: 'Collaboration', description: 'Works across teams.', active: true },
]

const assignedEmployees = [{
  id: 15,
  employeeId: 31,
  employeeCode: 'EMP-031',
  employeeName: 'Erin Employee',
  managerId: 7,
  managerName: 'Mona Manager',
  status: 'SELF_ASSESSMENT_PENDING',
}]

const activeEmployees = [
  {
    id: 31,
    role: 'EMPLOYEE',
    employeeCode: 'EMP-031',
    fullName: 'Erin Employee',
    department: 'Engineering',
    status: 'ACTIVE',
    accountEnabled: true,
    managerId: 8,
    managerName: 'Sam Second',
  },
  {
    id: 32,
    role: 'EMPLOYEE',
    employeeCode: 'EMP-032',
    fullName: 'Ethan Employee',
    department: 'Product',
    status: 'ACTIVE',
    accountEnabled: true,
    managerId: 7,
    managerName: 'Mona Manager',
  },
  {
    id: 33,
    role: 'EMPLOYEE',
    employeeCode: 'EMP-033',
    fullName: 'Disabled Employee',
    status: 'ACTIVE',
    accountEnabled: false,
    managerId: 7,
  },
  {
    id: 34,
    role: 'EMPLOYEE',
    employeeCode: 'EMP-034',
    fullName: 'Unassigned Employee',
    status: 'ACTIVE',
    accountEnabled: true,
    managerId: null,
  },
  {
    id: 35,
    role: 'EMPLOYEE',
    employeeCode: 'EMP-035',
    fullName: 'Inactive Manager Employee',
    status: 'ACTIVE',
    accountEnabled: true,
    managerId: 9,
  },
]

const activeManagers = [{
  id: 7,
  fullName: 'Mona Manager',
  employeeCode: 'MGR-007',
  status: 'ACTIVE',
  accountEnabled: true,
}, {
  id: 8,
  fullName: 'Sam Second',
  employeeCode: 'MGR-008',
  status: 'ACTIVE',
  accountEnabled: true,
}, {
  id: 9,
  fullName: 'Inactive Manager',
  employeeCode: 'MGR-009',
  status: 'INACTIVE',
  accountEnabled: true,
}, {
  id: 10,
  fullName: 'Disabled Manager',
  employeeCode: 'MGR-010',
  status: 'ACTIVE',
  accountEnabled: false,
}]

function renderApp(path, role = 'HR') {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'cycle-test.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

function setDate(label, value) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

async function fillValidCycle(user) {
  await user.type(await screen.findByLabelText('Cycle name'), '  Annual 2026  ')
  await user.type(screen.getByLabelText('Description'), '  Annual performance review.  ')
  setDate('Start date', '2026-01-01')
  setDate('End date', '2026-12-31')
  setDate('Self-assessment deadline', '2026-11-30')
  setDate('Manager review deadline', '2026-12-15')
  setDate('HR review deadline', '2026-12-31')
}

async function openKpiManager(user) {
  await user.click(await screen.findByRole('button', { name: 'Manage KPIs' }))
  expect(await screen.findByRole('heading', { name: 'Manage cycle KPIs' })).toBeTruthy()
  await waitFor(() => expect(kpiApi.getKpis).toHaveBeenCalledWith(true))
}

async function openEmployeeManager(user) {
  await user.click(await screen.findByRole('button', { name: 'Manage Employees' }))
  expect(await screen.findByRole('heading', { name: 'Manage cycle employees' })).toBeTruthy()
  await waitFor(() => {
    expect(employeeApi.listEmployees).toHaveBeenCalledWith('ACTIVE')
    expect(managerApi.listManagers).toHaveBeenCalledWith('ACTIVE')
  })
}

describe('HR performance cycle management', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getManagerDashboard.mockResolvedValue({})
    cycleApi.getCycles.mockResolvedValue([cycle])
    cycleApi.getCycleById.mockResolvedValue(cycle)
    cycleApi.getCycleKpis.mockResolvedValue(assignedKpis)
    cycleApi.configureCycleKpis.mockResolvedValue(assignedKpis)
    cycleApi.getCycleEmployees.mockResolvedValue(assignedEmployees)
    cycleApi.configureCycleEmployees.mockResolvedValue(assignedEmployees)
    cycleApi.publishCycle.mockResolvedValue(cycle)
    cycleApi.createCycle.mockResolvedValue(cycle)
    cycleApi.updateCycle.mockResolvedValue(cycle)
    kpiApi.getKpis.mockResolvedValue(activeKpis)
    employeeApi.listEmployees.mockResolvedValue(activeEmployees)
    managerApi.listManagers.mockResolvedValue(activeManagers)
  })

  afterEach(() => vi.restoreAllMocks())

  it('adds Performance Cycles navigation only for HR', async () => {
    const hr = renderApp('/hr/cycles')
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Performance Cycles' }).getAttribute('href'))
      .toBe('/hr/cycles')
    hr.unmount()

    renderApp('/manager/dashboard', 'MANAGER')
    expect(await screen.findByText('Your current team and assessment workload.')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Performance Cycles' })).toBeNull()
  })

  it.each(['MANAGER', 'EMPLOYEE'])('%s cannot access cycle management', async (role) => {
    renderApp('/hr/cycles', role)

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(cycleApi.getCycles).not.toHaveBeenCalled()
  })

  it('redirects an unauthenticated cycle visit to login', async () => {
    render(
      <MemoryRouter initialEntries={['/hr/cycles']}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(cycleApi.getCycles).not.toHaveBeenCalled()
  })

  it('shows loading and then the available cycle fields', async () => {
    let resolveCycles
    cycleApi.getCycles.mockReturnValueOnce(new Promise((resolve) => { resolveCycles = resolve }))
    renderApp('/hr/cycles')

    await waitFor(() => expect(cycleApi.getCycles).toHaveBeenCalledOnce())
    expect(screen.getByRole('status').textContent).toContain('Loading')
    resolveCycles([cycle])

    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(screen.getByText('2026-11-30')).toBeTruthy()
    expect(screen.getByText('DRAFT')).toBeTruthy()
    expect(screen.queryByText('createdById')).toBeNull()
  })

  it('shows an empty state when no cycles exist', async () => {
    cycleApi.getCycles.mockResolvedValueOnce([])
    renderApp('/hr/cycles')

    expect(await screen.findByText('No performance cycles have been created.')).toBeTruthy()
  })

  it('shows list errors and retries the request', async () => {
    const user = userEvent.setup()
    cycleApi.getCycles
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce([cycle])
    renderApp('/hr/cycles')

    expect(await screen.findByText('Evalora could not complete your request. Please try again later.'))
      .toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(cycleApi.getCycles).toHaveBeenCalledTimes(2)
  })

  it('searches cycle names and hides non-matching cycles', async () => {
    const user = userEvent.setup()
    cycleApi.getCycles.mockResolvedValueOnce([
      cycle,
      { ...cycle, id: 9, name: 'Quarterly 2026' },
    ])
    renderApp('/hr/cycles')

    expect(await screen.findByText('Annual 2026')).toBeTruthy()
    expect(screen.getByText('Quarterly 2026')).toBeTruthy()
    await user.type(screen.getByRole('searchbox', { name: 'Search cycles' }), 'quarter')
    expect(screen.getByText('Quarterly 2026')).toBeTruthy()
    expect(screen.queryByText('Annual 2026')).toBeNull()
  })

  it('validates required dates and date ordering before creating a cycle', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/new')

    await user.click(await screen.findByRole('button', { name: 'Create cycle' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Cycle name is required')
    expect(cycleApi.createCycle).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Cycle name'), 'Annual 2026')
    setDate('Start date', '2026-12-31')
    setDate('End date', '2026-01-01')
    setDate('Self-assessment deadline', '2026-06-30')
    setDate('Manager review deadline', '2026-07-15')
    setDate('HR review deadline', '2026-07-31')
    await user.click(screen.getByRole('button', { name: 'Create cycle' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Start date must be before the end date')
    expect(cycleApi.createCycle).not.toHaveBeenCalled()
  })

  it.each([
    ['Self-assessment deadline', '2027-01-01', 'Self-assessment deadline must be within the cycle dates.'],
    [
      'Manager review deadline',
      '2026-11-29',
      'Manager review deadline must be on or after the self-assessment deadline.',
    ],
    [
      'HR review deadline',
      '2026-12-14',
      'HR review deadline must be on or after the manager review deadline.',
    ],
  ])('validates the %s against the backend date rules', async (field, invalidDate, message) => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/new')
    await fillValidCycle(user)
    setDate(field, invalidDate)
    await user.click(screen.getByRole('button', { name: 'Create cycle' }))

    expect((await screen.findByRole('alert')).textContent).toContain(message)
    expect(cycleApi.createCycle).not.toHaveBeenCalled()
  })

  it('creates a cycle with trimmed text and the exact request fields', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/new')

    await fillValidCycle(user)
    await user.click(await screen.findByRole('button', { name: 'Create cycle' }))

    await waitFor(() => expect(cycleApi.createCycle).toHaveBeenCalledOnce())
    expect(cycleApi.createCycle).toHaveBeenCalledWith({
      name: 'Annual 2026',
      description: 'Annual performance review.',
      startDate: '2026-01-01',
      endDate: '2026-12-31',
      selfAssessmentDeadline: '2026-11-30',
      managerReviewDeadline: '2026-12-15',
      hrReviewDeadline: '2026-12-31',
    })
    expect(await screen.findByRole('heading', { name: 'Performance cycle details' })).toBeTruthy()
  })

  it('renders cycle details and provides edit only for a draft cycle', async () => {
    renderApp('/hr/cycles/8')

    expect(await screen.findByText('Annual performance review.')).toBeTruthy()
    expect(within(screen.getByText('Start date').parentElement).getByText('2026-01-01')).toBeTruthy()
    expect(within(screen.getByText('End date').parentElement).getByText('2026-12-31')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Edit cycle' }).getAttribute('href'))
      .toBe('/hr/cycles/8/edit')
    expect(await screen.findByRole('heading', { name: 'Assigned KPIs' })).toBeTruthy()
    expect(await screen.findByText('Communication')).toBeTruthy()
    expect(screen.getByText('Collaboration')).toBeTruthy()
    expect(screen.getByText('100.00%')).toBeTruthy()
    expect(await screen.findByRole('heading', { name: 'Assigned Employees' })).toBeTruthy()
    expect(screen.getByText('Erin Employee')).toBeTruthy()
    expect(screen.getByText('EMP-031')).toBeTruthy()
    expect(screen.getByText('Mona Manager')).toBeTruthy()
    expect(screen.getByText('SELF ASSESSMENT PENDING')).toBeTruthy()
  })

  it('shows configured KPI and employee counts and offers publishing only for drafts', async () => {
    renderApp('/hr/cycles/8')

    const configuredCount = await screen.findByText('Configured KPIs')
    const assignedCount = screen.getByText('Assigned employees')
    expect(configuredCount.parentElement.textContent).toContain('1')
    expect(assignedCount.parentElement.textContent).toContain('1')
    expect(screen.getByRole('button', { name: 'Publish Cycle' }).disabled).toBe(false)
    expect(screen.getByText(/freezes KPI snapshots/)).toBeTruthy()
  })

  it.each([
    ['no KPIs', [], assignedEmployees, 'Assign at least one KPI before publishing.'],
    ['no employees', assignedKpis, [], 'Assign at least one employee before publishing.'],
    [
      'no KPIs or employees',
      [],
      [],
      'Assign at least one KPI and at least one employee before publishing.',
    ],
  ])('disables publishing when the cycle has %s', async (
    _label,
    kpis,
    employees,
    message,
  ) => {
    cycleApi.getCycleKpis.mockResolvedValueOnce(kpis)
    cycleApi.getCycleEmployees.mockResolvedValueOnce(employees)
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')

    expect(await screen.findByText(message)).toBeTruthy()
    const publishButton = screen.getByRole('button', { name: 'Publish Cycle' })
    expect(publishButton.disabled).toBe(true)
    await user.click(publishButton)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(cycleApi.publishCycle).not.toHaveBeenCalled()
  })

  it.each(['PUBLISHED', 'IN_PROGRESS', 'COMPLETED'])(
    'does not offer publishing or draft-only editing for %s cycles',
    async (status) => {
      cycleApi.getCycleById.mockResolvedValueOnce({ ...cycle, status })
      renderApp('/hr/cycles/8')

      expect(await screen.findByRole('heading', { name: 'Publishing summary' })).toBeTruthy()
      expect(screen.queryByRole('button', { name: 'Publish Cycle' })).toBeNull()
      expect(screen.queryByRole('link', { name: 'Edit cycle' })).toBeNull()
    },
  )

  it('does not offer publication until KPI and employee counts have loaded', async () => {
    cycleApi.getCycleKpis.mockRejectedValueOnce({
      response: { status: 500 },
    })
    renderApp('/hr/cycles/8')

    expect(await screen.findByText('Evalora could not complete your request. Please try again later.'))
      .toBeTruthy()
    expect(screen.getByRole('button', { name: 'Publish Cycle' }).disabled).toBe(true)
  })

  it('opens a cycle-specific confirmation and does not publish when cancelled', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Publish Cycle' }))

    const message = dialogText()
    expect(message).toContain('Publish "Annual 2026"?')
    expect(message).toContain('Configured KPIs: 1')
    expect(message).toContain('Assigned employees: 1')
    expect(message).toContain('may be irreversible')
    await cancelDialog(user)
    expect(cycleApi.publishCycle).not.toHaveBeenCalled()
  })

  it('publishes once after explicit confirmation and refreshes cycle and assignment details', async () => {
    const user = userEvent.setup()
    const published = {
      ...cycle,
      status: 'PUBLISHED',
      publishedAt: '2026-11-02T10:00:00Z',
    }
    cycleApi.publishCycle.mockResolvedValueOnce(published)
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce(published)
    renderApp('/hr/cycles/8')

    await user.click(await screen.findByRole('button', { name: 'Publish Cycle' }))
    await confirmDialog(user, 'Publish cycle')

    expect(await screen.findByText(/published successfully\. Status: PUBLISHED/)).toBeTruthy()
    expect(screen.getByText('PUBLISHED')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Publish Cycle' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Edit cycle' })).toBeNull()
    expect(cycleApi.publishCycle).toHaveBeenCalledOnce()
    expect(cycleApi.publishCycle).toHaveBeenCalledWith('8')
    await waitFor(() => {
      expect(cycleApi.getCycleKpis.mock.calls.length).toBeGreaterThanOrEqual(3)
      expect(cycleApi.getCycleEmployees.mock.calls.length).toBeGreaterThanOrEqual(3)
    })
  })

  it('prevents duplicate publish requests while the confirmed request is pending', async () => {
    const user = userEvent.setup()
    let resolvePublish
    cycleApi.publishCycle.mockReturnValueOnce(new Promise((resolve) => {
      resolvePublish = resolve
    }))
    renderApp('/hr/cycles/8')
    const publishButton = await screen.findByRole('button', { name: 'Publish Cycle' })
    await user.click(publishButton)
    await confirmDialog(user, 'Publish cycle')

    const pendingButtons = screen.getAllByRole('button', { name: 'Publishing…' })
    expect(pendingButtons.length).toBeGreaterThan(0)
    for (const pendingButton of pendingButtons) {
      expect(pendingButton.disabled).toBe(true)
    }
    expect(cycleApi.publishCycle).toHaveBeenCalledOnce()
    resolvePublish({ ...cycle, status: 'PUBLISHED', publishedAt: '2026-11-02T10:00:00Z' })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Publish Cycle' })).toBeNull())
  })

  it('shows backend validation errors without reporting success', async () => {
    const user = userEvent.setup()
    cycleApi.publishCycle.mockRejectedValueOnce({
      response: {
        status: 400,
        data: { message: 'At least one KPI must be configured before publishing.' },
      },
    })
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Publish Cycle' }))
    await confirmDialog(user, 'Publish cycle')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('At least one KPI must be configured before publishing.')
    expect(screen.queryByText(/published successfully/)).toBeNull()
    expect(screen.getByText('Annual 2026')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Publish Cycle' }).disabled).toBe(false)
  })

  it('refreshes cycle and assignment information when publication conflicts', async () => {
    const user = userEvent.setup()
    cycleApi.publishCycle.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { message: 'Only a DRAFT cycle can be published.' },
      },
    })
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Publish Cycle' }))
    await confirmDialog(user, 'Publish cycle')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The cycle may have changed or its configuration may no longer be valid.')
    expect((await screen.findByRole('alert')).textContent)
      .toContain('Only a DRAFT cycle can be published.')
    await waitFor(() => expect(screen.getByText('PUBLISHED')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Publish Cycle' })).toBeNull()
  })

  it('shows retryable network failures and allows HR to retry publication', async () => {
    const user = userEvent.setup()
    cycleApi.publishCycle
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED', publishedAt: '2026-11-02T10:00:00Z' })
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED', publishedAt: '2026-11-02T10:00:00Z' })
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Publish Cycle' }))
    await confirmDialog(user, 'Publish cycle')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Unable to reach Evalora. Check your connection and whether the backend is running.')
    expect(screen.getByRole('button', { name: 'Publish Cycle' })).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Publish Cycle' }))
    await confirmDialog(user, 'Publish cycle')

    expect(await screen.findByText(/published successfully\. Status: PUBLISHED/)).toBeTruthy()
    expect(cycleApi.publishCycle).toHaveBeenCalledTimes(2)
  })

  it('offers active employees with enabled accounts whether or not they have an active current manager', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)

    expect(screen.getByRole('checkbox', { name: 'Select Erin Employee' }).checked).toBe(true)
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).checked).toBe(false)
    expect(screen.queryByRole('checkbox', { name: 'Select Disabled Employee' })).toBeNull()
    expect(screen.getByRole('checkbox', { name: 'Select Unassigned Employee' })).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Select Inactive Manager Employee' })).toBeTruthy()
    expect(screen.getAllByText('Mona Manager').length).toBeGreaterThan(0)
  })

  it('defaults a new assignment to the active current manager and allows HR to change it', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    const managerSelect = screen.getByRole('combobox', { name: 'Reviewing manager for Ethan Employee' })
    expect(managerSelect.value).toBe('7')
    await user.selectOptions(managerSelect, '8')
    expect(managerSelect.value).toBe('8')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledOnce())
    expect(cycleApi.configureCycleEmployees).toHaveBeenCalledWith('8', [
      { employeeId: 31, managerId: 7 },
      { employeeId: 32, managerId: 8 },
    ])
    const sentEmployeeIds = cycleApi.configureCycleEmployees.mock.calls[0][1]
      .map(({ employeeId }) => employeeId)
    expect(new Set(sentEmployeeIds).size).toBe(sentEmployeeIds.length)
    expect(await screen.findByText('Cycle employee assignments saved successfully.')).toBeTruthy()
  })

  it('requires an explicit active manager for an employee without an active current manager', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Unassigned Employee' }))
    const managerSelect = screen.getByRole('combobox', { name: 'Reviewing manager for Unassigned Employee' })

    expect(managerSelect.value).toBe('')
    expect(managerSelect.querySelector('option[value="9"]')).toBeNull()
    expect(managerSelect.querySelector('option[value="10"]')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))
    expect((await screen.findByRole('alert')).textContent)
      .toContain('Select an active reviewing manager for every selected employee.')
    expect(cycleApi.configureCycleEmployees).not.toHaveBeenCalled()

    await user.selectOptions(managerSelect, '8')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))
    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledWith('8', [
      { employeeId: 31, managerId: 7 },
      { employeeId: 34, managerId: 8 },
    ]))
  })

  it('prevents duplicate employee assignment submissions while saving', async () => {
    const user = userEvent.setup()
    let resolveConfigure
    cycleApi.configureCycleEmployees.mockReturnValueOnce(new Promise((resolve) => {
      resolveConfigure = resolve
    }))
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    const saveButton = screen.getByRole('button', { name: 'Save employee assignments' })
    await user.click(saveButton)

    expect(screen.getByRole('button', { name: 'Saving…' }).disabled).toBe(true)
    expect(cycleApi.configureCycleEmployees).toHaveBeenCalledOnce()
    resolveConfigure(assignedEmployees)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save employee assignments' }).disabled).toBe(false)
    })
  })

  it('retains existing manager snapshots even when the employee current manager differs', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)

    const existingManager = screen.getByRole('combobox', {
      name: 'Reviewing manager for Erin Employee',
    })
    expect(existingManager.value).toBe('7')
    expect(existingManager.selectedOptions[0].textContent).toContain('Mona Manager')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledWith('8', [
      { employeeId: 31, managerId: 7 },
    ]))
  })

  it('lets HR replace an inactive historical cycle manager without dropping the employee', async () => {
    const user = userEvent.setup()
    cycleApi.getCycleEmployees.mockResolvedValue([{
      ...assignedEmployees[0],
      managerId: 9,
      managerName: 'Legacy Manager',
    }])
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)

    const managerSelect = screen.getByRole('combobox', {
      name: 'Reviewing manager for Erin Employee',
    })
    expect(managerSelect.value).toBe('9')
    expect(managerSelect.selectedOptions[0].textContent)
      .toContain('Legacy Manager (inactive or unavailable)')
    expect(managerSelect.querySelector('option[value="9"]')).toBeTruthy()
    expect(managerSelect.querySelector('option[value="7"]')).toBeTruthy()
    expect(managerSelect.querySelector('option[value="8"]')).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Select Erin Employee' }).checked).toBe(true)

    await user.selectOptions(managerSelect, '8')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))
    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledWith('8', [
      { employeeId: 31, managerId: 8 },
    ]))
  })

  it('preserves the full roster and manager selections after a backend inactive-manager conflict', async () => {
    const user = userEvent.setup()
    cycleApi.configureCycleEmployees.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'The selected manager is inactive.' } },
    })
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    const ethanManager = screen.getByRole('combobox', {
      name: 'Reviewing manager for Ethan Employee',
    })
    await user.selectOptions(ethanManager, '8')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    expect(await screen.findByText(
      'A selected manager is inactive or disabled. Your roster and manager selections are preserved; choose an active manager and save again.',
    )).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Select Erin Employee' }).checked).toBe(true)
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).checked).toBe(true)
    expect(screen.getByRole('combobox', {
      name: 'Reviewing manager for Erin Employee',
    }).value).toBe('7')
    expect(screen.getByRole('combobox', {
      name: 'Reviewing manager for Ethan Employee',
    }).value).toBe('8')
    expect(cycleApi.configureCycleEmployees).toHaveBeenCalledOnce()

    await user.selectOptions(screen.getByRole('combobox', {
      name: 'Reviewing manager for Ethan Employee',
    }), '7')
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))
    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledTimes(2))
    expect(cycleApi.configureCycleEmployees.mock.calls[1][1]).toEqual([
      { employeeId: 31, managerId: 7 },
      { employeeId: 32, managerId: 7 },
    ])
  })

  it('shows loading while eligible employee choices are fetched', async () => {
    const user = userEvent.setup()
    let resolveEmployees
    employeeApi.listEmployees.mockReturnValueOnce(new Promise((resolve) => {
      resolveEmployees = resolve
    }))
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Manage Employees' }))
    await waitFor(() => expect(employeeApi.listEmployees).toHaveBeenCalledWith('ACTIVE'))

    expect(screen.getAllByRole('status').some((status) => status.textContent.includes('Loading'))).toBe(true)
    resolveEmployees(activeEmployees)
    expect(await screen.findByRole('checkbox', { name: /Ethan Employee/ })).toBeTruthy()
  })

  it('allows clearing the roster using the backend full-replacement contract', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Erin Employee' }))
    expect(screen.getByText('Saving an empty selection removes every employee from this cycle.'))
      .toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleEmployees).toHaveBeenCalledWith('8', []))
    expect(await screen.findByText('Cycle employee assignments saved successfully.')).toBeTruthy()
  })

  it('shows an empty state and retryable errors for eligible employee choices', async () => {
    const user = userEvent.setup()
    cycleApi.getCycleEmployees.mockResolvedValue([])
    employeeApi.listEmployees.mockRejectedValueOnce({
      response: { status: 500 },
    }).mockResolvedValueOnce([])
    managerApi.listManagers.mockResolvedValueOnce(activeManagers).mockResolvedValueOnce([])
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)

    expect(await screen.findByText('Evalora could not complete your request. Please try again later.'))
      .toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('No eligible employees are available.')).toBeTruthy()
  })

  it('preserves selected employees and refreshes state after a non-draft conflict', async () => {
    const user = userEvent.setup()
    cycleApi.configureCycleEmployees.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'Only a DRAFT cycle can be modified.' } },
    })
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    expect(await screen.findByText('Only a DRAFT cycle can be modified.')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('PUBLISHED')).toBeTruthy())
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).checked).toBe(true)
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).disabled).toBe(true)
    expect(cycleApi.configureCycleEmployees).toHaveBeenCalledOnce()
  })

  it('keeps the employee editor closable and read-only after the cycle leaves draft', async () => {
    const user = userEvent.setup()
    cycleApi.configureCycleEmployees.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'Only a DRAFT cycle can be modified.' } },
    })
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    expect(await screen.findByText('Only a DRAFT cycle can be modified.')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('PUBLISHED')).toBeTruthy())
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Save employee assignments' }).disabled).toBe(true)

    const closeButton = screen.getByRole('button', { name: 'Close employee manager' })
    expect(closeButton.disabled).toBe(false)
    await user.click(closeButton)

    expect(screen.queryByRole('heading', { name: 'Manage cycle employees' })).toBeNull()
    expect(cycleApi.configureCycleEmployees).toHaveBeenCalledOnce()
  })

  it('rechecks draft status before saving and keeps the selected roster when the cycle changed', async () => {
    const user = userEvent.setup()
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'IN_PROGRESS' })
    renderApp('/hr/cycles/8')
    await openEmployeeManager(user)
    await user.click(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }))
    await user.click(screen.getByRole('button', { name: 'Save employee assignments' }))

    expect(await screen.findByText(
      'This cycle is no longer a draft and its employee roster cannot be changed.',
    )).toBeTruthy()
    expect(cycleApi.configureCycleEmployees).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox', { name: 'Select Ethan Employee' }).checked).toBe(true)
  })

  it('shows employee assignments read-only for a non-draft cycle', async () => {
    cycleApi.getCycleById.mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')

    expect(await screen.findByRole('heading', { name: 'Assigned Employees' })).toBeTruthy()
    expect(await screen.findByText('Erin Employee')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Manage Employees' })).toBeNull()
    expect(employeeApi.listEmployees).not.toHaveBeenCalled()
    expect(cycleApi.configureCycleEmployees).not.toHaveBeenCalled()
  })

  it('loads active KPIs for selection and does not offer inactive KPIs', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)

    expect(screen.getByText('Technical skills')).toBeTruthy()
    expect(screen.getByText('Teamwork')).toBeTruthy()
    expect(screen.queryByText('Legacy process')).toBeNull()
    expect(screen.getByRole('checkbox', { name: /Communication/ }).checked).toBe(true)
  })

  it('shows loading, empty, and retryable error states for active KPI choices', async () => {
    const user = userEvent.setup()
    let resolveKpis
    kpiApi.getKpis.mockReturnValueOnce(new Promise((resolve) => { resolveKpis = resolve }))
    const loading = renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Manage KPIs' }))
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenCalledWith(true))
    expect(screen.getAllByRole('status').some((status) => status.textContent.includes('Loading'))).toBe(true)
    resolveKpis(activeKpis)
    expect(await screen.findByText('Technical skills')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Close KPI manager' }))
    loading.unmount()

    cycleApi.getCycleKpis.mockResolvedValue([])
    kpiApi.getKpis.mockResolvedValueOnce([])
    const empty = renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Manage KPIs' }))
    expect(await screen.findByText('No active KPIs are available to assign.')).toBeTruthy()
    empty.unmount()

    kpiApi.getKpis
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce(activeKpis)
    renderApp('/hr/cycles/8')
    await user.click(await screen.findByRole('button', { name: 'Manage KPIs' }))
    expect(await screen.findByText('Evalora could not complete your request. Please try again later.'))
      .toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Technical skills')).toBeTruthy()
  })

  it('replaces multiple KPI assignments with valid weighted values totaling 100%', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)

    await user.click(screen.getByRole('checkbox', { name: /Technical skills/ }))
    await user.clear(screen.getByRole('textbox', { name: 'Communication weightage' }))
    await user.type(screen.getByRole('textbox', { name: 'Communication weightage' }), '60')
    await user.type(screen.getByRole('textbox', { name: 'Technical skills weightage' }), '40')
    expect(screen.getByText('Exactly 100%')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce())
    expect(cycleApi.configureCycleKpis).toHaveBeenCalledWith('8', [
      { kpiId: 8, weightage: 60 },
      { kpiId: 9, weightage: 40 },
    ])
    expect((await screen.findByRole('status')).textContent)
      .toContain('Cycle KPI assignments saved successfully.')
  })

  it('saves every selected KPI with null weightage in unweighted mode', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('radio', { name: /Use equal\/unweighted scoring/ }))
    await user.click(screen.getByRole('checkbox', { name: /Technical skills/ }))
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce())
    expect(cycleApi.configureCycleKpis).toHaveBeenCalledWith('8', [
      { kpiId: 8, weightage: null },
      { kpiId: 9, weightage: null },
    ])
  })

  it('clears weightages when switching modes and rejects incomplete weighted values', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('radio', { name: /Use equal\/unweighted scoring/ }))
    await user.click(screen.getByRole('radio', { name: /Configure weightages/ }))

    expect(screen.getByRole('textbox', { name: 'Communication weightage' }).value).toBe('')
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))
    expect((await screen.findByRole('alert')).textContent)
      .toContain('Enter a weightage for every selected KPI')
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
  })

  it.each([
    ['0', 'Weightage must be greater than 0 and no greater than 100.'],
    ['100.01', 'Weightage must be greater than 0 and no greater than 100.'],
    ['12.345', 'Weightage must be a number with at most 2 decimal places.'],
  ])('rejects invalid individual weightage %s', async (value, message) => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    const weightage = screen.getByRole('textbox', { name: 'Communication weightage' })
    await user.clear(weightage)
    await user.type(weightage, value)
    expect(screen.getByText(message)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect((await screen.findByRole('alert')).textContent).toContain(message)
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
  })

  it.each([
    ['40', '50', '90.00%'],
    ['60', '50', '110.00%'],
  ])('rejects a weighted total of %s + %s', async (first, second, total) => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('checkbox', { name: /Technical skills/ }))
    const communication = screen.getByRole('textbox', { name: 'Communication weightage' })
    await user.clear(communication)
    await user.type(communication, first)
    await user.type(screen.getByRole('textbox', { name: 'Technical skills weightage' }), second)
    expect(document.querySelector('.cycle-weightage-total').textContent).toContain(total)
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect((await screen.findByRole('alert')).textContent).toContain('Weightages must total exactly 100%')
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
  })

  it('rejects an empty selection rather than silently clearing assignments', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('checkbox', { name: /Communication/ }))
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect((await screen.findByRole('alert')).textContent).toContain('Select at least one KPI')
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
  })

  it('prevents duplicate KPI IDs in the replacement payload', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    expect(screen.getAllByRole('checkbox', { name: /Communication/ })).toHaveLength(1)
    await user.click(screen.getByRole('checkbox', { name: /Technical skills/ }))
    await user.clear(screen.getByRole('textbox', { name: 'Communication weightage' }))
    await user.type(screen.getByRole('textbox', { name: 'Communication weightage' }), '60')
    await user.type(screen.getByRole('textbox', { name: 'Technical skills weightage' }), '40')
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    await waitFor(() => expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce())
    const sentIds = cycleApi.configureCycleKpis.mock.calls[0][1].map(({ kpiId }) => kpiId)
    expect(new Set(sentIds).size).toBe(sentIds.length)
  })

  it('preserves selected values after a backend conflict and refreshes cycle state', async () => {
    const user = userEvent.setup()
    cycleApi.configureCycleKpis.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'Only a DRAFT cycle can be modified.' } },
    })
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('checkbox', { name: /Technical skills/ }))
    const weightage = screen.getByRole('textbox', { name: 'Communication weightage' })
    await user.clear(weightage)
    await user.type(weightage, '60')
    await user.type(screen.getByRole('textbox', { name: 'Technical skills weightage' }), '40')
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect(await screen.findByText('Only a DRAFT cycle can be modified.')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('PUBLISHED')).toBeTruthy())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Close KPI manager' })).toBeTruthy())
    expect(screen.getByRole('checkbox', { name: /Technical skills/ }).checked).toBe(true)
    expect(screen.getByRole('textbox', { name: 'Communication weightage' }).value).toBe('60')
    expect(screen.getByRole('checkbox', { name: /Technical skills/ }).disabled).toBe(true)
    expect(screen.getByRole('textbox', { name: 'Communication weightage' }).disabled).toBe(true)
    expect(screen.queryByRole('button', { name: 'Manage KPIs' })).toBeNull()
    expect(cycleApi.getCycleById).toHaveBeenCalledTimes(3)
    expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce()
  })

  it('preserves selected weightages after an assignment 404 response', async () => {
    const user = userEvent.setup()
    cycleApi.configureCycleKpis.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Performance cycle not found.' } },
    })
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    const weightage = screen.getByRole('textbox', { name: 'Communication weightage' })
    await user.clear(weightage)
    await user.type(weightage, '100')
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect(await screen.findByText('Performance cycle not found.')).toBeTruthy()
    expect(screen.getByRole('textbox', { name: 'Communication weightage' }).value).toBe('100')
    expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce()
  })

  it('rechecks the backend cycle state before assignment and does not submit a stale draft', async () => {
    const user = userEvent.setup()
    cycleApi.getCycleById
      .mockResolvedValueOnce(cycle)
      .mockResolvedValueOnce({ ...cycle, status: 'IN_PROGRESS' })
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    await user.click(screen.getByRole('button', { name: 'Save KPI assignments' }))

    expect(await screen.findByText('This cycle is no longer a draft and cannot be changed.')).toBeTruthy()
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
    expect(cycleApi.getCycleKpis).toHaveBeenCalledTimes(3)
  })

  it('prevents duplicate assignment submissions while saving', async () => {
    const user = userEvent.setup()
    let resolveConfigure
    cycleApi.configureCycleKpis.mockReturnValueOnce(new Promise((resolve) => { resolveConfigure = resolve }))
    renderApp('/hr/cycles/8')
    await openKpiManager(user)
    const saveButton = screen.getByRole('button', { name: 'Save KPI assignments' })
    await user.click(saveButton)

    const pendingButton = screen.getByRole('button', { name: 'Saving…' })
    expect(pendingButton.disabled).toBe(true)
    expect(cycleApi.configureCycleKpis).toHaveBeenCalledOnce()
    resolveConfigure(assignedKpis)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save KPI assignments' }).disabled).toBe(false))
  })

  it('shows KPI assignments read-only for a non-draft cycle', async () => {
    cycleApi.getCycleById.mockResolvedValueOnce({ ...cycle, status: 'PUBLISHED' })
    renderApp('/hr/cycles/8')

    expect(await screen.findByRole('heading', { name: 'Assigned KPIs' })).toBeTruthy()
    expect(await screen.findByText('Communication')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Manage KPIs' })).toBeNull()
    expect(kpiApi.getKpis).not.toHaveBeenCalled()
    expect(cycleApi.configureCycleKpis).not.toHaveBeenCalled()
  })

  it('prepopulates and updates a draft cycle using only request DTO fields', async () => {
    const user = userEvent.setup()
    renderApp('/hr/cycles/8/edit')

    expect((await screen.findByLabelText('Cycle name')).value).toBe('Annual 2026')
    await user.clear(screen.getByLabelText('Cycle name'))
    await user.type(screen.getByLabelText('Cycle name'), '  Fiscal 2026 ')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(cycleApi.updateCycle).toHaveBeenCalledOnce())
    expect(cycleApi.updateCycle).toHaveBeenCalledWith('8', {
      name: 'Fiscal 2026',
      description: 'Annual performance review.',
      startDate: '2026-01-01',
      endDate: '2026-12-31',
      selfAssessmentDeadline: '2026-11-30',
      managerReviewDeadline: '2026-12-15',
      hrReviewDeadline: '2026-12-31',
    })
    expect(await screen.findByRole('heading', { name: 'Performance cycle details' })).toBeTruthy()
  })

  it('blocks editing non-draft cycles, including a direct edit route', async () => {
    cycleApi.getCycleById.mockResolvedValueOnce({ ...cycle, status: 'IN_PROGRESS' })
    renderApp('/hr/cycles/8/edit')

    expect((await screen.findByRole('alert')).textContent).toContain('Only a DRAFT cycle can be updated')
    expect(screen.queryByLabelText('Cycle name')).toBeNull()
    expect(cycleApi.updateCycle).not.toHaveBeenCalled()
  })

  it('shows a not-found response when the cycle cannot be loaded for editing', async () => {
    cycleApi.getCycleById.mockRejectedValueOnce({
      response: { status: 404, data: { message: 'Performance cycle not found.' } },
    })
    renderApp('/hr/cycles/404/edit')

    expect(await screen.findByText('Performance cycle not found.')).toBeTruthy()
    expect(screen.queryByLabelText('Cycle name')).toBeNull()
    expect(cycleApi.updateCycle).not.toHaveBeenCalled()
  })

  it('shows update conflicts and preserves the entered form values', async () => {
    const user = userEvent.setup()
    cycleApi.updateCycle.mockRejectedValueOnce({
      response: { status: 409, data: { message: 'A cycle with this name already exists.' } },
    })
    renderApp('/hr/cycles/8/edit')

    const nameInput = await screen.findByLabelText('Cycle name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Duplicate cycle name')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('A cycle with this name already exists.')).toBeTruthy()
    expect(screen.getByLabelText('Cycle name').value).toBe('Duplicate cycle name')
    expect(cycleApi.getCycleById).toHaveBeenCalledOnce()
  })
})
