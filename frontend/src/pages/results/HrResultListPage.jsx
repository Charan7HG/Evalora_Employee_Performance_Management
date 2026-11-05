import { useCallback, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { getHrResultQueue, publishHrCycleResults } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatValue(value) {
  return value == null || value === '' ? 'Not provided' : value
}

function formatStatus(value) {
  return value ? value.replaceAll('_', ' ') : 'Unknown'
}

function groupByCycle(results) {
  const groups = []
  const groupsById = new Map()

  for (const result of results) {
    const cycleId = result.cycle?.cycleId
    if (!groupsById.has(cycleId)) {
      const group = { cycleId, cycle: result.cycle || {}, results: [] }
      groupsById.set(cycleId, group)
      groups.push(group)
    }
    groupsById.get(cycleId).results.push(result)
  }

  return groups
}

export default function HrResultListPage() {
  const loader = useCallback(() => getHrResultQueue(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'hr-result-queue')
  const [publishingCycleId, setPublishingCycleId] = useState(null)
  const [actionError, setActionError] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const publishLock = useRef(false)
  const { ask, dialog } = useConfirmation()

  const groups = data && !error ? groupByCycle(data) : []

  function handleBulkPublish(group) {
    if (publishLock.current) {
      return
    }

    const cycleName = group.cycle.cycleName || 'this performance cycle'
    ask({
      title: 'Publish eligible results',
      message: `Publish all eligible results for "${cycleName}"? `
        + 'Published results become visible to the employee and the assigned manager '
        + 'and cannot be unpublished.',
      confirmLabel: 'Publish results',
      busyLabel: 'Publishing…',
      onConfirm: () => publishGroup(group, cycleName),
    })
  }

  async function publishGroup(group, cycleName) {
    publishLock.current = true
    setPublishingCycleId(group.cycleId)
    setActionError('')
    setSuccessMessage('')
    try {
      const publication = await publishHrCycleResults(group.cycleId)
      setSuccessMessage(
        `Published ${publication.publishedCount} result(s) from "${cycleName}". `
        + `${publication.skippedCount} assignment(s) were skipped as ineligible.`,
      )
      retry()
    } catch (requestError) {
      setActionError(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409 || requestError?.response?.status === 404) {
        retry()
      }
    } finally {
      publishLock.current = false
      setPublishingCycleId(null)
    }
  }

  return (
    <section aria-labelledby="hr-result-list-title">
      {dialog}
      <p className="dashboard-eyebrow">HR workspace</p>
      <h1 id="hr-result-list-title">Results awaiting publication</h1>
      <p className="dashboard-intro">
        Finalized results returned by the backend as eligible for publication. Bulk
        publication applies only to eligible results in a published cycle.
        {' '}
        <Link to="/hr/results/browse">Browse results by cycle and department →</Link>
      </p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {actionError && (
        <p className="management-message management-error" role="alert">{actionError}</p>
      )}
      {successMessage && (
        <p className="management-message management-success" role="status">{successMessage}</p>
      )}
      {data && !error && data.length === 0 && (
        <p className="management-empty">There are no performance results awaiting publication.</p>
      )}
      {groups.length > 0 && (
        <div className="result-cycle-groups">
          {groups.map((group, index) => {
            const cycleName = group.cycle.cycleName || 'Performance cycle'
            const headingId = `hr-result-cycle-${group.cycleId ?? index}`
            return (
              <section
                className="result-cycle-group"
                key={group.cycleId ?? index}
                aria-labelledby={headingId}
              >
                <div className="result-cycle-heading">
                  <div>
                    <p className="dashboard-eyebrow">Performance cycle</p>
                    <h2 id={headingId}>{cycleName}</h2>
                    <p className="result-card-code">
                      {formatValue(group.cycle.startDate)} – {formatValue(group.cycle.endDate)}
                    </p>
                  </div>
                  <div className="result-cycle-actions">
                    <p>
                      {group.results.length} result{group.results.length === 1 ? '' : 's'} awaiting
                      publication
                    </p>
                    <button
                      aria-label={`Publish eligible results for ${cycleName}`}
                      className="primary-button"
                      disabled={publishingCycleId !== null}
                      onClick={() => handleBulkPublish(group)}
                      type="button"
                    >
                      {publishingCycleId === group.cycleId
                        ? 'Publishing…'
                        : 'Publish eligible results'}
                    </button>
                  </div>
                </div>
                <div className="result-list">
                  {group.results.map((result) => {
                    const employee = result.employee || {}
                    return (
                      <article className="result-row" key={result.resultId}>
                        <div>
                          <h3>{employee.fullName || 'Employee result'}</h3>
                          {employee.employeeCode && (
                            <p className="result-card-code">{employee.employeeCode}</p>
                          )}
                        </div>
                        <dl className="cycle-details">
                          {employee.department && (
                            <div><dt>Department</dt><dd>{employee.department}</dd></div>
                          )}
                          <div><dt>Result status</dt><dd>{formatStatus(result.status)}</dd></div>
                          <div><dt>Overall score</dt><dd>{formatValue(result.overallScore)}</dd></div>
                          <div>
                            <dt>Grade</dt>
                            <dd>{result.grade ? formatStatus(result.grade) : 'Not provided'}</dd>
                          </div>
                        </dl>
                        <Link
                          className="primary-link result-card-action"
                          to={`/hr/results/${result.resultId}`}
                        >
                          Review result
                        </Link>
                      </article>
                    )
                  })}
                </div>
              </section>
            )
          })}
        </div>
      )}
    </section>
  )
}
