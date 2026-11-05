import { useCallback } from 'react'
import { getManagerDirectReports } from '../../api/assessmentStatusApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

export default function ManagerDirectReportsPage() {
  const loader = useCallback(() => getManagerDirectReports(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'manager-direct-reports')
  return (
    <section aria-labelledby="direct-reports-title">
      <p className="dashboard-eyebrow">Manager workspace</p>
      <h1 id="direct-reports-title">Active direct reports</h1>
      <p className="dashboard-intro">Your active employees and their current team details.</p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && data.length === 0 && <p className="management-empty">You have no active direct reports.</p>}
      {data && !error && data.length > 0 && (
        <div className="staff-table-wrap">
          <table className="staff-table">
            <thead><tr><th>Employee</th><th>Employee code</th><th>Department</th><th>Designation</th><th>Status</th></tr></thead>
            <tbody>{data.map((employee) => (
              <tr key={employee.employeeId}>
                <td>{employee.fullName}</td><td>{employee.employeeCode}</td>
                <td>{employee.department || 'Not provided'}</td><td>{employee.designation || 'Not provided'}</td><td>{employee.status}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  )
}
