export default function ManagementRequestState({ error, isLoading, onRetry }) {
  if (isLoading) {
    return <p className="management-message" role="status">Loading…</p>
  }

  if (!error) {
    return null
  }

  return (
    <section className="management-message management-error" role="alert">
      <p>{error.message}</p>
      {onRetry && (
        <button className="secondary-button" onClick={onRetry} type="button">
          Retry
        </button>
      )}
    </section>
  )
}
