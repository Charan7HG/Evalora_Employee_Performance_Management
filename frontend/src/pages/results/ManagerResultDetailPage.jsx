import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getManagerResult } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import ResultDetails from '../../components/ResultDetails.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

export default function ManagerResultDetailPage() {
  const { resultId } = useParams()
  const loader = useCallback(() => getManagerResult(resultId), [resultId])
  const { data, error, isLoading, retry } = useApiResource(loader, `manager-result:${resultId}`)
  const result = data && String(data.resultId) === String(resultId) ? data : null
  const errorState = error?.status === 404
    ? { ...error, message: 'This performance result was not found or is not available to you.' }
    : error

  return (
    <section aria-labelledby="manager-result-detail-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">Manager workspace</p>
          <h1 id="manager-result-detail-title">Performance result</h1>
        </div>
        <Link className="secondary-button button-link" to="/manager/results">
          Back to team results
        </Link>
      </div>
      <ManagementRequestState error={errorState} isLoading={isLoading} onRetry={retry} />
      {result && (
        <>
          <p className="management-message management-success" role="status">
            Published performance results are read-only.
          </p>
          <ResultDetails result={result} />
        </>
      )}
    </section>
  )
}
