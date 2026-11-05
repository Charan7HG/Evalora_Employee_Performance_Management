export const TOKEN_STORAGE_KEY = 'evalora.auth.token'
export const AUTHENTICATION_FAILURE_EVENT = 'evalora:authentication-failure'

export function getToken() {
  return window.localStorage.getItem(TOKEN_STORAGE_KEY)
}

export function saveToken(token) {
  window.localStorage.setItem(TOKEN_STORAGE_KEY, token)
}

export function clearToken() {
  window.localStorage.removeItem(TOKEN_STORAGE_KEY)
}
