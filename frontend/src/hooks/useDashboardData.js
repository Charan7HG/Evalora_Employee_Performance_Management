import { useEffect, useState } from 'react'
import { getApiErrorMessage } from '../api/axiosInstance.js'

export function useDashboardData(loadDashboard) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true

    Promise.resolve()
      .then(loadDashboard)
      .then((result) => {
        if (active) {
          setData(result)
        }
      })
      .catch((requestError) => {
        if (active) {
          setError({
            message: getApiErrorMessage(requestError),
            status: requestError?.response?.status,
          })
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
  }, [attempt, loadDashboard])

  return {
    data,
    error,
    isLoading,
    retry: () => {
      setIsLoading(true)
      setError(null)
      setAttempt((current) => current + 1)
    },
  }
}
