import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getManagerAssessmentQueue } from '../../api/managerAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

export default function ManagerAssessmentListPage() {
  const loader = useCallback(() => getManagerAssessmentQueue(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'manager-assessment-queue')

  return (
    <section aria-labelledby="manager-assessment-list-title">
      <p className="dashboard-eyebrow">Manager workspace</p>
      <h1 id="manager-assessment-list-title">Assessments awaiting your review</h1>
      <p className="dashboard-intro">
        Review employee self-assessments assigned to you for this performance cycle.
      </p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && data.length === 0 && (
        <p className="management-empty">There are no employee assessments awaiting your review.</p>
      )}
      {data && !error && data.length > 0 && (
        <div className="manager-assessment-list">
          {data.map((assignment) => (
            <article className="manager-assessment-assignment" key={assignment.assignmentId}>
              <div>
                <p className="dashboard-eyebrow">Employee</p>
                <h2>{assignment.employeeName}</h2>
                {assignment.employeeCode && <p className="manager-assessment-code">{assignment.employeeCode}</p>}
              </div>
              <dl className="cycle-details">
                <div><dt>Performance cycle</dt><dd>{assignment.cycleName}</dd></div>
                <div><dt>Assignment status</dt><dd>{formatStatus(assignment.assignmentStatus)}</dd></div>
                {assignment.selfSubmittedAt && (
                  <div><dt>Self-assessment submitted</dt><dd>{assignment.selfSubmittedAt}</dd></div>
                )}
              </dl>
              <p className="manager-assessment-date-note">
                Cycle dates are shown on the assessment details page.
              </p>
              <Link
                className="primary-link manager-assessment-action"
                to={`/manager/assessments/${assignment.assignmentId}`}
              >
                Review assessment
              </Link>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
