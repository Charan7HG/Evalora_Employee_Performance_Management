import { Link } from 'react-router-dom'
import { getHrDashboard } from '../../api/dashboardApi.js'
import DashboardRequestState from '../../components/DashboardRequestState.jsx'
import { useDashboardData } from '../../hooks/useDashboardData.js'

/**
 * Every card that represents a real HR section links to that section's page.
 * Cards without a path are genuinely informational counters and stay plain.
 */
const statistics = [
  { label: 'Active employees', field: 'totalActiveEmployees', path: '/hr/employees' },
  { label: 'Active managers', field: 'totalActiveManagers', path: '/hr/managers' },
  { label: 'Performance cycles', field: 'totalPerformanceCycles', path: '/hr/cycles' },
  { label: 'Published cycles', field: 'totalPublishedCycles', path: '/hr/cycles' },
  { label: 'Awaiting self-assessment', field: 'employeesAwaitingSelfAssessment', path: '/hr/assessment-status/self-assessment-pending' },
  { label: 'Pending manager reviews', field: 'employeesAwaitingManagerReview', path: '/hr/assessment-status/manager-review-pending' },
  { label: 'Pending HR reviews', field: 'employeesAwaitingHrReview', path: '/hr/assessment-status/hr-review-pending' },
  { label: 'Completed assessments', field: 'completedAssessments', path: '/hr/assessment-status/completed' },
  { label: 'Published results', field: 'publishedResults', path: '/hr/results/browse' },
]

function StatisticCard({ label, value, path }) {
  const content = (
    <>
      <h2>{label}</h2>
      <p>{value}</p>
    </>
  )

  if (!path) {
    return <article className="statistic-card">{content}</article>
  }

  return (
    <Link className="statistic-card statistic-card-link" to={path}>
      {content}
      <span className="statistic-card-action" aria-hidden="true">Open →</span>
    </Link>
  )
}

export default function HrDashboardPage() {
  const { data, error, isLoading, retry } = useDashboardData(getHrDashboard)

  return (
    <section aria-labelledby="dashboard-title">
      <p className="dashboard-eyebrow">HR workspace</p>
      <h1 id="dashboard-title">Dashboard</h1>
      <p className="dashboard-intro">Organization-wide performance activity.</p>
      <DashboardRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && (
        <div className="statistics-grid">
          {statistics.map(({ label, field, path }) => (
            <StatisticCard key={field} label={label} value={data[field]} path={path} />
          ))}
        </div>
      )}
    </section>
  )
}
