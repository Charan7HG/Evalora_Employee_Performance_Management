import apiClient from './axiosInstance.js'

export function getHrAssessmentQueue() {
  return apiClient.get('/hr/assessments').then(({ data }) => data)
}

export function getHrAssessment(assignmentId) {
  return apiClient.get(`/hr/assessments/${assignmentId}`).then(({ data }) => data)
}

export function getHrAssessmentStatus(assignmentId) {
  return apiClient.get(`/hr/assessments/${assignmentId}/status`).then(({ data }) => data)
}

export function saveHrAssessmentDraft(assignmentId, kpis) {
  return apiClient.put(`/hr/assessments/${assignmentId}/draft`, { kpis })
    .then(({ data }) => data)
}

export function finalizeHrAssessment(assignmentId) {
  return apiClient.post(`/hr/assessments/${assignmentId}/submit`).then(({ data }) => data)
}
