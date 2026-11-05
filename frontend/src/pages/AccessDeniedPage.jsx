import { Link } from 'react-router-dom'
import { useAuth } from '../context/useAuth.js'
import { dashboardPathForRole } from '../utils/roles.js'

export default function AccessDeniedPage() {
  const { user } = useAuth()

  return (
    <main className="message-page">
      <div className="message-card">
        <p className="dashboard-eyebrow">Evalora</p>
        <h1>Access denied</h1>
        <p>Your account does not have access to this dashboard.</p>
        <Link className="primary-link" to={dashboardPathForRole(user.role)}>
          Go to your dashboard
        </Link>
      </div>
    </main>
  )
}
