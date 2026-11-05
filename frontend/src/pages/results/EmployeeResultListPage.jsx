import { useCallback } from 'react'
import { getEmployeeResults } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import ResultList from '../../components/ResultList.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

export default function EmployeeResultListPage() {
  const loader = useCallback(() => getEmployeeResults(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'employee-results')

  return (
    <section aria-labelledby="employee-result-list-title">
      <p className="dashboard-eyebrow">Employee workspace</p>
      <h1 id="employee-result-list-title">Your published results</h1>
      <p className="dashboard-intro">
        Published performance results for your own assignments. The backend returns
        only results that are published and visible to you.
      </p>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {data && !error && (
        <ResultList
          results={data}
          detailBasePath="/employee/results"
          emptyMessage="You do not have any published performance results yet."
        />
      )}
    </section>
  )
}
