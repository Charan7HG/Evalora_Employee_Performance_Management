import apiClient from './axiosInstance.js'

export function getManagerAssessmentQueue() {
  return apiClient.get('/manager/assessments').then(({ data }) => data)
}

export function getManagerAssessment(assignmentId) {
  return apiClient.get(`/manager/assessments/${assignmentId}`).then(({ data }) => data)
}

export function getManagerAssessmentStatus(assignmentId) {
  return apiClient.get(`/manager/assessments/${assignmentId}/status`).then(({ data }) => data)
}

export function saveManagerAssessmentDraft(assignmentId, kpis) {
  return apiClient.put(`/manager/assessments/${assignmentId}/draft`, { kpis })
    .then(({ data }) => data)
}

export function submitManagerAssessment(assignmentId) {
  return apiClient.post(`/manager/assessments/${assignmentId}/submit`).then(({ data }) => data)
}
