import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  getEmployeeDashboard,
  getHrDashboard,
  getManagerDashboard,
} from './dashboardApi.js'
import { TOKEN_STORAGE_KEY } from './tokenStorage.js'

const initialAdapter = apiClient.defaults.adapter

function setAdapter(handler) {
  apiClient.defaults.adapter = async (config) => ({
    data: await handler(config),
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  })
}

describe('dashboard API', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    apiClient.defaults.adapter = initialAdapter
  })

  it.each([
    ['HR', getHrDashboard, '/hr/dashboard'],
    ['manager', getManagerDashboard, '/manager/dashboard'],
    ['employee', getEmployeeDashboard, '/employee/dashboard'],
  ])('requests the %s dashboard endpoint', async (_role, getDashboard, endpoint) => {
    let request
    setAdapter((config) => {
      request = config
      return { ok: true }
    })

    await expect(getDashboard()).resolves.toEqual({ ok: true })

    expect(request.method).toBe('get')
    expect(request.url).toBe(endpoint)
  })

  it('uses the existing bearer-token interceptor', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'dashboard.jwt')
    let request
    setAdapter((config) => {
      request = config
      return {}
    })

    await getHrDashboard()

    expect(request.headers.get('Authorization')).toMatch(/^Bearer /)
  })
})
