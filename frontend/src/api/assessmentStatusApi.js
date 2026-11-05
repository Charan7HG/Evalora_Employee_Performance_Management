import apiClient from './axiosInstance.js'

export function getHrAssessmentStatus(status) {
  return apiClient.get('/hr/assessment-status', { params: { status } }).then(({ data }) => data)
}

export function getManagerAssessmentStatusList(status) {
  return apiClient.get('/manager/assessment-status', { params: { status } }).then(({ data }) => data)
}

export function getManagerDirectReports() {
  return apiClient.get('/manager/direct-reports').then(({ data }) => data)
}
