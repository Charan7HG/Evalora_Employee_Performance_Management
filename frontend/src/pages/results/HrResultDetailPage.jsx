import { useCallback, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { getHrResult, publishHrResult } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import ResultDetails from '../../components/ResultDetails.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatStatus(value) {
  return value ? value.replaceAll('_', ' ') : 'Unknown'
}

export default function HrResultDetailPage() {
  const { resultId } = useParams()
  const loader = useCallback(() => getHrResult(resultId), [resultId])
  const { data, error, isLoading, retry } = useApiResource(loader, `hr-result:${resultId}`)
  const [publishedResult, setPublishedResult] = useState(null)
  const [isPublishing, setIsPublishing] = useState(false)
  const [actionError, setActionError] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const publishLock = useRef(false)
  const { ask, dialog } = useConfirmation()

  const loadedResult = data && String(data.resultId) === String(resultId) ? data : null
  const result = publishedResult
    && String(publishedResult.resultId) === String(resultId)
    ? publishedResult
    : loadedResult
  const errorState = error?.status === 404
    ? { ...error, message: 'This performance result was not found.' }
    : error
  const employeeName = result?.employee?.fullName || 'this employee'
  const cycleName = result?.cycle?.cycleName || 'this performance cycle'

  function handlePublish() {
    if (!result || result.status !== 'DRAFT' || publishLock.current) {
      return
    }

    ask({
      title: 'Publish performance result',
      message: `Publish the performance result for ${employeeName} in "${cycleName}"? `
        + 'It becomes visible to the employee and the assigned manager and cannot be unpublished.',
      confirmLabel: 'Publish result',
      busyLabel: 'Publishing…',
      onConfirm: publishResult,
    })
  }

  async function publishResult() {
    publishLock.current = true
    setIsPublishing(true)
    setActionError('')
    setSuccessMessage('')
    try {
      const published = await publishHrResult(result.resultId)
      setPublishedResult(published)
      setSuccessMessage(
        'Performance result published. It is now visible to the employee and assigned manager.',
      )
      retry()
    } catch (requestError) {
      setActionError(getApiErrorMessage(requestError))
      const status = requestError?.response?.status
      if (status === 404 || status === 409) {
        retry()
      }
    } finally {
      publishLock.current = false
      setIsPublishing(false)
    }
  }

  return (
    <section aria-labelledby="hr-result-detail-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="hr-result-detail-title">Performance result</h1>
        </div>
        <Link className="secondary-button button-link" to="/hr/results">
          Back to results awaiting publication
        </Link>
        <Link className="secondary-button button-link" to="/hr/results/browse">
          Results by cycle
        </Link>
      </div>
      <ManagementRequestState error={errorState} isLoading={isLoading} onRetry={retry} />
      {actionError && (
        <p className="management-message management-error" role="alert">{actionError}</p>
      )}
      {successMessage && (
        <p className="management-message management-success" role="status">{successMessage}</p>
      )}
      {result && (
        <>
          <ResultDetails result={result} />
          <section className="result-publication" aria-labelledby="result-publication-title">
            <h2 id="result-publication-title">Publication</h2>
            {result.status === 'DRAFT' ? (
              <>
                <p>
                  Publishing makes this finalized result visible to the employee and the
                  assigned manager. Scores and KPI items are not recalculated or changed.
                </p>
                <button
                  className="primary-button"
                  disabled={isPublishing}
                  onClick={handlePublish}
                  type="button"
                >
                  {isPublishing ? 'Publishing…' : 'Publish result'}
                </button>
              </>
            ) : (
              <p>
                This performance result is {formatStatus(result.status).toLowerCase()} and
                read-only. It cannot be published again.
              </p>
            )}
          </section>
        </>
      )}
    </section>
  )
}
