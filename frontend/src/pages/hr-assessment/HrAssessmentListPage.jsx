import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getHrAssessmentQueue } from '../../api/hrAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

export default function HrAssessmentListPage() {
  const loader = useCallback(() => getHrAssessmentQueue(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'hr-assessment-queue')
  const errorState = error?.status === 404
    ? { ...error, message: 'The HR review queue is not available.' }
    : error

  return (
    <section aria-labelledby="hr-assessment-list-title">
      <p className="dashboard-eyebrow">HR workspace</p>
      <h1 id="hr-assessment-list-title">Assessments awaiting HR review</h1>
      <p className="dashboard-intro">
        Review completed manager assessments and finalize eligible assignments.
      </p>
      <ManagementRequestState error={errorState} isLoading={isLoading} onRetry={retry} />
      {data && !error && data.length === 0 && (
        <p className="management-empty">There are no employee assessments awaiting HR review.</p>
      )}
      {data && !error && data.length > 0 && (
        <div className="hr-assessment-list">
          {data.map((assignment) => (
            <article className="hr-assessment-assignment" key={assignment.assignmentId}>
              <div>
                <p className="dashboard-eyebrow">Employee</p>
                <h2>{assignment.employeeName}</h2>
                {assignment.employeeCode && (
                  <p className="manager-assessment-code">{assignment.employeeCode}</p>
                )}
              </div>
              <dl className="cycle-details">
                {assignment.department && (
                  <div><dt>Department</dt><dd>{assignment.department}</dd></div>
                )}
                {assignment.designation && (
                  <div><dt>Designation</dt><dd>{assignment.designation}</dd></div>
                )}
                <div><dt>Performance cycle</dt><dd>{assignment.cycleName}</dd></div>
                <div><dt>Assignment status</dt><dd>{formatStatus(assignment.assignmentStatus)}</dd></div>
                {assignment.managerSubmittedAt && (
                  <div><dt>Manager submitted</dt><dd>{assignment.managerSubmittedAt}</dd></div>
                )}
              </dl>
              {assignment.assignmentStatus === 'HR_REVIEW_PENDING' && (
                <Link
                  className="primary-link manager-assessment-action"
                  to={`/hr/assessments/${assignment.assignmentId}`}
                >
                  Review assessment
                </Link>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
