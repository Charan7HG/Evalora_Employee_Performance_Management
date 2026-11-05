import { useCallback, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import {
  getCycleById,
  getCycleEmployees,
  getCycleKpis,
  publishCycle,
} from '../../api/cycleApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'
import CycleEmployeeAssignments from './CycleEmployeeAssignments.jsx'
import CycleKpiAssignments from './CycleKpiAssignments.jsx'

function display(value) {
  return value || 'Not provided'
}

function formatTimestamp(value) {
  return value ? new Date(value).toLocaleString() : 'Not provided'
}

export default function CycleDetailPage() {
  const { id } = useParams()
  const location = useLocation()
  const loader = useCallback(() => getCycleById(id), [id])
  const { data: cycle, error, isLoading, retry } = useApiResource(loader, `cycle:${id}`)
  const summaryLoader = useCallback(async () => {
    const [kpis, employees] = await Promise.all([
      getCycleKpis(id),
      getCycleEmployees(id),
    ])
    return { kpis, employees }
  }, [id])
  const {
    data: summary,
    error: summaryError,
    isLoading: summaryLoading,
    retry: retrySummary,
  } = useApiResource(summaryLoader, `cycle-publish-summary:${id}`)
  const [assignmentRefreshKey, setAssignmentRefreshKey] = useState(0)
  const [cycleOverride, setCycleOverride] = useState(null)
  const [isPublishing, setIsPublishing] = useState(false)
  const publishingRef = useRef(false)
  const [publishError, setPublishError] = useState('')
  const [publishSuccess, setPublishSuccess] = useState('')
  const { ask, dialog } = useConfirmation()
  const currentCycle = cycleOverride && (isLoading || error) ? cycleOverride : cycle
  const publishSummaryReady = !summaryLoading
    && !summaryError
    && Array.isArray(summary?.kpis)
    && Array.isArray(summary?.employees)
  const kpiCount = publishSummaryReady ? summary.kpis.length : null
  const managerKpiCount = publishSummaryReady
    ? summary.kpis.filter((kpi) => (kpi.kpiType || 'MANAGER') === 'MANAGER').length
    : null
  const hrKpiCount = publishSummaryReady ? summary.kpis.length - managerKpiCount : null
  const employeeCount = publishSummaryReady ? summary.employees.length : null
  const configurationReady = publishSummaryReady && kpiCount > 0 && employeeCount > 0
  const canPublish = Boolean(currentCycle)
    && currentCycle.status === 'DRAFT'
    && configurationReady
  const configurationMessage = publishSummaryReady && !configurationReady
    ? kpiCount === 0 && employeeCount === 0
      ? 'Assign at least one KPI and at least one employee before publishing.'
      : kpiCount === 0
        ? 'Assign at least one KPI before publishing.'
        : employeeCount === 0
          ? 'Assign at least one employee before publishing.'
          : ''
    : ''

  /**
   * Called immediately after a KPI or employee assignment save succeeds. The
   * publish button reads these two counts, so they must be refetched before the
   * button can correctly become enabled - previously only the child lists
   * refreshed, which is what made the button look stuck until a page reload.
   */
  const refreshAssignments = useCallback(() => {
    retrySummary()
    retry()
  }, [retry, retrySummary])

  async function publish() {
    // The confirmation dialog can be confirmed twice in the same tick; the
    // ref makes the second call a no-op instead of publishing twice.
    if (publishingRef.current) {
      return
    }
    publishingRef.current = true
    setPublishError('')
    setPublishSuccess('')
    setIsPublishing(true)
    try {
      const publishedCycle = await publishCycle(id)
      setCycleOverride(publishedCycle)
      setPublishSuccess(
        `Cycle "${publishedCycle.name}" published successfully. Status: ${publishedCycle.status}.`,
      )
      retry()
      retrySummary()
      setAssignmentRefreshKey((current) => current + 1)
    } catch (requestError) {
      const backendMessage = getApiErrorMessage(requestError)
      setPublishError(requestError?.response?.status === 409
        ? `The cycle may have changed or its configuration may no longer be valid. Latest cycle and assignment information has been refreshed. ${backendMessage}`
        : backendMessage)
      if (requestError?.response?.status === 409) {
        retry()
        retrySummary()
        setAssignmentRefreshKey((current) => current + 1)
      }
    } finally {
      publishingRef.current = false
      setIsPublishing(false)
    }
  }

  function requestPublish() {
    if (!canPublish || isPublishing) {
      return
    }

    ask({
      title: 'Publish performance cycle',
      message: [
        `Publish "${currentCycle.name}"?`,
        `Configured KPIs: ${kpiCount} (manager-assessed: ${managerKpiCount}, HR-assessed: ${hrKpiCount})`,
        `Assigned employees: ${employeeCount}`,
        'Publishing starts this cycle, creates frozen KPI snapshots for each employee, and makes the draft configuration immutable. This action may be irreversible.',
      ],
      confirmLabel: 'Publish cycle',
      busyLabel: 'Publishing…',
      onConfirm: publish,
    })
  }

  return (
    <section aria-labelledby="cycle-detail-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="cycle-detail-title">Performance cycle details</h1>
        </div>
        <Link className="secondary-button button-link" to="/hr/cycles">Back to cycles</Link>
      </div>
      {location.state?.successMessage && (
        <p className="management-message management-success" role="status">{location.state.successMessage}</p>
      )}
      {publishSuccess && <p className="management-message management-success" role="status">{publishSuccess}</p>}
      {publishError && <p className="management-message management-error" role="alert">{publishError}</p>}
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {currentCycle && (
        <>
          <dl className="staff-details cycle-details-grid">
            <div><dt>Cycle name</dt><dd>{currentCycle.name}</dd></div>
            <div>
              <dt>Status</dt>
              <dd>
                <span className={`status-pill cycle-status-${(currentCycle.status || '').toLowerCase()}`}>
                  {(currentCycle.status || '').replaceAll('_', ' ')}
                </span>
              </dd>
            </div>
            <div className="cycle-detail-description"><dt>Description</dt><dd>{display(currentCycle.description)}</dd></div>
            <div><dt>Start date</dt><dd>{display(currentCycle.startDate)}</dd></div>
            <div><dt>End date</dt><dd>{display(currentCycle.endDate)}</dd></div>
            <div><dt>Self-assessment deadline</dt><dd>{display(currentCycle.selfAssessmentDeadline)}</dd></div>
            <div><dt>Manager review deadline</dt><dd>{display(currentCycle.managerReviewDeadline)}</dd></div>
            <div><dt>HR review deadline</dt><dd>{display(currentCycle.hrReviewDeadline)}</dd></div>
            <div><dt>Created</dt><dd>{formatTimestamp(currentCycle.createdAt)}</dd></div>
            <div><dt>Last updated</dt><dd>{formatTimestamp(currentCycle.updatedAt)}</dd></div>
            {currentCycle.publishedAt && <div><dt>Published</dt><dd>{formatTimestamp(currentCycle.publishedAt)}</dd></div>}
            {currentCycle.completedAt && <div><dt>Completed</dt><dd>{formatTimestamp(currentCycle.completedAt)}</dd></div>}
          </dl>
          {currentCycle.status === 'DRAFT' && (
            <div className="management-actions">
              <Link className="primary-link" to={`/hr/cycles/${id}/edit`}>Edit cycle</Link>
            </div>
          )}
          <CycleKpiAssignments
            key={`kpis-${id}-${assignmentRefreshKey}`}
            cycleId={id}
            cycleStatus={currentCycle.status}
            onCycleConflict={retry}
            onAssignmentsChanged={refreshAssignments}
          />
          <CycleEmployeeAssignments
            key={`employees-${id}-${assignmentRefreshKey}`}
            cycleId={id}
            cycleStatus={currentCycle.status}
            onCycleConflict={retry}
            onAssignmentsChanged={refreshAssignments}
          />
          <section className="cycle-publish-section" aria-labelledby="cycle-publish-title">
            <div>
              <p className="dashboard-eyebrow">Final step</p>
              <h2 id="cycle-publish-title">Publishing summary</h2>
              <p>
                Review the assigned KPIs and employees above, then publish. Publishing
                starts the cycle and freezes KPI snapshots for each assigned employee.
                A published configuration cannot be edited.
              </p>
            </div>
            <ManagementRequestState
              error={summaryError}
              isLoading={summaryLoading}
              onRetry={retrySummary}
            />
            {publishSummaryReady && (
              <>
                <dl className="cycle-publish-counts">
                  <div><dt>Configured KPIs</dt><dd>{summary.kpis.length}</dd></div>
                  <div><dt>Manager-assessed KPIs (75%)</dt><dd>{managerKpiCount}</dd></div>
                  <div><dt>HR-assessed KPIs (25%)</dt><dd>{hrKpiCount}</dd></div>
                  <div><dt>Assigned employees</dt><dd>{summary.employees.length}</dd></div>
                </dl>
                <p className="field-hint">
                  Manager-assessed KPIs form the 75% manager category and HR-assessed
                  KPIs form the 25% HR category. Employees self-rate both types for
                  context only, and their self-rating never contributes to the score.
                </p>
              </>
            )}
            {configurationMessage && (
              <p className="field-hint" role="status">{configurationMessage}</p>
            )}
            {currentCycle.status === 'DRAFT' && (
              <button
                className="primary-button"
                type="button"
                disabled={!canPublish || isPublishing}
                onClick={requestPublish}
              >
                {isPublishing ? 'Publishing…' : 'Publish Cycle'}
              </button>
            )}
            {currentCycle.status !== 'DRAFT' && (
              <p className="cycle-published-status" role="status">
                This cycle has been published, so it can no longer be published again.
                {currentCycle.publishedAt
                  ? ` Published on ${formatTimestamp(currentCycle.publishedAt)}.`
                  : ''}
              </p>
            )}
          </section>
        </>
      )}
    </section>
  )
}
