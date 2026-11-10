import { Link } from 'react-router-dom'
import { getManagerDashboard } from '../../api/dashboardApi.js'
import DashboardGreeting from '../../components/DashboardGreeting.jsx'
import DashboardRequestState from '../../components/DashboardRequestState.jsx'
import { useDashboardData } from '../../hooks/useDashboardData.js'

/**
 * Every workload counter opens a list scoped to the authenticated manager.
 */
const statistics = [
  { label: 'Active direct reports', field: 'totalActiveDirectReports', path: '/manager/direct-reports' },
  { label: 'Employees awaiting self-assessment', field: 'employeesAwaitingSelfAssessment', path: '/manager/assessment-status/self-assessment-pending' },
  {
    label: 'Assessments awaiting manager review',
    field: 'assessmentsAwaitingManagerReview',
    path: '/manager/assessment-status/manager-review-pending',
  },
  { label: 'Assessments awaiting HR review', field: 'assessmentsAwaitingHrReview', path: '/manager/assessment-status/hr-review-pending' },
  { label: 'Completed assessments', field: 'completedAssessments', path: '/manager/assessment-status/completed' },
  { label: 'Published results', field: 'publishedResults', path: '/manager/results' },
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

export default function ManagerDashboardPage() {
  const { data, error, isLoading, retry } = useDashboardData(getManagerDashboard)

  return (
    <section aria-labelledby="dashboard-title">
      <p className="dashboard-eyebrow">Manager workspace</p>
      <DashboardGreeting />
      <h1 id="dashboard-title">Dashboard</h1>
      <p className="dashboard-intro">Your current team and assessment workload.</p>
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
