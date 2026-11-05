import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  getSelfAssessment,
  getSelfAssessmentCycles,
  getSelfAssessmentStatus,
  saveSelfAssessmentDraft,
  submitSelfAssessment,
} from './selfAssessmentApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(response) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: response, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('self-assessment API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('lists the authenticated employee assignments', async () => {
    const assignments = [{ assignmentId: 21, cycleName: 'Annual 2026' }]
    const getRequest = captureRequest(assignments)

    await expect(getSelfAssessmentCycles()).resolves.toEqual(assignments)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/employee/self-assessments/cycles')
  })

  it('gets assignment details and status', async () => {
    const detail = { assignmentId: 21, editable: true, kpis: [] }
    let getRequest = captureRequest(detail)

    await expect(getSelfAssessment(21)).resolves.toEqual(detail)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/employee/self-assessments/21')

    const status = { assignmentId: 21, submitted: false, ratedKpis: 0 }
    getRequest = captureRequest(status)
    await expect(getSelfAssessmentStatus(21)).resolves.toEqual(status)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/employee/self-assessments/21/status')
  })

  it('saves draft entries with the backend request fields, including nullable ratings', async () => {
    const response = { assignmentId: 21, editable: true, kpis: [] }
    const payload = [
      { employeeKpiId: 91, rating: 4, comment: 'Good progress' },
      { employeeKpiId: 92, rating: null, comment: '' },
    ]
    const getRequest = captureRequest(response)

    await expect(saveSelfAssessmentDraft(21, payload)).resolves.toEqual(response)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/employee/self-assessments/21/draft')
    expect(JSON.parse(getRequest().data)).toEqual({ kpis: payload })
  })

  it('submits using the separate submit endpoint without a request body', async () => {
    const status = {
      assignmentId: 21,
      submitted: true,
      assignmentStatus: 'MANAGER_REVIEW_PENDING',
    }
    const getRequest = captureRequest(status)

    await expect(submitSelfAssessment(21)).resolves.toEqual(status)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/employee/self-assessments/21/submit')
    expect(getRequest().data).toBeUndefined()
  })

  it('propagates backend errors to the page', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(submitSelfAssessment(21)).rejects.toBe(requestError)
  })
})
