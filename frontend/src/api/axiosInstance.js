import axios from 'axios'
import {
  AUTHENTICATION_FAILURE_EVENT,
  clearToken,
  getToken,
} from './tokenStorage.js'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  headers: {
    Accept: 'application/json',
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  const token = getToken()
  const isLoginRequest = config.url?.replace(/\/+$/, '').endsWith('/auth/login')

  if (token && !isLoginRequest) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const isLoginRequest = error.config?.url?.replace(/\/+$/, '').endsWith('/auth/login')

    if (error.response?.status === 401 && !isLoginRequest) {
      clearToken()
      window.dispatchEvent(new Event(AUTHENTICATION_FAILURE_EVENT))
    }

    return Promise.reject(error)
  },
)

export function getApiErrorMessage(error) {
  const status = error?.response?.status
  const serverMessage = error?.response?.data?.message

  if (!error?.response) {
    return 'Unable to reach Evalora. Check your connection and whether the backend is running.'
  }

  if (status === 400) {
    return serverMessage || 'Please check the information you entered.'
  }
  if (status === 401) {
    const isLoginRequest = error.config?.url?.replace(/\/+$/, '').endsWith('/auth/login')
    return isLoginRequest
      ? serverMessage || 'Your email or password is incorrect.'
      : 'Your session has expired. Please sign in again.'
  }
  if (status === 403) {
    return 'You do not have permission to perform this action.'
  }
  if (status === 404) {
    return serverMessage || 'The requested record could not be found.'
  }
  if (status === 409) {
    return serverMessage || 'This operation conflicts with the current account data.'
  }
  if (status === 423) {
    return serverMessage || 'Your account is temporarily locked. Please try again later.'
  }
  if (status >= 500) {
    return 'Evalora could not complete your request. Please try again later.'
  }

  return 'Something went wrong. Please try again.'
}

export default apiClient
