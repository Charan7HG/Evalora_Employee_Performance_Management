import { render, screen, waitFor } from '@testing-library/react'
import { confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as dashboardApi from '../../api/dashboardApi.js'
import * as kpiApi from '../../api/kpiApi.js'
import { TOKEN_STORAGE_KEY } from '../../api/tokenStorage.js'
import App from '../../App.jsx'
import { AuthProvider } from '../../context/AuthContext.jsx'

vi.mock('../../api/authApi.js', () => ({
  login: vi.fn(),
  getCurrentUser: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
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

const kpis = [
  {
    id: 8,
    name: 'Customer focus',
    description: 'Understand customer needs and deliver value.',
    category: 'Service',
    defaultWeightage: 12.5,
    active: true,
    createdById: 1,
    createdAt: '2026-01-02T00:00:00Z',
    updatedAt: '2026-01-03T00:00:00Z',
  },
  {
    id: 9,
    name: 'Legacy process',
    description: null,
    category: 'Operations',
    defaultWeightage: null,
    active: false,
    createdById: 1,
    createdAt: '2025-01-02T00:00:00Z',
    updatedAt: '2025-01-03T00:00:00Z',
  },
]

function renderApp(path, role = 'HR') {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'kpi-test.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}

describe('HR KPI management', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    dashboardApi.getManagerDashboard.mockResolvedValue({})
    kpiApi.getKpis.mockResolvedValue(kpis)
    kpiApi.getKpiById.mockImplementation(async (id) => kpis.find((item) => item.id === Number(id)))
    kpiApi.createKpi.mockResolvedValue({ ...kpis[0], id: 10, name: 'New KPI' })
    kpiApi.updateKpi.mockResolvedValue(kpis[0])
    kpiApi.deactivateKpi.mockResolvedValue({ ...kpis[0], active: false })
    kpiApi.reactivateKpi.mockResolvedValue({ ...kpis[1], active: true })
  })

  afterEach(() => vi.restoreAllMocks())

  it('only adds the KPI navigation link for HR', async () => {
    const hr = renderApp('/hr/kpis')
    expect(await screen.findByText('Customer focus')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'KPIs' }).getAttribute('href')).toBe('/hr/kpis')
    hr.unmount()

    renderApp('/manager/dashboard', 'MANAGER')
    expect(await screen.findByText('Your current team and assessment workload.')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'KPIs' })).toBeNull()
  })

  it.each(['MANAGER', 'EMPLOYEE'])('%s cannot access KPI management', async (role) => {
    renderApp('/hr/kpis', role)

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(kpiApi.getKpis).not.toHaveBeenCalled()
  })

  it('redirects unauthenticated KPI access to login', async () => {
    render(
      <MemoryRouter initialEntries={['/hr/kpis']}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(kpiApi.getKpis).not.toHaveBeenCalled()
  })

  it('shows loading and then renders backend fields without audit internals', async () => {
    let resolveList
    kpiApi.getKpis.mockReturnValueOnce(new Promise((resolve) => { resolveList = resolve }))
    renderApp('/hr/kpis')

    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenCalledOnce())
    expect(screen.getByRole('status').textContent).toContain('Loading')
    resolveList(kpis)

    expect(await screen.findByText('Customer focus')).toBeTruthy()
    expect(screen.getByText('Service')).toBeTruthy()
    expect(screen.getByText('12.50%')).toBeTruthy()
    expect(screen.queryByText('createdById')).toBeNull()
  })

  it('filters all, active, inactive, and searches the loaded result set', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis')

    expect(await screen.findByText('Customer focus')).toBeTruthy()
    expect(screen.getByText('Legacy process')).toBeTruthy()
    await user.selectOptions(screen.getByLabelText('Status'), 'active')
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenLastCalledWith(true))
    await user.selectOptions(screen.getByLabelText('Status'), 'inactive')
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenLastCalledWith(false))
    await user.selectOptions(screen.getByLabelText('Status'), 'all')
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenLastCalledWith(undefined))

    await user.type(screen.getByRole('searchbox', { name: 'Search KPIs' }), 'operations')
    expect(screen.getByText('Legacy process')).toBeTruthy()
    expect(screen.queryByText('Customer focus')).toBeNull()
  })

  it('shows empty and error states with retry', async () => {
    kpiApi.getKpis.mockResolvedValueOnce([])
    const user = userEvent.setup()
    renderApp('/hr/kpis')
    expect(await screen.findByText('No KPIs match this status filter.')).toBeTruthy()

    kpiApi.getKpis
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce(kpis)
    await user.selectOptions(screen.getByLabelText('Status'), 'active')
    expect(await screen.findByText('Evalora could not complete your request. Please try again later.')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Customer focus')).toBeTruthy()
  })

  it('validates KPI fields and sends a trimmed accepted create payload', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis/new')

    await user.click(await screen.findByRole('button', { name: 'Create KPI' }))
    expect((await screen.findByRole('alert')).textContent).toContain('KPI name is required')
    expect(kpiApi.createKpi).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText(/KPI name/), '  Customer focus  ')
    await user.type(screen.getByLabelText('Category'), ' Service ')
    await user.type(screen.getByLabelText('Description'), '  Work with customers.  ')
    await user.type(screen.getByLabelText(/Default weightage/), '12.345')
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))
    expect((await screen.findByRole('alert')).textContent).toContain('at most 2 decimal places')
    expect(kpiApi.createKpi).not.toHaveBeenCalled()

    await user.clear(screen.getByLabelText(/Default weightage/))
    await user.type(screen.getByLabelText(/Default weightage/), '12.50')
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))
    await waitFor(() => expect(kpiApi.createKpi).toHaveBeenCalledOnce())
    expect(kpiApi.createKpi).toHaveBeenCalledWith({
      name: 'Customer focus',
      description: 'Work with customers.',
      category: 'Service',
      defaultWeightage: 12.5,
      kpiType: 'MANAGER',
    })
    expect((await screen.findByRole('status')).textContent).toContain('KPI created successfully.')
  })

  it.each(['0', '100.01'])('rejects invalid weightage %s', async (weightage) => {
    const user = userEvent.setup()
    renderApp('/hr/kpis/new')
    await user.type(await screen.findByLabelText(/KPI name/), 'Quality')
    await user.type(screen.getByLabelText(/Default weightage/), weightage)
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))

    expect((await screen.findByRole('alert')).textContent).toContain('greater than 0 and at most 100')
    expect(kpiApi.createKpi).not.toHaveBeenCalled()
  })

  it('renders KPI details and links to edit', async () => {
    renderApp('/hr/kpis/8')

    expect(await screen.findByText('Customer focus')).toBeTruthy()
    expect(screen.getByText('Understand customer needs and deliver value.')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Edit KPI' }).getAttribute('href')).toBe('/hr/kpis/8/edit')
  })

  it('prepopulates and updates only accepted KPI fields', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis/8/edit')

    expect((await screen.findByLabelText(/KPI name/)).value).toBe('Customer focus')
    expect(screen.getByLabelText('Category').value).toBe('Service')
    await user.clear(screen.getByLabelText('Category'))
    await user.type(screen.getByLabelText('Category'), 'Operations')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(kpiApi.updateKpi).toHaveBeenCalledOnce())
    expect(kpiApi.updateKpi).toHaveBeenCalledWith('8', {
      name: 'Customer focus',
      description: 'Understand customer needs and deliver value.',
      category: 'Operations',
      defaultWeightage: 12.5,
      kpiType: 'MANAGER',
    })
    expect((await screen.findByRole('status')).textContent).toContain('KPI updated successfully.')
  })

  it('displays API validation and duplicate-name conflict messages', async () => {
    const user = userEvent.setup()
    kpiApi.createKpi.mockRejectedValue({
      response: { status: 409, data: { message: 'A KPI with this name already exists.' } },
    })
    renderApp('/hr/kpis/new')

    await user.type(await screen.findByLabelText(/KPI name/), 'Customer focus')
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))

    expect(await screen.findByText('A KPI with this name already exists.')).toBeTruthy()
    expect(kpiApi.createKpi).toHaveBeenCalledOnce()
  })

  it('requires confirmation before deactivation and refreshes the list on success', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis')
    await screen.findByText('Customer focus')

    await user.click(screen.getAllByRole('button', { name: 'Deactivate' })[0])
    expect(dialogText()).toContain('Deactivate "Customer focus"')
    await confirmDialog(user, 'Deactivate')
    await waitFor(() => expect(kpiApi.deactivateKpi).toHaveBeenCalledWith(8))
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenCalledTimes(2))
    expect((await screen.findByRole('status')).textContent).toContain('deactivated successfully')
  })

  it('requires confirmation before reactivation', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis')
    await screen.findByText('Legacy process')
    await user.click(screen.getByRole('button', { name: 'Reactivate' }))

    expect(dialogText()).toContain('Reactivate "Legacy process"')
    await confirmDialog(user, 'Reactivate')
    await waitFor(() => expect(kpiApi.reactivateKpi).toHaveBeenCalledWith(9))
  })

  it('shows conflict errors without changing the displayed KPI status', async () => {
    const user = userEvent.setup()
    kpiApi.deactivateKpi.mockRejectedValue({
      response: { status: 409, data: { message: 'KPI is already inactive.' } },
    })
    renderApp('/hr/kpis')
    await screen.findByText('Customer focus')
    await user.click(screen.getAllByRole('button', { name: 'Deactivate' })[0])
    await confirmDialog(user, 'Deactivate')

    expect(await screen.findByText('KPI is already inactive.')).toBeTruthy()
    expect(screen.getByText('Active', { selector: 'span.status-pill' })).toBeTruthy()
    expect(kpiApi.getKpis).toHaveBeenCalledOnce()
  })

  it('prevents duplicate status submissions while the request is pending', async () => {
    const user = userEvent.setup()
    let resolveDeactivate
    kpiApi.deactivateKpi.mockReturnValueOnce(new Promise((resolve) => { resolveDeactivate = resolve }))
    renderApp('/hr/kpis')
    await screen.findByText('Customer focus')
    await user.click(screen.getAllByRole('button', { name: 'Deactivate' })[0])
    await confirmDialog(user, 'Deactivate')

    const pendingButton = screen.getByRole('button', { name: 'Saving…' })
    expect(pendingButton.disabled).toBe(true)
    expect(screen.getAllByRole('button').filter((button) => button.textContent === 'Reactivate')[0].disabled)
      .toBe(true)
    expect(kpiApi.deactivateKpi).toHaveBeenCalledOnce()
    resolveDeactivate({ ...kpis[0], active: false })
    await waitFor(() => expect(kpiApi.getKpis).toHaveBeenCalledTimes(2))
  })
})
