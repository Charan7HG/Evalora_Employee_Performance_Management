import apiClient from './axiosInstance.js'

export async function login({ email, password }) {
  const response = await apiClient.post('/auth/login', { email, password })
  return response.data
}

export async function activateAccount({ token, newPassword, confirmPassword }) {
  const response = await apiClient.post('/auth/activate-account', {
    token,
    newPassword,
    confirmPassword,
  })
  return response.data
}

export async function getCurrentUser() {
  const response = await apiClient.get('/auth/me')
  return response.data
}

export async function logout() {
  const response = await apiClient.post('/auth/logout')
  return response.data
}

export async function changePassword({ currentPassword, newPassword, confirmPassword }) {
  const response = await apiClient.put('/auth/change-password', {
    currentPassword,
    newPassword,
    confirmPassword,
  })
  return response.data
}

export async function forgotPassword({ email }) {
  const response = await apiClient.post('/auth/forgot-password', { email })
  return response.data
}

export async function verifyResetOtp({ email, otp }) {
  const response = await apiClient.post('/auth/verify-reset-otp', { email, otp })
  return response.data
}

export async function resetPassword({ token, newPassword, confirmPassword }) {
  const response = await apiClient.post('/auth/reset-password', {
    token,
    newPassword,
    confirmPassword,
  })
  return response.data
}

export async function resendOtp({ email }) {
  return forgotPassword({ email })
}

export async function devResetPassword({ newPassword, confirmPassword }) {
  const response = await apiClient.post('/auth/dev-reset-password', {
    newPassword,
    confirmPassword,
  })
  return response.data
}