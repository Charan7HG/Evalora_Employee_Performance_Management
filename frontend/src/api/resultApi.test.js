import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  getEmployeeResult,
  getEmployeeResults,
  getHrResult,
  getHrResultQueue,
  getManagerResult,
  getManagerResults,
  publishHrCycleResults,
  publishHrResult,
} from './resultApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(response) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: response, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('result API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('gets the HR eligibility queue without sending an HR identity', async () => {
    const queue = [{ resultId: 51, status: 'DRAFT' }]
    const getRequest = captureRequest(queue)

    await expect(getHrResultQueue()).resolves.toEqual(queue)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/results')
  })

  it('gets an HR result including before publication', async () => {
    const result = { resultId: 51, status: 'DRAFT', items: [] }
    const getRequest = captureRequest(result)

    await expect(getHrResult(51)).resolves.toEqual(result)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/results/51')
  })

  it('publishes one result with no request body', async () => {
    const response = { resultId: 51, status: 'PUBLISHED', publishedAt: '2026-11-03T09:00:00Z' }
    const getRequest = captureRequest(response)

    await expect(publishHrResult(51)).resolves.toEqual(response)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/results/51/publish')
    expect(getRequest().data).toBeUndefined()
  })

  it('publishes all eligible cycle results with no request body', async () => {
    const response = { cycleId: 8, publishedCount: 2, skippedCount: 1, publishedResultIds: [51, 52] }
    const getRequest = captureRequest(response)

    await expect(publishHrCycleResults(8)).resolves.toEqual(response)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/results/cycles/8/publish')
    expect(getRequest().data).toBeUndefined()
  })

  it('gets the authenticated employee published results and one detail', async () => {
    const results = [{ resultId: 61, status: 'PUBLISHED' }]
    let getRequest = captureRequest(results)

    await expect(getEmployeeResults()).resolves.toEqual(results)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/employee/results')

    const detail = { resultId: 61, status: 'PUBLISHED', items: [] }
    getRequest = captureRequest(detail)
    await expect(getEmployeeResult(61)).resolves.toEqual(detail)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/employee/results/61')
  })

  it('gets the authenticated manager team results and one detail', async () => {
    const results = [{ resultId: 71, status: 'PUBLISHED' }]
    let getRequest = captureRequest(results)

    await expect(getManagerResults()).resolves.toEqual(results)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/manager/results')

    const detail = { resultId: 71, status: 'PUBLISHED', items: [] }
    getRequest = captureRequest(detail)
    await expect(getManagerResult(71)).resolves.toEqual(detail)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/manager/results/71')
  })

  it('propagates publication conflicts to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(publishHrResult(51)).rejects.toBe(requestError)
  })
})
