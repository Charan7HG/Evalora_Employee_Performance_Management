import apiClient from './axiosInstance.js'

/*
 * Result endpoints verified against HrResultController,
 * EmployeeResultController and ManagerResultController.
 *
 * The backend derives the caller from the authenticated principal, so no
 * HR, employee or manager identity is ever sent by the frontend.
 */

export function getHrResultQueue() {
  return apiClient.get('/hr/results').then(({ data }) => data)
}

export function getHrResult(resultId) {
  return apiClient.get(`/hr/results/${resultId}`).then(({ data }) => data)
}

/**
 * Every result of one cycle visible to HR (published and not yet published),
 * optional exact department filter. Used by the HR results browser.
 */
export function getHrCycleResults(cycleId, department) {
  const config = department ? { params: { department } } : undefined
  return apiClient.get(`/hr/results/cycles/${cycleId}`, config).then(({ data }) => data)
}

export function publishHrResult(resultId) {
  return apiClient.post(`/hr/results/${resultId}/publish`).then(({ data }) => data)
}

export function publishHrCycleResults(cycleId) {
  return apiClient.post(`/hr/results/cycles/${cycleId}/publish`).then(({ data }) => data)
}

export function getEmployeeResults() {
  return apiClient.get('/employee/results').then(({ data }) => data)
}

export function getEmployeeResult(resultId) {
  return apiClient.get(`/employee/results/${resultId}`).then(({ data }) => data)
}

export function getManagerResults() {
  return apiClient.get('/manager/results').then(({ data }) => data)
}

export function getManagerResult(resultId) {
  return apiClient.get(`/manager/results/${resultId}`).then(({ data }) => data)
}
