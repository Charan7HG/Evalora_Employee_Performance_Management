import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getHrAssessmentStatus, getManagerAssessmentStatusList } from '../../api/assessmentStatusApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useAuth } from '../../context/useAuth.js'

const statusLabels = {
  'self-assessment-pending': ['SELF_ASSESSMENT_PENDING', 'Awaiting self-assessment'],
  'manager-review-pending': ['MANAGER_REVIEW_PENDING', 'Awaiting manager review'],
  'hr-review-pending': ['HR_REVIEW_PENDING', 'Awaiting HR review'],
  completed: ['COMPLETED', 'Completed assessments'],
}

function readable(value) {
  return (value || 'Unknown').replaceAll('_', ' ')
}

export default function AssessmentStatusListPage() {
  const { status: slug } = useParams()
  const { user } = useAuth()
  const [status, title] = statusLabels[slug] || []
  const loader = useCallback(() => {
    if (!status) return Promise.resolve([])
    return user.role === 'HR'
      ? getHrAssessmentStatus(status)
      : getManagerAssessmentStatusList(status)
  }, [status, user.role])
  const { data, error, isLoading, retry } = useApiResource(loader, `assessment-status:${user.role}:${slug}`)

  return (
    <section aria-labelledby="assessment-status-title">
      <p className="dashboard-eyebrow">{user.role === 'HR' ? 'HR workspace' : 'Manager workspace'}</p>
      <h1 id="assessment-status-title">{title || 'Assessment status'}</h1>
      <p className="dashboard-intro">Employees, cycle details, and current assessment status.</p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && data.length === 0 && <p className="management-empty">No assessments match this status.</p>}
      {data && !error && data.length > 0 && (
        <div className="hr-assessment-list">
          {data.map((item) => (
            <article className="hr-assessment-assignment" key={item.assignmentId}>
              <div>
                <p className="dashboard-eyebrow">Employee</p>
                <h2>{item.employeeFullName}</h2>
                <p className="manager-assessment-code">{item.employeeCode}</p>
              </div>
              <dl className="cycle-details">
                <div><dt>Department</dt><dd>{item.department || 'Not provided'}</dd></div>
                <div><dt>Manager</dt><dd>{item.managerName || 'Not assigned'}</dd></div>
                <div><dt>Performance cycle</dt><dd>{item.cycleName}</dd></div>
                <div><dt>Cycle status</dt><dd>{readable(item.cycleStatus)}</dd></div>
                <div><dt>Assessment status</dt><dd>{readable(item.assignmentStatus)}</dd></div>
                {item.dueDate && <div><dt>Due date</dt><dd>{item.dueDate}</dd></div>}
              </dl>
              {item.assignmentStatus === 'MANAGER_REVIEW_PENDING' && user.role === 'MANAGER' && (
                <Link className="primary-link manager-assessment-action" to={`/manager/assessments/${item.assignmentId}`}>Review assessment</Link>
              )}
              {item.assignmentStatus === 'HR_REVIEW_PENDING' && user.role === 'HR' && (
                <Link className="primary-link manager-assessment-action" to={`/hr/assessments/${item.assignmentId}`}>Review assessment</Link>
              )}
              {item.assignmentStatus === 'COMPLETED' && user.role === 'HR' && (
                <Link className="primary-link manager-assessment-action" to="/hr/results/browse">View cycle results</Link>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
