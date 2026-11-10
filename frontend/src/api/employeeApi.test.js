import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  assignEmployeeManager,
  createEmployee,
  deactivateEmployee,
  getEmployee,
  listEmployees,
  reactivateEmployee,
  updateEmployee,
} from './employeeApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(result = { id: 21 }) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: result, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('employee API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('lists employees with the backend status query parameter', async () => {
    const getRequest = captureRequest([])
    await listEmployees('INACTIVE')

    expect(getRequest().url).toBe('/hr/employees')
    expect(getRequest().params).toEqual({ status: 'INACTIVE' })
  })

  it('posts the exact create DTO', async () => {
    const payload = {
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.test',
      employeeCode: 'EMP-001',
      department: 'Engineering',
      designation: 'Developer',
      joiningDate: '2026-01-05',
      managerId: 7,
    }
    const getRequest = captureRequest()
    await createEmployee(payload)

    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/employees')
    expect(JSON.parse(getRequest().data)).toEqual(payload)
  })

  it('omits managerId from employee creation when no manager is selected', async () => {
    const payload = {
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.test',
      employeeCode: 'EMP-002',
      department: null,
      designation: null,
      joiningDate: null,
    }
    const getRequest = captureRequest()
    await createEmployee(payload)

    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/employees')
    expect(JSON.parse(getRequest().data)).toEqual(payload)
    expect(JSON.parse(getRequest().data)).not.toHaveProperty('managerId')
  })

  it('gets and updates an employee using its profile ID and exact update fields', async () => {
    const getRequest = captureRequest()
    await getEmployee(21)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/employees/21')

    const updatePayload = {
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.test',
      employeeCode: 'EMP-001',
      department: 'Product',
      designation: 'Engineer',
      joiningDate: null,
    }
    await updateEmployee(21, updatePayload)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/hr/employees/21')
    expect(JSON.parse(getRequest().data)).toEqual(updatePayload)
  })

  it('uses the dedicated manager assignment endpoint and status endpoints', async () => {
    const getRequest = captureRequest()
    await assignEmployeeManager(21, 7)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/hr/employees/21/manager')
    expect(JSON.parse(getRequest().data)).toEqual({ managerId: 7 })

    await deactivateEmployee(21)
    expect(getRequest().method).toBe('patch')
    expect(getRequest().url).toBe('/hr/employees/21/deactivate')

    await reactivateEmployee(21)
    expect(getRequest().method).toBe('patch')
    expect(getRequest().url).toBe('/hr/employees/21/reactivate')
  })
})
