import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  createKpi,
  deactivateKpi,
  getKpiById,
  getKpis,
  reactivateKpi,
  updateKpi,
} from './kpiApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(result = { id: 8 }) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: result, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('KPI API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('lists every KPI without a filter parameter', async () => {
    const getRequest = captureRequest([])
    await getKpis()

    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/kpis')
    expect(getRequest().params).toBeUndefined()
  })

  it.each([
    [true, { active: true }],
    [false, { active: false }],
  ])('passes active=%s as the list query parameter', async (active, expectedParams) => {
    const getRequest = captureRequest([])
    await getKpis(active)

    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/kpis')
    expect(getRequest().params).toEqual(expectedParams)
  })

  it('gets KPI details by ID', async () => {
    const getRequest = captureRequest()
    await getKpiById(8)

    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/kpis/8')
  })

  it.each([
    ['create', createKpi, 'post', '/hr/kpis'],
    ['update', (id, payload) => updateKpi(id, payload), 'put', '/hr/kpis/8'],
  ])('sends the accepted fields when it %s a KPI', async (_operation, requestKpi, method, url) => {
    const payload = {
      name: 'Customer focus',
      description: 'Consistently understand customer needs.',
      category: 'Service',
      defaultWeightage: 12.5,
    }
    const getRequest = captureRequest()
    if (method === 'post') {
      await requestKpi(payload)
    } else {
      await requestKpi(8, payload)
    }

    expect(getRequest().method).toBe(method)
    expect(getRequest().url).toBe(url)
    expect(JSON.parse(getRequest().data)).toEqual(payload)
  })

  it.each([
    ['deactivate', deactivateKpi, '/hr/kpis/8/deactivate'],
    ['reactivate', reactivateKpi, '/hr/kpis/8/reactivate'],
  ])('uses the status endpoint to %s a KPI', async (_action, changeStatus, url) => {
    const getRequest = captureRequest()
    await changeStatus(8)

    expect(getRequest().method).toBe('patch')
    expect(getRequest().url).toBe(url)
    expect(getRequest().data).toBeUndefined()
  })

  it('propagates API errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(getKpis()).rejects.toBe(requestError)
  })
})
