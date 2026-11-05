import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
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

vi.mock('../../api/dashboardApi.js', () => ({
  getHrDashboard: vi.fn(),
  getManagerDashboard: vi.fn(),
  getEmployeeDashboard: vi.fn(),
}))

vi.mock('../../api/kpiApi.js', () => ({
  getKpis: vi.fn(),
  getKpiById: vi.fn(),
  createKpi: vi.fn(),
  updateKpi: vi.fn(),
  deactivateKpi: vi.fn(),
  reactivateKpi: vi.fn(),
}))

const managerKpi = {
  id: 8,
  name: 'Customer focus',
  description: 'Understand customer needs.',
  category: 'Service',
  defaultWeightage: 50,
  kpiType: 'MANAGER',
  active: true,
}

const hrKpi = {
  id: 11,
  name: 'Policy compliance',
  description: 'Follows company policy.',
  category: 'Compliance',
  defaultWeightage: 50,
  kpiType: 'HR',
  active: true,
}

function renderApp(path) {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'kpi-type.jwt')
  authApi.getCurrentUser.mockResolvedValue({ id: 1, email: 'hr@example.test', role: 'HR' })
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('KPI assessment classification', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    kpiApi.getKpis.mockResolvedValue([managerKpi, hrKpi])
    kpiApi.createKpi.mockResolvedValue({ ...hrKpi, id: 12 })
    kpiApi.getKpiById.mockImplementation(async (id) => (
      Number(id) === hrKpi.id ? hrKpi : managerKpi
    ))
  })

  it('shows the assessment type of every KPI in the HR list', async () => {
    renderApp('/hr/kpis')

    expect(await screen.findByText('Customer focus')).toBeTruthy()
    expect(screen.getByText('Manager assessed')).toBeTruthy()
    expect(screen.getByText('HR assessed')).toBeTruthy()
  })

  it('shows the assessment type on the KPI detail page', async () => {
    renderApp('/hr/kpis/11')

    expect(await screen.findByText('Policy compliance')).toBeTruthy()
    expect(screen.getByText('HR assessed')).toBeTruthy()
  })

  it('creates an HR-assessed KPI with the classification selected in the form', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis/new')

    await user.type(await screen.findByLabelText(/KPI name/), 'Policy compliance')
    await user.selectOptions(screen.getByLabelText(/Assessment type/), 'HR')
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))

    await waitFor(() => expect(kpiApi.createKpi).toHaveBeenCalledOnce())
    expect(kpiApi.createKpi.mock.calls[0][0]).toMatchObject({
      name: 'Policy compliance',
      kpiType: 'HR',
    })
  })

  it('defaults a new KPI to the manager-assessed classification', async () => {
    const user = userEvent.setup()
    renderApp('/hr/kpis/new')

    expect((await screen.findByLabelText(/Assessment type/)).value).toBe('MANAGER')
    await user.type(screen.getByLabelText(/KPI name/), 'Quality')
    await user.click(screen.getByRole('button', { name: 'Create KPI' }))

    await waitFor(() => expect(kpiApi.createKpi).toHaveBeenCalledOnce())
    expect(kpiApi.createKpi.mock.calls[0][0].kpiType).toBe('MANAGER')
  })
})
