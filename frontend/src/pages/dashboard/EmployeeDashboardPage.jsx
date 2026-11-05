import { getEmployeeDashboard } from '../../api/dashboardApi.js'
import DashboardRequestState from '../../components/DashboardRequestState.jsx'
import { useDashboardData } from '../../hooks/useDashboardData.js'

function CurrentCycle({ cycle }) {
  if (!cycle) {
    return (
      <article className="detail-card">
        <h2>Current performance cycle</h2>
        <p>No current cycle or assessment is assigned.</p>
      </article>
    )
  }

  return (
    <article className="detail-card">
      <h2>Current performance cycle</h2>
      <dl className="cycle-details">
        <div><dt>Cycle</dt><dd>{cycle.cycleName}</dd></div>
        <div><dt>Cycle status</dt><dd>{cycle.cycleStatus}</dd></div>
        <div><dt>Assessment status</dt><dd>{cycle.assessmentStatus}</dd></div>
        <div><dt>Due date</dt><dd>{cycle.dueDate || 'No due date'}</dd></div>
      </dl>
    </article>
  )
}

function ReportingManager({ manager }) {
  if (!manager || !manager.managerName) {
    return (
      <article className="detail-card">
        <h2>Reporting Manager</h2>
        <p>No reporting manager is currently assigned to you.</p>
      </article>
    )
  }

  return (
    <article className="detail-card">
      <h2>Reporting Manager</h2>
      <dl className="cycle-details">
        <div><dt>Name</dt><dd>{manager.managerName}</dd></div>
        {manager.employeeCode && (
          <div><dt>Manager code</dt><dd>{manager.employeeCode}</dd></div>
        )}
      </dl>
    </article>
  )
}

function LatestPublishedResult({ result }) {
  if (!result) {
    return (
      <article className="detail-card">
        <h2>Latest published result</h2>
        <p>No published result is available yet.</p>
      </article>
    )
  }

  return (
    <article className="detail-card">
      <h2>Latest published result</h2>
      <dl className="cycle-details">
        <div><dt>Cycle</dt><dd>{result.cycle?.cycleName || 'Cycle information unavailable'}</dd></div>
        <div><dt>Score</dt><dd>{result.score ?? 'Score unavailable'}</dd></div>
        <div><dt>Published</dt><dd>{result.publishedAt || 'Publication date unavailable'}</dd></div>
        {result.cycle?.startDate && (
          <div><dt>Cycle start</dt><dd>{result.cycle.startDate}</dd></div>
        )}
        {result.cycle?.endDate && (
          <div><dt>Cycle end</dt><dd>{result.cycle.endDate}</dd></div>
        )}
      </dl>
    </article>
  )
}

export default function EmployeeDashboardPage() {
  const { data, error, isLoading, retry } = useDashboardData(getEmployeeDashboard)

  return (
    <section aria-labelledby="dashboard-title">
      <p className="dashboard-eyebrow">Employee workspace</p>
      <h1 id="dashboard-title">Dashboard</h1>
      <p className="dashboard-intro">Your current assessment and published performance result.</p>
      <DashboardRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && (
        <div className="detail-grid">
          <CurrentCycle cycle={data.currentCycle} />
          <ReportingManager manager={data.reportingManager} />
          <LatestPublishedResult result={data.latestPublishedResult} />
        </div>
      )}
    </section>
  )
}
