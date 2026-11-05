import apiClient from './axiosInstance.js'

export function getCycles() {
  return apiClient.get('/hr/cycles').then(({ data }) => data)
}

export function getCycleById(cycleId) {
  return apiClient.get(`/hr/cycles/${cycleId}`).then(({ data }) => data)
}

export function getCycleKpis(cycleId) {
  return apiClient.get(`/hr/cycles/${cycleId}/kpis`).then(({ data }) => data)
}

export function configureCycleKpis(cycleId, kpis) {
  return apiClient.post(`/hr/cycles/${cycleId}/kpis`, { kpis }).then(({ data }) => data)
}

export function getCycleEmployees(cycleId) {
  return apiClient.get(`/hr/cycles/${cycleId}/employees`).then(({ data }) => data)
}

export function configureCycleEmployees(cycleId, employees) {
  return apiClient.put(`/hr/cycles/${cycleId}/employees`, { employees }).then(({ data }) => data)
}

export function publishCycle(cycleId) {
  return apiClient.post(`/hr/cycles/${cycleId}/publish`).then(({ data }) => data)
}

export function createCycle(payload) {
  return apiClient.post('/hr/cycles', payload).then(({ data }) => data)
}

export function updateCycle(cycleId, payload) {
  return apiClient.put(`/hr/cycles/${cycleId}`, payload).then(({ data }) => data)
}
