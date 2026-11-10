import { useAuth } from '../context/useAuth.js'

/** The profile name for the greeting, or a neutral fallback when it is blank. */
function greetingName(user) {
  const fullName = typeof user?.fullName === 'string' ? user.fullName.trim() : ''
  return fullName || 'there'
}

/**
 * Personalized greeting shared by the HR, Manager and Employee dashboards.
 * The name comes from the authenticated identity loaded by AuthContext, so it
 * survives a page refresh that re-fetches `/api/auth/me`.
 */
export default function DashboardGreeting() {
  const { user } = useAuth()
  return <p className="dashboard-greeting">Hi, {greetingName(user)}!</p>
}
