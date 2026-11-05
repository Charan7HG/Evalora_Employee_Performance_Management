import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getCycles } from '../../api/cycleApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function formatDate(value) {
  return value || '—'
}

function formatStatus(status = '') {
  return status.replaceAll('_', ' ')
}

export default function CycleListPage() {
  const [search, setSearch] = useState('')
  const loader = useCallback(() => getCycles(), [])
  const { data, error, isLoading, retry } = useApiResource(loader, 'cycles')
  const cycles = Array.isArray(data) ? data : []
  const searchTerm = search.trim().toLocaleLowerCase()
  const visibleCycles = cycles.filter((cycle) => (
    (cycle.name || '').toLocaleLowerCase().includes(searchTerm)
  ))

  return (
    <section aria-labelledby="cycle-list-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="cycle-list-title">Performance cycles</h1>
        </div>
        <Link className="primary-link" to="/hr/cycles/new">Create cycle</Link>
      </div>

      <div className="kpi-list-controls cycle-list-controls">
        <label>
          Search cycles
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search by cycle name"
          />
        </label>
      </div>

      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />

      {!isLoading && !error && (
        visibleCycles.length === 0
          ? (
            <p className="management-empty">
              {cycles.length === 0 ? 'No performance cycles have been created.' : 'No cycles match your search.'}
            </p>
          )
          : (
            <div className="staff-table-wrap">
              <table className="staff-table cycle-table">
                <thead>
                  <tr>
                    <th scope="col">Cycle name</th>
                    <th scope="col">Description</th>
                    <th scope="col">Start date</th>
                    <th scope="col">End date</th>
                    <th scope="col">Self-assessment deadline</th>
                    <th scope="col">Manager review deadline</th>
                    <th scope="col">HR review deadline</th>
                    <th scope="col">Status</th>
                    <th scope="col">Created</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleCycles.map((cycle) => (
                    <tr key={cycle.id}>
                      <td>{cycle.name}</td>
                      <td className="cycle-description-cell">{cycle.description || '—'}</td>
                      <td>{formatDate(cycle.startDate)}</td>
                      <td>{formatDate(cycle.endDate)}</td>
                      <td>{formatDate(cycle.selfAssessmentDeadline)}</td>
                      <td>{formatDate(cycle.managerReviewDeadline)}</td>
                      <td>{formatDate(cycle.hrReviewDeadline)}</td>
                      <td>
                        <span className={`status-pill cycle-status-${(cycle.status || '').toLowerCase()}`}>
                          {formatStatus(cycle.status)}
                        </span>
                      </td>
                      <td>{cycle.createdAt ? new Date(cycle.createdAt).toLocaleDateString() : '—'}</td>
                      <td>
                        <div className="table-actions">
                          <Link to={`/hr/cycles/${cycle.id}`}>View</Link>
                          {cycle.status === 'DRAFT' && (
                            <Link to={`/hr/cycles/${cycle.id}/edit`}>Edit</Link>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
      )}
    </section>
  )
}
