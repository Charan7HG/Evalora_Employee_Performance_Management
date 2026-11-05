import apiClient from './axiosInstance.js'

export function getSelfAssessmentCycles() {
  return apiClient.get('/employee/self-assessments/cycles').then(({ data }) => data)
}

export function getSelfAssessment(assignmentId) {
  return apiClient.get(`/employee/self-assessments/${assignmentId}`).then(({ data }) => data)
}

export function getSelfAssessmentStatus(assignmentId) {
  return apiClient.get(`/employee/self-assessments/${assignmentId}/status`).then(({ data }) => data)
}

export function saveSelfAssessmentDraft(assignmentId, kpis) {
  return apiClient.put(`/employee/self-assessments/${assignmentId}/draft`, { kpis })
    .then(({ data }) => data)
}

export function submitSelfAssessment(assignmentId) {
  return apiClient.post(`/employee/self-assessments/${assignmentId}/submit`).then(({ data }) => data)
}
