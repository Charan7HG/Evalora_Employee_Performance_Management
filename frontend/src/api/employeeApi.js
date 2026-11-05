import apiClient from './axiosInstance.js'

export function createEmployee(payload) {
  return apiClient.post('/hr/employees', payload).then(({ data }) => data)
}

export function listEmployees(status = 'ACTIVE') {
  return apiClient.get('/hr/employees', { params: { status } }).then(({ data }) => data)
}

export function getEmployee(employeeId) {
  return apiClient.get(`/hr/employees/${employeeId}`).then(({ data }) => data)
}

export function updateEmployee(employeeId, payload) {
  return apiClient.put(`/hr/employees/${employeeId}`, payload).then(({ data }) => data)
}

export function assignEmployeeManager(employeeId, managerId) {
  return apiClient.put(`/hr/employees/${employeeId}/manager`, { managerId }).then(({ data }) => data)
}

export function deactivateEmployee(employeeId) {
  return apiClient.patch(`/hr/employees/${employeeId}/deactivate`).then(({ data }) => data)
}

export function reactivateEmployee(employeeId) {
  return apiClient.patch(`/hr/employees/${employeeId}/reactivate`).then(({ data }) => data)
}
