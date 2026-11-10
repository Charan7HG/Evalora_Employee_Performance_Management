import { render, screen, waitFor, within } from '@testing-library/react'
import { cancelDialog, confirmDialog, dialogText } from '../../test/dialogHelpers.js'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as authApi from '../../api/authApi.js'
import * as employeeApi from '../../api/employeeApi.js'
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

const users = {
  HR: { id: 1, email: 'hr@example.test', role: 'HR' },
  MANAGER: { id: 2, email: 'manager@example.test', role: 'MANAGER' },
  EMPLOYEE: { id: 3, email: 'employee@example.test', role: 'EMPLOYEE' },
}

const employee = {
  id: 21,
  userId: 121,
  employeeCode: 'EMP-021',
  fullName: 'Asha Rao',
  email: 'asha@example.test',
  role: 'EMPLOYEE',
  department: 'Engineering',
  designation: 'Developer',
  joiningDate: '2026-01-05',
  status: 'ACTIVE',
  accountEnabled: true,
  managerId: 31,
  managerName: 'Mona Boss',
  createdAt: '2026-01-05T00:00:00Z',
  updatedAt: '2026-01-05T00:00:00Z',
}

const inactiveManager = {
  id: 31,
  userId: 131,
  employeeCode: 'MGR-031',
  fullName: 'Mona Boss',
  email: 'mona@example.test',
  role: 'MANAGER',
  department: 'Operations',
  designation: 'Manager',
  joiningDate: null,
  status: 'INACTIVE',
  accountEnabled: false,
  managerId: null,
  managerName: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function renderApp(path, role = 'HR') {
  localStorage.setItem(TOKEN_STORAGE_KEY, 'staff-test.jwt')
  authApi.getCurrentUser.mockResolvedValue(users[role])
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('HR employee and manager management', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    employeeApi.listEmployees.mockResolvedValue([employee])
    employeeApi.getEmployee.mockResolvedValue(employee)
    employeeApi.createEmployee.mockResolvedValue(employee)
    employeeApi.updateEmployee.mockResolvedValue(employee)
    employeeApi.assignEmployeeManager.mockResolvedValue({
      ...employee,
      managerId: 32,
      managerName: 'Ravi Shah',
    })
    employeeApi.deactivateEmployee.mockResolvedValue({ ...employee, status: 'INACTIVE' })
    employeeApi.reactivateEmployee.mockResolvedValue(employee)
    managerApi.listManagers.mockResolvedValue([{
      ...inactiveManager,
      id: 32,
      employeeCode: 'MGR-032',
      fullName: 'Ravi Shah',
      status: 'ACTIVE',
      accountEnabled: true,
    }])
    managerApi.getManager.mockResolvedValue(inactiveManager)
    managerApi.createManager.mockResolvedValue(inactiveManager)
    managerApi.updateManager.mockResolvedValue(inactiveManager)
    managerApi.deactivateManager.mockResolvedValue(inactiveManager)
    managerApi.reactivateManager.mockResolvedValue({ ...inactiveManager, status: 'ACTIVE' })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('lets HR open employee and manager lists with corresponding active filters', async () => {
    const employeeView = renderApp('/hr/employees')
    expect(await screen.findByText('Asha Rao')).toBeTruthy()
    expect(employeeApi.listEmployees).toHaveBeenCalledWith('ACTIVE')
    employeeView.unmount()

    renderApp('/hr/managers')
    expect(await screen.findByText('Ravi Shah')).toBeTruthy()
    expect(managerApi.listManagers).toHaveBeenCalledWith('ACTIVE')
  })

  it('filters the backend list by status and searches the returned records', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees')
    expect(await screen.findByText('Asha Rao')).toBeTruthy()
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'not found')
    expect(screen.getByText('No matching records found.')).toBeTruthy()

    await user.clear(screen.getByRole('searchbox', { name: 'Search' }))
    await user.selectOptions(screen.getByLabelText('Account status'), 'INACTIVE')
    await waitFor(() => expect(employeeApi.listEmployees).toHaveBeenCalledWith('INACTIVE'))
  })

  it.each([
    ['MANAGER', '/hr/employees'],
    ['EMPLOYEE', '/hr/managers'],
  ])('%s cannot access HR staff-management routes', async (role, path) => {
    renderApp(path, role)

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(employeeApi.listEmployees).not.toHaveBeenCalled()
    expect(managerApi.listManagers).not.toHaveBeenCalled()
  })

  it('redirects an unauthenticated visitor away from employee management', async () => {
    render(
      <MemoryRouter initialEntries={['/hr/employees']}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: 'Welcome to Evalora' })).toBeTruthy()
    expect(employeeApi.listEmployees).not.toHaveBeenCalled()
  })

  it('renders employee details from the response without exposing security fields', async () => {
    renderApp('/hr/employees/21')

    expect(await screen.findByText('Asha Rao')).toBeTruthy()
    expect(screen.getByText('EMP-021')).toBeTruthy()
    expect(screen.getByText('Mona Boss')).toBeTruthy()
    expect(screen.getByText('asha@example.test')).toBeTruthy()
    expect(screen.queryByText('passwordHash')).toBeNull()
    expect(employeeApi.getEmployee).toHaveBeenCalledWith('21')
  })

  it('shows the current manager and loads only eligible active managers', async () => {
    const user = userEvent.setup()
    managerApi.listManagers.mockResolvedValue([
      {
        ...inactiveManager,
        id: 32,
        employeeCode: 'MGR-032',
        fullName: 'Ravi Shah',
        status: 'ACTIVE',
        accountEnabled: true,
      },
      { ...inactiveManager, id: 31 },
      {
        ...inactiveManager,
        id: 33,
        employeeCode: 'MGR-033',
        fullName: 'Disabled Manager',
        status: 'ACTIVE',
        accountEnabled: false,
      },
    ])
    renderApp('/hr/employees/21')

    const currentManager = (await screen.findByText('Current reporting manager')).parentElement
    expect(within(currentManager).getByText('Mona Boss')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    const select = await screen.findByLabelText('New reporting manager')
    await waitFor(() => expect(managerApi.listManagers).toHaveBeenCalledWith('ACTIVE'))

    expect(within(select).getByRole('option', { name: 'Ravi Shah (MGR-032)' })).toBeTruthy()
    expect(within(select).getByRole('option', { name: /Mona Boss \(inactive or unavailable\)/ }))
      .toBeTruthy()
    expect(within(select).queryByRole('option', { name: /Disabled Manager/ })).toBeNull()
    expect(within(select).queryByRole('option', { name: /Remove|No manager/ })).toBeNull()
  })

  it('reassigns the reporting manager through the dedicated endpoint and refreshes details', async () => {
    const user = userEvent.setup()
    const updated = { ...employee, managerId: 32, managerName: 'Ravi Shah' }
    employeeApi.getEmployee
      .mockResolvedValueOnce(employee)
      .mockResolvedValueOnce(updated)
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    await user.selectOptions(await screen.findByLabelText('New reporting manager'), '32')
    await user.click(screen.getByRole('button', { name: 'Save reporting manager' }))
    expect(dialogText()).toContain(
      "Change Asha Rao's reporting manager from Mona Boss to Ravi Shah?",
    )
    await confirmDialog(user, 'Save manager')

    await waitFor(() => expect(employeeApi.assignEmployeeManager).toHaveBeenCalledOnce())
    expect(employeeApi.assignEmployeeManager).toHaveBeenCalledWith('21', 32)
    expect(await screen.findByText('Reporting manager for Asha Rao updated to Ravi Shah.'))
      .toBeTruthy()
    await waitFor(() => expect(employeeApi.getEmployee).toHaveBeenCalledTimes(2))
    expect(screen.getByText('Ravi Shah')).toBeTruthy()
    expect(employeeApi.updateEmployee).not.toHaveBeenCalled()
  })

  it('does not offer removal because the backend requires a manager id', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    const select = await screen.findByLabelText('New reporting manager')

    expect(screen.queryByRole('button', { name: /remove manager/i })).toBeNull()
    expect(within(select).queryByRole('option', { name: /no manager|remove/i })).toBeNull()
    expect(within(select).getAllByRole('option')[0].value).toBe('')
  })

  it('disables manager reassignment for an inactive employee', async () => {
    employeeApi.getEmployee.mockResolvedValue({
      ...employee,
      status: 'INACTIVE',
      accountEnabled: false,
    })
    renderApp('/hr/employees/21')

    expect(await screen.findByText('Current reporting manager')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Change manager' }).disabled).toBe(true)
    expect(screen.getByText(/Only an active employee can be assigned a reporting manager/))
      .toBeTruthy()
    expect(managerApi.listManagers).not.toHaveBeenCalled()
  })

  it('shows backend validation errors and preserves the selected manager', async () => {
    const user = userEvent.setup()
    employeeApi.assignEmployeeManager.mockRejectedValueOnce({
      response: {
        status: 400,
        data: { message: 'The selected account is not a manager.' },
      },
    })
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    await user.selectOptions(await screen.findByLabelText('New reporting manager'), '32')
    await user.click(screen.getByRole('button', { name: 'Save reporting manager' }))
    await confirmDialog(user, 'Save manager')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The selected account is not a manager.')
    expect(screen.getByLabelText('New reporting manager').value).toBe('32')
    expect(screen.queryByText(/updated to Ravi Shah/)).toBeNull()
    expect(employeeApi.assignEmployeeManager).toHaveBeenCalledOnce()
  })

  it('shows a manager deactivation conflict without losing the selection', async () => {
    const user = userEvent.setup()
    employeeApi.assignEmployeeManager.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { message: 'The selected manager is inactive.' },
      },
    })
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    await user.selectOptions(await screen.findByLabelText('New reporting manager'), '32')
    await user.click(screen.getByRole('button', { name: 'Save reporting manager' }))
    await confirmDialog(user, 'Save manager')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('The selected manager is inactive.')
    expect(screen.getByLabelText('New reporting manager').value).toBe('32')
    expect(screen.queryByText(/updated to Ravi Shah/)).toBeNull()
  })

  it('reports network failures during reassignment and keeps the form open', async () => {
    const user = userEvent.setup()
    employeeApi.assignEmployeeManager.mockRejectedValueOnce(new Error('network down'))
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    await user.selectOptions(await screen.findByLabelText('New reporting manager'), '32')
    await user.click(screen.getByRole('button', { name: 'Save reporting manager' }))
    await confirmDialog(user, 'Save manager')

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Unable to reach Evalora. Check your connection')
    expect(screen.getByLabelText('New reporting manager').value).toBe('32')
    expect(employeeApi.getEmployee).toHaveBeenCalledOnce()
  })

  it('prevents duplicate reassignment requests while the first is pending', async () => {
    const user = userEvent.setup()
    let resolveAssignment
    employeeApi.assignEmployeeManager.mockReturnValueOnce(new Promise((resolve) => {
      resolveAssignment = resolve
    }))
    renderApp('/hr/employees/21')

    await screen.findByText('Current reporting manager')
    await user.click(screen.getByRole('button', { name: 'Change manager' }))
    await user.selectOptions(await screen.findByLabelText('New reporting manager'), '32')
    const saveButton = screen.getByRole('button', { name: 'Save reporting manager' })
    await user.click(saveButton)
    await confirmDialog(user, 'Save manager')
    await user.click(saveButton)

    expect(employeeApi.assignEmployeeManager).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: 'Saving…' }).disabled).toBe(true)
    resolveAssignment({ ...employee, managerId: 32, managerName: 'Ravi Shah' })
    expect(await screen.findByText('Reporting manager for Asha Rao updated to Ravi Shah.'))
      .toBeTruthy()
  })

  it.each(['MANAGER', 'EMPLOYEE'])(
    '%s cannot access employee manager reassignment',
    async (role) => {
      renderApp('/hr/employees/21', role)

      expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
      expect(employeeApi.getEmployee).not.toHaveBeenCalled()
      expect(managerApi.listManagers).not.toHaveBeenCalled()
    },
  )

  it('renders manager details and status', async () => {
    renderApp('/hr/managers/31')

    expect(await screen.findByText('MGR-031')).toBeTruthy()
    expect(screen.getByText('INACTIVE')).toBeTruthy()
    expect(screen.getByText('No')).toBeTruthy()
    expect(managerApi.getManager).toHaveBeenCalledWith('31')
  })

  it('validates the employee email before submitting', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees/new')

    await user.type(await screen.findByLabelText('First name'), 'Asha')
    await user.type(screen.getByLabelText('Last name'), 'Rao')
    await user.type(screen.getByLabelText('Email'), 'invalid')
    await user.type(screen.getByLabelText('Employee code'), 'EMP-022')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    expect((await screen.findByRole('alert')).textContent).toContain('valid email address')
    expect(employeeApi.createEmployee).not.toHaveBeenCalled()
  })

  it('requires a selected reporting manager before employee submission', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees/new')

    await user.type(await screen.findByLabelText('First name'), 'Asha')
    await user.type(screen.getByLabelText('Last name'), 'Rao')
    await user.type(screen.getByLabelText('Email'), 'asha@example.test')
    await user.type(screen.getByLabelText('Employee code'), 'EMP-022')
    const managerSelect = screen.getByLabelText(/Reporting manager/)
    expect(managerSelect.required).toBe(true)
    expect(managerSelect.value).toBe('')

    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    expect((await screen.findByRole('alert')).textContent)
      .toContain('Select a reporting manager before creating this employee.')
    expect(employeeApi.createEmployee).not.toHaveBeenCalled()
  })

  it('shows manager loading state and keeps employee creation disabled until the list arrives', async () => {
    let resolveManagers
    managerApi.listManagers.mockReturnValueOnce(new Promise((resolve) => {
      resolveManagers = resolve
    }))
    renderApp('/hr/employees/new')

    expect(await screen.findByText('Loading eligible managers…')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create employee' }).disabled).toBe(true)

    resolveManagers([{
      ...inactiveManager,
      id: 32,
      employeeCode: 'MGR-032',
      fullName: 'Ravi Shah',
      role: 'MANAGER',
      status: 'ACTIVE',
      accountEnabled: true,
    }])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create employee' }).disabled).toBe(false))
    expect(screen.getByRole('option', { name: 'Ravi Shah (MGR-032)' })).toBeTruthy()
  })

  it('disables employee creation with no eligible managers and explains how to proceed', async () => {
    managerApi.listManagers.mockResolvedValueOnce([])
    renderApp('/hr/employees/new')

    expect(await screen.findByText(/No active managers are available/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Create or activate a manager first.' }).getAttribute('href'))
      .toBe('/hr/managers')
    expect(screen.getByRole('button', { name: 'Create employee' }).disabled).toBe(true)
    expect(employeeApi.createEmployee).not.toHaveBeenCalled()
  })

  it('shows manager-list errors, offers retry, and keeps submission disabled until retry succeeds', async () => {
    const user = userEvent.setup()
    managerApi.listManagers
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce([{
        ...inactiveManager,
        id: 32,
        employeeCode: 'MGR-032',
        fullName: 'Ravi Shah',
        role: 'MANAGER',
        status: 'ACTIVE',
        accountEnabled: true,
      }])
    renderApp('/hr/employees/new')

    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create employee' }).disabled).toBe(true)
    await user.click(screen.getByRole('button', { name: 'retry' }))

    expect(await screen.findByRole('option', { name: 'Ravi Shah (MGR-032)' })).toBeTruthy()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create employee' }).disabled).toBe(false))
    expect(managerApi.listManagers).toHaveBeenCalledTimes(2)
  })

  it('creates an employee with the accepted DTO and an active manager option', async () => {
    const user = userEvent.setup()
    managerApi.listManagers.mockResolvedValueOnce([
      { ...inactiveManager, id: 32, employeeCode: 'MGR-032', fullName: 'Ravi Shah', status: 'ACTIVE', accountEnabled: true },
      { ...inactiveManager, id: 32, employeeCode: 'MGR-032', fullName: 'Ravi Shah', status: 'ACTIVE', accountEnabled: true },
      { ...inactiveManager, id: 31, status: 'INACTIVE', accountEnabled: false },
      { ...inactiveManager, id: 33, fullName: 'Disabled Manager', status: 'ACTIVE', accountEnabled: false },
      { ...inactiveManager, id: 34, fullName: 'Employee Option', role: 'EMPLOYEE', status: 'ACTIVE', accountEnabled: true },
    ])
    renderApp('/hr/employees/new')

    await user.type(await screen.findByLabelText('First name'), 'Asha')
    await user.type(screen.getByLabelText('Last name'), 'Rao')
    await user.type(screen.getByLabelText('Email'), 'asha@example.test')
    await user.type(screen.getByLabelText('Employee code'), 'EMP-022')
    expect(screen.queryByLabelText(/Initial password/)).toBeNull()
    const managerSelect = screen.getByLabelText(/Reporting manager/)
    expect(within(managerSelect).getAllByRole('option')).toHaveLength(2)
    expect(within(managerSelect).queryByRole('option', { name: /Disabled Manager|Employee Option|Mona Boss/ })).toBeNull()
    await user.selectOptions(managerSelect, '32')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    await waitFor(() => expect(employeeApi.createEmployee).toHaveBeenCalledOnce())
    expect(employeeApi.createEmployee).toHaveBeenCalledWith({
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.test',
      employeeCode: 'EMP-022',
      department: null,
      designation: null,
      joiningDate: null,
      managerId: 32,
    })
    expect(employeeApi.assignEmployeeManager).not.toHaveBeenCalled()
  })

  it('prevents duplicate employee creation while the first request is pending', async () => {
    const user = userEvent.setup()
    let resolveCreate
    employeeApi.createEmployee.mockReturnValueOnce(new Promise((resolve) => {
      resolveCreate = resolve
    }))
    renderApp('/hr/employees/new')

    await user.type(await screen.findByLabelText('First name'), 'Asha')
    await user.type(screen.getByLabelText('Last name'), 'Rao')
    await user.type(screen.getByLabelText('Email'), 'asha@example.test')
    await user.type(screen.getByLabelText('Employee code'), 'EMP-022')
    await user.selectOptions(screen.getByLabelText(/Reporting manager/), '32')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    const savingButton = await screen.findByRole('button', { name: 'Saving…' })
    expect(savingButton.disabled).toBe(true)
    await user.click(savingButton)
    expect(employeeApi.createEmployee).toHaveBeenCalledOnce()

    resolveCreate(employee)
    expect(await screen.findByText('Asha Rao')).toBeTruthy()
  })

  it('keeps the employee form intact after a failed create without follow-up assignment', async () => {
    const user = userEvent.setup()
    employeeApi.createEmployee.mockRejectedValue({
      response: { status: 409, data: { message: 'An employee with this code already exists.' } },
    })
    renderApp('/hr/employees/new')

    await user.type(await screen.findByLabelText('First name'), 'Asha')
    await user.type(screen.getByLabelText('Last name'), 'Rao')
    await user.type(screen.getByLabelText('Email'), 'asha@example.test')
    await user.type(screen.getByLabelText('Employee code'), 'EMP-021')
    await user.selectOptions(screen.getByLabelText(/Reporting manager/), '32')
    await user.click(screen.getByRole('button', { name: 'Create employee' }))

    expect(await screen.findByText('An employee with this code already exists.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Add Employee' })).toBeTruthy()
    expect(screen.getByLabelText('Employee code').value).toBe('EMP-021')
    expect(screen.getByLabelText(/Reporting manager/).value).toBe('32')
    expect(employeeApi.createEmployee).toHaveBeenCalledOnce()
    expect(employeeApi.assignEmployeeManager).not.toHaveBeenCalled()
  })

  it('validates manager required fields and never offers another manager assignment', async () => {
    const user = userEvent.setup()
    renderApp('/hr/managers/new')

    expect(await screen.findByLabelText('First name')).toBeTruthy()
    expect(screen.queryByLabelText(/Reporting manager/)).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Create manager' }))

    expect((await screen.findByRole('alert')).textContent).toContain('first and last name')
    expect(managerApi.createManager).not.toHaveBeenCalled()
  })

  it('creates a manager without sending a reporting-manager field', async () => {
    const user = userEvent.setup()
    renderApp('/hr/managers/new')

    await user.type(await screen.findByLabelText('First name'), 'Mona')
    await user.type(screen.getByLabelText('Last name'), 'Boss')
    await user.type(screen.getByLabelText('Email'), 'mona@example.test')
    await user.type(screen.getByLabelText('Employee code'), 'MGR-031')
    await user.click(screen.getByRole('button', { name: 'Create manager' }))

    await waitFor(() => expect(managerApi.createManager).toHaveBeenCalledOnce())
    expect(managerApi.createManager).toHaveBeenCalledWith({
      firstName: 'Mona',
      lastName: 'Boss',
      email: 'mona@example.test',
      employeeCode: 'MGR-031',
      department: null,
      designation: null,
      joiningDate: null,
    })
  })

  it('prepopulates employee edit fields and submits only the update DTO', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees/21/edit')

    expect((await screen.findByLabelText('First name')).value).toBe('Asha')
    expect(screen.getByLabelText('Last name').value).toBe('Rao')
    expect(screen.getByLabelText('Email').value).toBe('asha@example.test')
    await user.clear(screen.getByLabelText('Department'))
    await user.type(screen.getByLabelText('Department'), 'Product')
    await user.click(screen.getByLabelText('I verified the first and last name split.'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(employeeApi.updateEmployee).toHaveBeenCalledOnce())
    expect(employeeApi.updateEmployee).toHaveBeenCalledWith('21', {
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.test',
      employeeCode: 'EMP-021',
      department: 'Product',
      designation: 'Developer',
      joiningDate: '2026-01-05',
    })
  })

  it.each([
    ['employee', '/hr/employees/21/edit', 'employee', employeeApi.getEmployee, employeeApi.updateEmployee],
    ['manager', '/hr/managers/31/edit', 'manager', managerApi.getManager, managerApi.updateManager],
  ])('requires verifying the split and preserves a compound %s full name', async (
    _role,
    path,
    resource,
    getStaff,
    updateStaff,
  ) => {
    const user = userEvent.setup()
    const profile = {
      ...(resource === 'employee' ? employee : inactiveManager),
      fullName: 'Mary Jane Watson',
    }
    getStaff.mockResolvedValue(profile)
    renderApp(path)

    expect((await screen.findByLabelText('First name')).value).toBe('Mary')
    expect(screen.getByLabelText('Last name').value).toBe('Jane Watson')
    expect(screen.getAllByText('Mary Jane Watson').length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect((await screen.findByRole('alert')).textContent)
      .toContain('Verify or correct the first and last name split')
    expect(updateStaff).not.toHaveBeenCalled()

    await user.clear(screen.getByLabelText('First name'))
    await user.type(screen.getByLabelText('First name'), 'Mary Jane')
    await user.clear(screen.getByLabelText('Last name'))
    await user.type(screen.getByLabelText('Last name'), 'Watson')
    expect(screen.getAllByText('Mary Jane Watson').length).toBeGreaterThan(0)
    await user.click(screen.getByLabelText('I verified the first and last name split.'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(updateStaff).toHaveBeenCalledOnce())
    const payload = updateStaff.mock.calls[0][1]
    expect(payload.firstName).toBe('Mary Jane')
    expect(payload.lastName).toBe('Watson')
    expect(`${payload.firstName} ${payload.lastName}`).toBe(profile.fullName)
  })

  it.each([
    ['/hr/employees/21/edit', 'employee', employeeApi.getEmployee, employeeApi.updateEmployee],
    ['/hr/managers/31/edit', 'manager', managerApi.getManager, managerApi.updateManager],
  ])('requires explicit name verification on an unchanged %s profile submission', async (
    path,
    role,
    getStaff,
    updateStaff,
  ) => {
    const user = userEvent.setup()
    renderApp(path)

    await screen.findByLabelText('First name')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(updateStaff).not.toHaveBeenCalled()
    expect((await screen.findByRole('alert')).textContent).toContain('Verify or correct')

    await user.click(screen.getByLabelText('I verified the first and last name split.'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(updateStaff).toHaveBeenCalledOnce())

    const original = role === 'employee' ? employee : inactiveManager
    const payload = updateStaff.mock.calls[0][1]
    expect(`${payload.firstName} ${payload.lastName}`).toBe(original.fullName)
    expect(payload).not.toHaveProperty('role')
    expect(payload).not.toHaveProperty('status')
    expect(getStaff).toHaveBeenCalledWith(role === 'employee' ? '21' : '31')
  })

  it('prepopulates manager edit fields without enabling role or status changes', async () => {
    const user = userEvent.setup()
    renderApp('/hr/managers/31/edit')

    expect((await screen.findByLabelText('First name')).value).toBe('Mona')
    expect(screen.getByLabelText('Last name').value).toBe('Boss')
    expect(screen.queryByLabelText(/Reporting manager/)).toBeNull()
    expect(screen.queryByLabelText('Account status')).toBeNull()
    await user.clear(screen.getByLabelText('Designation'))
    await user.type(screen.getByLabelText('Designation'), 'People Manager')
    await user.click(screen.getByLabelText('I verified the first and last name split.'))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(managerApi.updateManager).toHaveBeenCalledOnce())
    expect(managerApi.updateManager).toHaveBeenCalledWith('31', {
      firstName: 'Mona',
      lastName: 'Boss',
      email: 'mona@example.test',
      employeeCode: 'MGR-031',
      department: 'Operations',
      designation: 'People Manager',
      joiningDate: null,
    })
  })

  it('requires confirmation before deactivation and then refreshes the list', async () => {
    const user = userEvent.setup()
    renderApp('/hr/employees')
    await screen.findByText('Asha Rao')
    await user.click(screen.getByRole('button', { name: 'Deactivate' }))
    expect(dialogText()).toContain('account will become inactive')
    await cancelDialog(user)
    expect(employeeApi.deactivateEmployee).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Deactivate' }))
    await confirmDialog(user, 'Deactivate')
    await waitFor(() => expect(employeeApi.deactivateEmployee).toHaveBeenCalledWith(21))
    await waitFor(() => expect(employeeApi.listEmployees).toHaveBeenCalledTimes(2))
  })

  it('requires confirmation before reactivation', async () => {
    managerApi.listManagers
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([inactiveManager])
    renderApp('/hr/managers')
    await screen.findByText('No active managers found.')
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Account status'), 'INACTIVE')
    await screen.findByText('Mona Boss')

    await user.click(screen.getByRole('button', { name: 'Reactivate' }))

    expect(dialogText()).toContain('account will become active again')
    await confirmDialog(user, 'Reactivate')
    await waitFor(() => expect(managerApi.reactivateManager).toHaveBeenCalledWith(31))
  })

  it('shows backend conflict messages from a rejected manager deactivation', async () => {
    const user = userEvent.setup()
    managerApi.deactivateManager.mockRejectedValue({
      response: {
        status: 409,
        data: { message: 'Reassign active employees before deactivating this manager.' },
      },
    })
    managerApi.getManager.mockResolvedValue({ ...inactiveManager, status: 'ACTIVE' })
    renderApp('/hr/managers/31')
    await screen.findByText('MGR-031')
    await user.click(screen.getByRole('button', { name: 'Deactivate account' }))
    await confirmDialog(user, 'Deactivate')

    expect(await screen.findByText('Reassign active employees before deactivating this manager.')).toBeTruthy()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('staff-test.jwt')
  })

  it('renders empty and loading states', async () => {
    employeeApi.listEmployees.mockResolvedValueOnce([])
    renderApp('/hr/employees')

    expect(await screen.findByText('No active employees found.')).toBeTruthy()

    employeeApi.listEmployees.mockReturnValueOnce(new Promise(() => {}))
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('Account status'), 'INACTIVE')
    expect(await screen.findByRole('status')).toBeTruthy()
  })
})
