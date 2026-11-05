import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  createManager,
  deactivateManager,
  getManager,
  listManagers,
  reactivateManager,
  updateManager,
} from './managerApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(result = { id: 31 }) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: result, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('manager API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('lists managers with the backend status query parameter', async () => {
    const getRequest = captureRequest([])
    await listManagers('INACTIVE')

    expect(getRequest().url).toBe('/hr/managers')
    expect(getRequest().params).toEqual({ status: 'INACTIVE' })
  })

  it('posts the exact manager creation DTO without a manager assignment', async () => {
    const payload = {
      firstName: 'Mona',
      lastName: 'Boss',
      email: 'mona@example.test',
      employeeCode: 'MGR-001',
      department: null,
      designation: 'Lead',
      joiningDate: '2026-02-01',
      initialPassword: 'Manager@2026',
    }
    const getRequest = captureRequest()
    await createManager(payload)

    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/managers')
    expect(JSON.parse(getRequest().data)).toEqual(payload)
  })

  it('gets and updates a manager using its profile ID and exact update fields', async () => {
    const getRequest = captureRequest()
    await getManager(31)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/managers/31')

    const updatePayload = {
      firstName: 'Mona',
      lastName: 'Boss',
      email: 'mona@example.test',
      employeeCode: 'MGR-001',
      department: 'Operations',
      designation: 'Manager',
      joiningDate: null,
    }
    await updateManager(31, updatePayload)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/hr/managers/31')
    expect(JSON.parse(getRequest().data)).toEqual(updatePayload)
  })

  it('calls the manager deactivation and reactivation endpoints', async () => {
    const getRequest = captureRequest()
    await deactivateManager(31)
    expect(getRequest().method).toBe('patch')
    expect(getRequest().url).toBe('/hr/managers/31/deactivate')

    await reactivateManager(31)
    expect(getRequest().method).toBe('patch')
    expect(getRequest().url).toBe('/hr/managers/31/reactivate')
  })
})
