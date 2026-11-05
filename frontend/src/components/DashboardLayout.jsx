import { Link, NavLink } from 'react-router-dom'
import { useAuth } from '../context/useAuth.js'
import { dashboardPathForRole } from '../utils/roles.js'

export default function DashboardLayout({ children }) {
  const { user, signOut } = useAuth()
  const dashboardPath = dashboardPathForRole(user.role)
  const navigation = [
    { label: 'Dashboard', path: dashboardPath },
    ...(user.role === 'HR'
      ? [
          { label: 'Employees', path: '/hr/employees' },
          { label: 'Managers', path: '/hr/managers' },
          { label: 'KPIs', path: '/hr/kpis' },
          { label: 'Performance Cycles', path: '/hr/cycles' },
          { label: 'HR Assessments', path: '/hr/assessments' },
          { label: 'Results awaiting publication', path: '/hr/results' },
          { label: 'Results by cycle', path: '/hr/results/browse' },
        ]
      : user.role === 'EMPLOYEE'
        ? [
            { label: 'Self-assessments', path: '/employee/self-assessments' },
            { label: 'Results', path: '/employee/results' },
          ]
        : user.role === 'MANAGER'
          ? [
              { label: 'Assessments', path: '/manager/assessments' },
              { label: 'Results', path: '/manager/results' },
            ]
          : []),
  ]

  return (
    <div className="dashboard-shell">
      <aside className="dashboard-sidebar">
        <Link className="wordmark" to={dashboardPath}>Evalora</Link>
        <nav aria-label="Main navigation">
          {navigation.map(({ label, path }) => (
            <NavLink className="dashboard-nav-link" key={path} to={path}>
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="dashboard-main">
        <header className="dashboard-header">
          <div className="account-summary">
            <span>{user.email}</span>
            <span className="role-badge">{user.role}</span>
          </div>
          <button className="secondary-button logout-button" onClick={signOut} type="button">
            Sign out
          </button>
        </header>
        <main className="dashboard-content">{children}</main>
      </div>
    </div>
  )
}
