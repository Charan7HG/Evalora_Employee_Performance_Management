import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  finalizeHrAssessment,
  getHrAssessment,
  getHrAssessmentQueue,
  getHrAssessmentStatus,
  saveHrAssessmentDraft,
} from './hrAssessmentApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(response) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: response, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('HR assessment API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('gets the authenticated HR queue without sending an HR identity', async () => {
    const queue = [{ assignmentId: 31, employeeName: 'Eve Employee' }]
    const getRequest = captureRequest(queue)

    await expect(getHrAssessmentQueue()).resolves.toEqual(queue)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/assessments')
  })

  it('gets assessment detail and status', async () => {
    const detail = { assignmentId: 31, editable: true, kpis: [] }
    let getRequest = captureRequest(detail)

    await expect(getHrAssessment(31)).resolves.toEqual(detail)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/assessments/31')

    const status = { assignmentId: 31, submitted: false }
    getRequest = captureRequest(status)
    await expect(getHrAssessmentStatus(31)).resolves.toEqual(status)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/hr/assessments/31/status')
  })

  it('saves incomplete drafts using the backend KPI entry shape', async () => {
    const response = { assignmentId: 31, editable: true, kpis: [] }
    const entries = [
      { employeeKpiId: 100, rating: 4, comment: 'Strong delivery' },
      { employeeKpiId: 101, rating: null, comment: '' },
    ]
    const getRequest = captureRequest(response)

    await expect(saveHrAssessmentDraft(31, entries)).resolves.toEqual(response)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/hr/assessments/31/draft')
    expect(JSON.parse(getRequest().data)).toEqual({ kpis: entries })
  })

  it('finalizes separately without a request body', async () => {
    const response = { assignmentId: 31, assignmentStatus: 'COMPLETED', submitted: true }
    const getRequest = captureRequest(response)

    await expect(finalizeHrAssessment(31)).resolves.toEqual(response)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/hr/assessments/31/submit')
    expect(getRequest().data).toBeUndefined()
  })

  it('propagates backend errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(finalizeHrAssessment(31)).rejects.toBe(requestError)
  })
})
