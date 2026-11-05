import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

export default function StaffListPage({
  resource,
  title,
  listStaff,
  deactivateStaff,
  reactivateStaff,
}) {
  const [status, setStatus] = useState('ACTIVE')
  const [search, setSearch] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [statusError, setStatusError] = useState('')
  const [changingId, setChangingId] = useState(null)
  const { ask, dialog } = useConfirmation()
  const loader = useCallback(() => listStaff(status), [listStaff, status])
  const { data, error, isLoading, retry } = useApiResource(loader, `${status}:${attempt}`)
  const basePath = `/hr/${resource}`
  const entries = Array.isArray(data) ? data : []
  const searchTerm = search.trim().toLocaleLowerCase()
  const visibleEntries = entries.filter((entry) => (
    [
      entry.employeeCode,
      entry.fullName,
      entry.email,
      entry.department,
      entry.designation,
      entry.managerName,
      entry.status,
    ]
      .filter(Boolean)
      .some((value) => value.toLocaleLowerCase().includes(searchTerm))
  ))

  function changeStatus(staff) {
    const activating = staff.status === 'INACTIVE'
    const confirmation = activating
      ? `Reactivate ${staff.fullName}? This account will become active again.`
      : `Deactivate ${staff.fullName}? This account will become inactive.`
    ask({
      title: activating ? 'Reactivate account' : 'Deactivate account',
      message: confirmation,
      confirmLabel: activating ? 'Reactivate' : 'Deactivate',
      onConfirm: () => applyStatusChange(staff, activating),
    })
  }

  async function applyStatusChange(staff, activating) {
    setStatusError('')
    setChangingId(staff.id)
    try {
      if (activating) {
        await reactivateStaff(staff.id)
      } else {
        await deactivateStaff(staff.id)
      }
      setAttempt((current) => current + 1)
    } catch (requestError) {
      setStatusError(getApiErrorMessage(requestError))
    } finally {
      setChangingId(null)
    }
  }

  function retryList() {
    retry()
  }

  return (
    <section aria-labelledby="staff-list-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="staff-list-title">{title}</h1>
        </div>
        <Link className="primary-link" to={`${basePath}/new`}>Add {resource === 'employees' ? 'employee' : 'manager'}</Link>
      </div>
      <div className="staff-list-controls">
        <label>
          Search
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search name, code, email…"
          />
        </label>
        <label>
          Account status
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </label>
      </div>
      {statusError && <p className="management-message management-error" role="alert">{statusError}</p>}
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retryList} />
      {!isLoading && !error && (
        visibleEntries.length === 0
          ? <p className="management-empty">{entries.length === 0 ? `No ${status.toLowerCase()} ${resource} found.` : 'No matching records found.'}</p>
          : (
            <div className="staff-table-wrap">
              <table className="staff-table">
                <thead>
                  <tr>
                    <th scope="col">Employee code</th>
                    <th scope="col">Full name</th>
                    <th scope="col">Email</th>
                    <th scope="col">Department</th>
                    <th scope="col">Designation</th>
                    {resource === 'employees' && <th scope="col">Manager</th>}
                    <th scope="col">Status</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleEntries.map((staff) => (
                    <tr key={staff.id}>
                      <td>{staff.employeeCode}</td>
                      <td>{staff.fullName}</td>
                      <td>{staff.email}</td>
                      <td>{staff.department || '—'}</td>
                      <td>{staff.designation || '—'}</td>
                      {resource === 'employees' && <td>{staff.managerName || 'Unassigned'}</td>}
                      <td><span className={`status-pill status-${staff.status.toLowerCase()}`}>{staff.status}</span></td>
                      <td>
                        <div className="table-actions">
                          <Link to={`${basePath}/${staff.id}`}>View</Link>
                          <Link to={`${basePath}/${staff.id}/edit`}>Edit</Link>
                          <button
                            type="button"
                            disabled={changingId !== null}
                            onClick={() => changeStatus(staff)}
                          >
                            {changingId === staff.id
                              ? 'Saving…'
                              : staff.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}
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
