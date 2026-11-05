import apiClient from './axiosInstance.js'

export function createManager(payload) {
  return apiClient.post('/hr/managers', payload).then(({ data }) => data)
}

export function listManagers(status = 'ACTIVE') {
  return apiClient.get('/hr/managers', { params: { status } }).then(({ data }) => data)
}

export function getManager(managerId) {
  return apiClient.get(`/hr/managers/${managerId}`).then(({ data }) => data)
}

export function updateManager(managerId, payload) {
  return apiClient.put(`/hr/managers/${managerId}`, payload).then(({ data }) => data)
}

export function deactivateManager(managerId) {
  return apiClient.patch(`/hr/managers/${managerId}/deactivate`).then(({ data }) => data)
}

export function reactivateManager(managerId) {
  return apiClient.patch(`/hr/managers/${managerId}/reactivate`).then(({ data }) => data)
}
