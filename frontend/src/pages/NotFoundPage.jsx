import { Link } from 'react-router-dom'

export default function NotFoundPage() {
  return (
    <main className="message-page">
      <div className="message-card">
        <p className="dashboard-eyebrow">Evalora</p>
        <h1>Page not found</h1>
        <p>The page you requested does not exist.</p>
        <Link className="primary-link" to="/">Return to your dashboard</Link>
      </div>
    </main>
  )
}
