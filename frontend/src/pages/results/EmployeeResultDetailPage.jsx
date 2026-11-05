import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getEmployeeResult } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import ResultDetails from '../../components/ResultDetails.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

export default function EmployeeResultDetailPage() {
  const { resultId } = useParams()
  const loader = useCallback(() => getEmployeeResult(resultId), [resultId])
  const { data, error, isLoading, retry } = useApiResource(loader, `employee-result:${resultId}`)
  const result = data && String(data.resultId) === String(resultId) ? data : null
  const errorState = error?.status === 404
    ? { ...error, message: 'This performance result was not found or is not available to you.' }
    : error

  return (
    <section aria-labelledby="employee-result-detail-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">Employee workspace</p>
          <h1 id="employee-result-detail-title">Performance result</h1>
        </div>
        <Link className="secondary-button button-link" to="/employee/results">
          Back to your results
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
