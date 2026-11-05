import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  createCycle,
  configureCycleKpis,
  configureCycleEmployees,
  getCycleEmployees,
  getCycleKpis,
  getCycleById,
  getCycles,
  publishCycle,
  updateCycle,
} from './cycleApi.js'

const initialAdapter = apiClient.defaults.adapter
const cycle = {
  id: 8,
  name: 'Annual 2026',
  status: 'DRAFT',
}

function captureRequest(result = cycle) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: result, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('cycle API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('lists cycles and returns the response body', async () => {
    const getRequest = captureRequest([cycle])

    await expect(getCycles()).resolves.toEqual([cycle])
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/cycles')
  })

  it('gets a cycle by ID', async () => {
    const getRequest = captureRequest()

    await expect(getCycleById(8)).resolves.toEqual(cycle)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/cycles/8')
  })

  it('gets assigned cycle KPIs', async () => {
    const assigned = [{ id: 2, kpiId: 8, name: 'Communication', weightage: 100 }]
    const getRequest = captureRequest(assigned)

    await expect(getCycleKpis(8)).resolves.toEqual(assigned)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/cycles/8/kpis')
  })

  it.each([
    ['weighted', [{ kpiId: 8, weightage: 100 }]],
    ['unweighted', [{ kpiId: 8, weightage: null }]],
  ])('replaces cycle KPIs with %s request data', async (_mode, selections) => {
    const saved = [{ id: 2, kpiId: 8, name: 'Communication', weightage: selections[0].weightage }]
    const getRequest = captureRequest(saved)

    await expect(configureCycleKpis(8, selections)).resolves.toEqual(saved)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/cycles/8/kpis')
    expect(JSON.parse(getRequest().data)).toEqual({ kpis: selections })
  })

  it('gets assigned cycle employees', async () => {
    const assigned = [{
      id: 15,
      employeeId: 31,
      employeeCode: 'EMP-031',
      employeeName: 'Erin Employee',
      managerId: 7,
      managerName: 'Mona Manager',
      status: 'SELF_ASSESSMENT_PENDING',
    }]
    const getRequest = captureRequest(assigned)

    await expect(getCycleEmployees(8)).resolves.toEqual(assigned)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/cycles/8/employees')
  })

  it('publishes a cycle using the cycle publish endpoint and returns its response body', async () => {
    const published = { ...cycle, status: 'PUBLISHED', publishedAt: '2026-11-02T10:00:00Z' }
    const getRequest = captureRequest(published)

    await expect(publishCycle(8)).resolves.toEqual(published)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/cycles/8/publish')
    expect(getRequest().data).toBeUndefined()
  })

  it('replaces the full cycle employee roster and sends manager IDs only when supplied', async () => {
    const employees = [
      { employeeId: 31, managerId: 7 },
      { employeeId: 32 },
    ]
    const saved = [{ id: 15, employeeId: 31, employeeName: 'Erin Employee' }]
    const getRequest = captureRequest(saved)

    await expect(configureCycleEmployees(8, employees)).resolves.toEqual(saved)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/hr/cycles/8/employees')
    expect(JSON.parse(getRequest().data)).toEqual({ employees })
  })

  it('propagates employee assignment errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(configureCycleEmployees(8, [])).rejects.toBe(requestError)
  })

  it.each([
    ['create', createCycle, 'post', '/hr/cycles'],
    ['update', (id, payload) => updateCycle(id, payload), 'put', '/hr/cycles/8'],
  ])('sends the cycle DTO when it %s a cycle', async (_operation, requestCycle, method, url) => {
    const payload = {
      name: 'Annual 2026',
      description: 'Annual performance review.',
      startDate: '2026-01-01',
      endDate: '2026-12-31',
      selfAssessmentDeadline: '2026-11-30',
      managerReviewDeadline: '2026-12-15',
      hrReviewDeadline: '2026-12-31',
    }
    const getRequest = captureRequest()

    if (method === 'post') {
      await expect(requestCycle(payload)).resolves.toEqual(cycle)
    } else {
      await expect(requestCycle(8, payload)).resolves.toEqual(cycle)
    }

    expect(getRequest().method).toBe(method)
    expect(getRequest().url).toBe(url)
    expect(JSON.parse(getRequest().data)).toEqual(payload)
  })

  it('propagates backend errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(getCycles()).rejects.toBe(requestError)
  })

  it('propagates publish errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(publishCycle(8)).rejects.toBe(requestError)
  })

  it('propagates cycle KPI request errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(configureCycleKpis(8, [{ kpiId: 8, weightage: null }]))
      .rejects.toBe(requestError)
  })
})
