import apiClient from './axiosInstance.js'

export function getKpis(active) {
  const config = active === undefined ? undefined : { params: { active } }
  return apiClient.get('/hr/kpis', config).then(({ data }) => data)
}

export function getKpiById(kpiId) {
  return apiClient.get(`/hr/kpis/${kpiId}`).then(({ data }) => data)
}

export function createKpi(payload) {
  return apiClient.post('/hr/kpis', payload).then(({ data }) => data)
}

export function updateKpi(kpiId, payload) {
  return apiClient.put(`/hr/kpis/${kpiId}`, payload).then(({ data }) => data)
}

export function deactivateKpi(kpiId) {
  return apiClient.patch(`/hr/kpis/${kpiId}/deactivate`).then(({ data }) => data)
}

export function reactivateKpi(kpiId) {
  return apiClient.patch(`/hr/kpis/${kpiId}/reactivate`).then(({ data }) => data)
}
