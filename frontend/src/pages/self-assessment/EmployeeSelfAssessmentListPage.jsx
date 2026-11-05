import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getSelfAssessmentCycles } from '../../api/selfAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function displayDate(value) {
  return value || 'Not provided'
}

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

export default function EmployeeSelfAssessmentListPage() {
  const loader = useCallback(() => getSelfAssessmentCycles(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'employee-self-assessment-cycles')

  return (
    <section aria-labelledby="self-assessment-list-title">
      <p className="dashboard-eyebrow">Employee workspace</p>
      <h1 id="self-assessment-list-title">Your self-assessments</h1>
      <p className="dashboard-intro">
        Review your assigned performance cycles and complete your self-assessment.
      </p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && data.length === 0 && (
        <p className="management-empty">You do not have any published performance cycle assignments.</p>
      )}
      {data && !error && data.length > 0 && (
        <div className="self-assessment-list">
          {data.map((assignment) => {
            const pending = assignment.assignmentStatus === 'SELF_ASSESSMENT_PENDING'
            return (
              <article className="self-assessment-assignment" key={assignment.assignmentId}>
                <div>
                  <p className="dashboard-eyebrow">Performance cycle</p>
                  <h2>{assignment.cycleName}</h2>
                </div>
                <dl className="cycle-details">
                  <div><dt>Cycle dates</dt><dd>{displayDate(assignment.startDate)} – {displayDate(assignment.endDate)}</dd></div>
                  <div><dt>Self-assessment deadline</dt><dd>{displayDate(assignment.selfAssessmentDeadline)}</dd></div>
                  <div><dt>Assignment status</dt><dd>{formatStatus(assignment.assignmentStatus)}</dd></div>
                  {assignment.selfSubmittedAt && (
                    <div><dt>Submitted</dt><dd>{assignment.selfSubmittedAt}</dd></div>
                  )}
                </dl>
                <Link
                  className="primary-link self-assessment-action"
                  to={`/employee/self-assessments/${assignment.assignmentId}`}
                >
                  {pending ? 'Open self-assessment' : 'View submission'}
                </Link>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
