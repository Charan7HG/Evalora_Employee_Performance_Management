export default function DashboardRequestState({ error, isLoading, onRetry }) {
  if (isLoading) {
    return <p className="dashboard-message" role="status">Loading dashboard…</p>
  }

  if (!error) {
    return null
  }

  return (
    <section className="dashboard-message dashboard-error" role="alert">
      <h2>{error.status === 403 ? 'Access denied' : 'Dashboard unavailable'}</h2>
      <p>{error.message}</p>
      <button className="secondary-button" onClick={onRetry} type="button">
        Retry
      </button>
    </section>
  )
}
