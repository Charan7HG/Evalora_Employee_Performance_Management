import { useCallback } from 'react'
import { getManagerResults } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import ResultList from '../../components/ResultList.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

export default function ManagerResultListPage() {
  const loader = useCallback(() => getManagerResults(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'manager-results')

  return (
    <section aria-labelledby="manager-result-list-title">
      <p className="dashboard-eyebrow">Manager workspace</p>
      <h1 id="manager-result-list-title">Published results for your team</h1>
      <p className="dashboard-intro">
        Published results for employees whose assignment for that cycle names you as
        the reviewing manager. The backend applies this ownership rule and the frontend
        sends no manager or employee identity.
      </p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && (
        <ResultList
          results={data}
          detailBasePath="/manager/results"
          emptyMessage="No published performance results are available for your team yet."
        />
      )}
    </section>
  )
}
