import { useCallback, useEffect, useState } from 'react'
import { getApiErrorMessage } from '../api/axiosInstance.js'

export function useApiResource(loader, resourceKey) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [loadedKey, setLoadedKey] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true

    Promise.resolve()
      .then(loader)
      .then((result) => {
        if (active) {
          setData(result)
          setLoadedKey(resourceKey)
          setError(null)
        }
      })
      .catch((requestError) => {
        if (active) {
          setError({
            message: getApiErrorMessage(requestError),
            status: requestError?.response?.status,
          })
          setLoadedKey(resourceKey)
        }
      })
      .finally(() => {
        if (active) {
          setIsLoading(false)
        }
      })

    return () => {
      active = false
    }
  }, [attempt, loader, resourceKey])

  const retry = useCallback(() => {
    setError(null)
    setIsLoading(true)
    setAttempt((current) => current + 1)
  }, [])

  return {
    data,
    error,
    isLoading: isLoading || loadedKey !== resourceKey,
    retry,
  }
}
