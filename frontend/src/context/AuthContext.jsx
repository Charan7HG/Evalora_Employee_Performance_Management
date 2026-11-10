import { useCallback, useEffect, useState } from 'react'
import * as authApi from '../api/authApi.js'
import { getApiErrorMessage } from '../api/axiosInstance.js'
import {
  AUTHENTICATION_FAILURE_EVENT,
  clearToken,
  getToken,
  saveToken,
} from '../api/tokenStorage.js'
import { AuthContext } from './authContext.js'

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [token, setToken] = useState(() => getToken())
  const [isLoading, setIsLoading] = useState(true)
  const [authError, setAuthError] = useState('')

  const clearAuthentication = useCallback((message = '') => {
    clearToken()
    setToken(null)
    setUser(null)
    setAuthError(message)
  }, [])

  const refreshCurrentUser = useCallback(async () => {
    if (!getToken()) {
      setUser(null)
      setToken(null)
      return null
    }

    const currentUser = await authApi.getCurrentUser()
    setUser(currentUser)
    setToken(getToken())
    setAuthError('')
    return currentUser
  }, [])

  useEffect(() => {
    const handleAuthenticationFailure = () => {
      clearAuthentication('Your session has expired. Please sign in again.')
    }

    window.addEventListener(AUTHENTICATION_FAILURE_EVENT, handleAuthenticationFailure)

    let active = true
    const initialize = async () => {
      if (!getToken()) {
        setIsLoading(false)
        return
      }

      try {
        const currentUser = await authApi.getCurrentUser()
        if (active) {
          setUser(currentUser)
          setToken(getToken())
          setAuthError('')
        }
      } catch (error) {
        if (active && error.response?.status !== 401) {
          setAuthError(getApiErrorMessage(error))
        }
      } finally {
        if (active) {
          setIsLoading(false)
        }
      }
    }

    initialize()

    return () => {
      active = false
      window.removeEventListener(
        AUTHENTICATION_FAILURE_EVENT,
        handleAuthenticationFailure,
      )
    }
  }, [clearAuthentication])

  const signIn = useCallback(async (credentials) => {
    setAuthError('')
    const result = await authApi.login(credentials)
    if (!result.token || !result.user) {
      throw new Error('The server returned an incomplete sign-in response.')
    }

    saveToken(result.token)
    setToken(result.token)
    setUser(result.user)
    setIsLoading(false)
    return result.user
  }, [])

  const signOut = useCallback(async () => {
    setAuthError('')
    let logoutMessage = ''
    try {
      if (getToken()) {
        await authApi.logout()
      }
    } catch (error) {
      logoutMessage = `Signed out locally. ${getApiErrorMessage(error)}`
    } finally {
      clearAuthentication(logoutMessage)
    }
  }, [clearAuthentication])

  /**
   * Clears the local session without calling the API. Used after a password
   * change, because the backend increments the token version and every JWT
   * issued before the change is rejected from then on.
   */
  const signOutLocally = useCallback((message = '') => {
    clearAuthentication(message)
  }, [clearAuthentication])

  const value = {
    user,
    token,
    isLoading,
    authError,
    signIn,
    signOut,
    signOutLocally,
    refreshCurrentUser,
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
