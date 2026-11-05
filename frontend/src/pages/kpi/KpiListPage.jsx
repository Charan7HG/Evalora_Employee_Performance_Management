import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  deactivateKpi,
  getKpis,
  reactivateKpi,
} from '../../api/kpiApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

const ACTIVE_FILTERS = {
  all: undefined,
  active: true,
  inactive: false,
}

function formatWeightage(weightage) {
  return weightage == null ? 'Not set' : `${Number(weightage).toFixed(2)}%`
}

export default function KpiListPage() {
  const [filter, setFilter] = useState('all')
  const [search, setSearch] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [statusMessage, setStatusMessage] = useState('')
  const [statusError, setStatusError] = useState('')
  const [changingId, setChangingId] = useState(null)
  const { ask, dialog } = useConfirmation()
  const active = ACTIVE_FILTERS[filter]
  const loader = useCallback(() => getKpis(active), [active])
  const { data, error, isLoading, retry } = useApiResource(loader, `${filter}:${attempt}`)
  const kpis = Array.isArray(data) ? data : []
  const searchTerm = search.trim().toLocaleLowerCase()
  const visibleKpis = kpis.filter((kpi) => (
    [kpi.name, kpi.category]
      .filter(Boolean)
      .some((value) => value.toLocaleLowerCase().includes(searchTerm))
  ))

  function changeStatus(kpi) {
    const activating = !kpi.active
    ask({
      title: `${activating ? 'Reactivate' : 'Deactivate'} KPI`,
      message: `${activating ? 'Reactivate' : 'Deactivate'} "${kpi.name}"? This will mark the KPI ${activating ? 'active' : 'inactive'}.`,
      confirmLabel: activating ? 'Reactivate' : 'Deactivate',
      onConfirm: () => applyStatusChange(kpi, activating),
    })
  }

  async function applyStatusChange(kpi, activating) {
    const action = activating ? 'reactivate' : 'deactivate'
    setStatusMessage('')
    setStatusError('')
    setChangingId(kpi.id)
    try {
      if (activating) {
        await reactivateKpi(kpi.id)
      } else {
        await deactivateKpi(kpi.id)
      }
      setStatusMessage(`KPI "${kpi.name}" ${action}d successfully.`)
      setAttempt((current) => current + 1)
    } catch (requestError) {
      setStatusError(getApiErrorMessage(requestError))
    } finally {
      setChangingId(null)
    }
  }

  return (
    <section aria-labelledby="kpi-list-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="kpi-list-title">KPI management</h1>
        </div>
        <Link className="primary-link" to="/hr/kpis/new">Create KPI</Link>
      </div>

      <div className="kpi-list-controls">
        <label>
          Search KPIs
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search by name or category"
          />
        </label>
        <label>
          Status
          <select
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value)
              setStatusMessage('')
              setStatusError('')
            }}
          >
            <option value="all">All KPIs</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </label>
      </div>

      {statusMessage && <p className="management-message management-success" role="status">{statusMessage}</p>}
      {statusError && <p className="management-message management-error" role="alert">{statusError}</p>}
      <ManagementRequestState
        error={error}
        isLoading={isLoading}
        onRetry={retry}
      />

      {!isLoading && !error && (
        visibleKpis.length === 0
          ? (
            <p className="management-empty">
              {kpis.length === 0 ? 'No KPIs match this status filter.' : 'No KPIs match your search.'}
            </p>
          )
          : (
            <div className="staff-table-wrap">
              <table className="staff-table kpi-table">
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Category</th>
                    <th scope="col">Assessment</th>
                    <th scope="col">Description</th>
                    <th scope="col">Default weightage</th>
                    <th scope="col">Status</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleKpis.map((kpi) => (
                    <tr key={kpi.id}>
                      <td>{kpi.name}</td>
                      <td>{kpi.category || '—'}</td>
                      <td>{kpi.kpiType === 'HR' ? 'HR assessed' : 'Manager assessed'}</td>
                      <td className="kpi-description-cell">{kpi.description || '—'}</td>
                      <td>{formatWeightage(kpi.defaultWeightage)}</td>
                      <td>
                        <span className={`status-pill status-${kpi.active ? 'active' : 'inactive'}`}>
                          {kpi.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td>
                        <div className="table-actions">
                          <Link to={`/hr/kpis/${kpi.id}`}>View</Link>
                          <Link to={`/hr/kpis/${kpi.id}/edit`}>Edit</Link>
                          <button
                            type="button"
                            disabled={changingId !== null}
                            onClick={() => changeStatus(kpi)}
                          >
                            {changingId === kpi.id
                              ? 'Saving…'
                              : kpi.active ? 'Deactivate' : 'Reactivate'}
                          </button>
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
