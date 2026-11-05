import apiClient from './axiosInstance.js'

export function getHrDashboard() {
  return apiClient.get('/hr/dashboard').then(({ data }) => data)
}

export function getManagerDashboard() {
  return apiClient.get('/manager/dashboard').then(({ data }) => data)
}

export function getEmployeeDashboard() {
  return apiClient.get('/employee/dashboard').then(({ data }) => data)
}
