import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import apiClient from './axiosInstance.js'
import {
  getManagerAssessment,
  getManagerAssessmentQueue,
  getManagerAssessmentStatus,
  saveManagerAssessmentDraft,
  submitManagerAssessment,
} from './managerAssessmentApi.js'

const initialAdapter = apiClient.defaults.adapter

function captureRequest(response) {
  let request
  apiClient.defaults.adapter = async (config) => {
    request = config
    return { data: response, status: 200, statusText: 'OK', headers: {}, config }
  }
  return () => request
}

describe('manager assessment API', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => { apiClient.defaults.adapter = initialAdapter })

  it('gets the authenticated manager review queue without a manager ID', async () => {
    const queue = [{ assignmentId: 31, employeeName: 'Eve Employee' }]
    const getRequest = captureRequest(queue)

    await expect(getManagerAssessmentQueue()).resolves.toEqual(queue)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/manager/assessments')
  })

  it('gets assessment detail and status', async () => {
    const assessment = { assignmentId: 31, editable: true, kpis: [] }
    let getRequest = captureRequest(assessment)

    await expect(getManagerAssessment(31)).resolves.toEqual(assessment)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/manager/assessments/31')

    const status = { assignmentId: 31, submitted: false }
    getRequest = captureRequest(status)
    await expect(getManagerAssessmentStatus(31)).resolves.toEqual(status)
    expect(getRequest().method).toBe('get')
    expect(getRequest().url).toBe('/manager/assessments/31/status')
  })

  it('saves manager draft fields in the backend request shape', async () => {
    const response = { assignmentId: 31, editable: true, kpis: [] }
    const payload = [
      { employeeKpiId: 100, rating: 4, comment: 'Strong delivery' },
      { employeeKpiId: 101, rating: null, comment: '' },
    ]
    const getRequest = captureRequest(response)

    await expect(saveManagerAssessmentDraft(31, payload)).resolves.toEqual(response)
    expect(getRequest().method).toBe('put')
    expect(getRequest().url).toBe('/manager/assessments/31/draft')
    expect(JSON.parse(getRequest().data)).toEqual({ kpis: payload })
  })

  it('submits separately with no request body', async () => {
    const status = {
      assignmentId: 31,
      assignmentStatus: 'HR_REVIEW_PENDING',
      submitted: true,
    }
    const getRequest = captureRequest(status)

    await expect(submitManagerAssessment(31)).resolves.toEqual(status)
    expect(getRequest().method).toBe('post')
    expect(getRequest().url).toBe('/manager/assessments/31/submit')
    expect(getRequest().data).toBeUndefined()
  })

  it('propagates backend errors to the caller', async () => {
    const requestError = new Error('conflict')
    apiClient.defaults.adapter = async () => { throw requestError }

    await expect(submitManagerAssessment(31)).rejects.toBe(requestError)
  })
})
