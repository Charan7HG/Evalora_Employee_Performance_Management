import { useCallback, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

const DISPLAY_FIELDS = [
  ['Employee code', 'employeeCode'],
  ['Full name', 'fullName'],
  ['Email', 'email'],
  ['Department', 'department'],
  ['Designation', 'designation'],
  ['Joining date', 'joiningDate'],
  ['Account status', 'status'],
  ['Login enabled', 'accountEnabled'],
  ['Profile created', 'createdAt'],
  ['Profile updated', 'updatedAt'],
]

function ManagerEditor({
  employeeId,
  employee,
  listManagers,
  assignStaffManager,
  onSaved,
  onClose,
}) {
  const loader = useCallback(() => listManagers('ACTIVE'), [listManagers])
  const { data, error, isLoading, retry } = useApiResource(
    loader,
    `active-managers:${employee.id}`,
  )
  const currentManagerId = employee.managerId == null ? '' : String(employee.managerId)
  const [managerId, setManagerId] = useState(currentManagerId)
  const [errorMessage, setErrorMessage] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const saveLock = useRef(false)
  const { ask, dialog } = useConfirmation()
  const managers = (Array.isArray(data) ? data : []).filter((manager) => (
    manager.role === 'MANAGER'
    && manager.status === 'ACTIVE'
    && manager.accountEnabled
    && String(manager.id) !== String(employee.id)
  ))
  const managerById = new Map(managers.map((manager) => [String(manager.id), manager]))
  const currentManagerUnavailable = currentManagerId !== ''
    && !managerById.has(currentManagerId)
  const isUnchanged = managerId !== '' && managerId === currentManagerId
  const canSubmit = !isLoading && !error && !isSaving
    && managerId !== '' && !isUnchanged

  function handleSubmit(event) {
    event.preventDefault()
    if (!canSubmit || saveLock.current) {
      return
    }

    const nextManager = managerById.get(managerId)
    if (!nextManager) {
      setErrorMessage('Select an active manager.')
      return
    }
    ask({
      title: 'Change reporting manager',
      message: `Change ${employee.fullName}'s reporting manager from `
        + `${employee.managerName || 'no manager'} to ${nextManager.fullName}?`,
      confirmLabel: 'Save manager',
      onConfirm: () => saveManager(nextManager),
    })
  }

  async function saveManager(nextManager) {
    saveLock.current = true
    setIsSaving(true)
    setErrorMessage('')
    try {
      await assignStaffManager(employeeId, Number(managerId))
      onSaved(nextManager)
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    } finally {
      saveLock.current = false
      setIsSaving(false)
    }
  }

  return (
    <form className="staff-form" onSubmit={handleSubmit} noValidate>
      {dialog}
      {errorMessage && (
        <p className="management-message management-error" role="alert">{errorMessage}</p>
      )}
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {!isLoading && !error && (
        <div className="staff-form-grid">
          <label>
            New reporting manager
            <select
              aria-label="New reporting manager"
              value={managerId}
              onChange={(event) => {
                setManagerId(event.target.value)
                setErrorMessage('')
              }}
            >
              <option value="">Select an active manager</option>
              {currentManagerUnavailable && (
                <option value={currentManagerId}>
                  {employee.managerName || 'Current manager'} (inactive or unavailable)
                </option>
              )}
              {managers.map((manager) => (
                <option key={manager.id} value={String(manager.id)}>
                  {manager.fullName} ({manager.employeeCode})
                </option>
              ))}
            </select>
            <span className="field-hint">
              Only active manager accounts with enabled logins can be assigned.
            </span>
            {isUnchanged && (
              <span className="field-hint">Select a different manager to reassign.</span>
            )}
            {managers.length === 0 && (
              <span className="field-hint">No active managers are available.</span>
            )}
          </label>
        </div>
      )}
      <div className="management-actions">
        <button className="primary-button" type="submit" disabled={!canSubmit}>
          {isSaving ? 'Saving…' : 'Save reporting manager'}
        </button>
        <button className="secondary-button" type="button" onClick={onClose}>
          Cancel
        </button>
      </div>
    </form>
  )
}

export default function StaffDetailPage({
  resource,
  title,
  getStaff,
  deactivateStaff,
  reactivateStaff,
  assignStaffManager,
  listManagers,
}) {
  const { id } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const [attempt, setAttempt] = useState(0)
  const [actionError, setActionError] = useState('')
  const [actionSuccess, setActionSuccess] = useState('')
  const [isChangingStatus, setIsChangingStatus] = useState(false)
  const [managerOpen, setManagerOpen] = useState(false)
  const { ask, dialog } = useConfirmation()
  const loader = useCallback(() => getStaff(id), [getStaff, id])
  const { data: staff, error, isLoading, retry } = useApiResource(loader, `${id}:${attempt}`)
  const basePath = `/hr/${resource}`
  const canReassignManager = resource === 'employees'
    && typeof assignStaffManager === 'function'
    && typeof listManagers === 'function'
    && staff?.status === 'ACTIVE'

  function changeStatus() {
    if (!staff) {
      return
    }
    const activating = staff.status === 'INACTIVE'
    const message = activating
      ? `Reactivate ${staff.fullName}? This account will become active again.`
      : `Deactivate ${staff.fullName}? This account will become inactive.`
    ask({
      title: activating ? 'Reactivate account' : 'Deactivate account',
      message,
      confirmLabel: activating ? 'Reactivate' : 'Deactivate',
      onConfirm: () => applyStatusChange(activating),
    })
  }

  async function applyStatusChange(activating) {
    setActionError('')
    setActionSuccess('')
    setIsChangingStatus(true)
    try {
      if (activating) {
        await reactivateStaff(id)
      } else {
        await deactivateStaff(id)
      }
      setManagerOpen(false)
      setAttempt((current) => current + 1)
    } catch (requestError) {
      setActionError(getApiErrorMessage(requestError))
    } finally {
      setIsChangingStatus(false)
    }
  }

  function handleManagerSaved(nextManager) {
    setManagerOpen(false)
    setActionError('')
    setActionSuccess(
      `Reporting manager for ${staff.fullName} updated to ${nextManager.fullName}.`,
    )
    setAttempt((current) => current + 1)
  }

  return (
    <section aria-labelledby="staff-detail-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="staff-detail-title">{title} details</h1>
        </div>
        <Link className="secondary-button button-link" to={basePath}>Back to {title.toLowerCase()}</Link>
      </div>
      {location.state?.successMessage && (
        <p className="management-message management-success" role="status">
          {location.state.successMessage}
        </p>
      )}
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {actionError && <p className="management-message management-error" role="alert">{actionError}</p>}
      {actionSuccess && <p className="management-message management-success" role="status">{actionSuccess}</p>}
      {staff && !error && !isLoading && (
        <>
          <dl className="staff-details">
            {DISPLAY_FIELDS.map(([label, field]) => {
              const value = staff[field]
              return (
                <div key={field}>
                  <dt>{label}</dt>
                  <dd>{typeof value === 'boolean' ? value ? 'Yes' : 'No' : value || 'Not provided'}</dd>
                </div>
              )
            })}
          </dl>
          {resource === 'employees' && typeof assignStaffManager === 'function' && (
            <section aria-labelledby="employee-manager-title">
              <div className="cycle-section-heading">
                <div>
                  <h2 id="employee-manager-title">Reporting manager</h2>
                </div>
                {!managerOpen && (
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={!canReassignManager || isChangingStatus}
                    onClick={() => {
                      setActionError('')
                      setActionSuccess('')
                      setManagerOpen(true)
                    }}
                  >
                    Change manager
                  </button>
                )}
              </div>
              <dl className="staff-details">
                <div>
                  <dt>Current reporting manager</dt>
                  <dd>{staff.managerName || 'No manager assigned'}</dd>
                </div>
              </dl>
              {!canReassignManager && (
                <p className="field-hint">
                  Only an active employee can be assigned a reporting manager. Reactivate this
                  account first.
                </p>
              )}
              {managerOpen && canReassignManager && (
                <ManagerEditor
                  key={staff.managerId ?? 'none'}
                  employeeId={id}
                  employee={staff}
                  listManagers={listManagers}
                  assignStaffManager={assignStaffManager}
                  onSaved={handleManagerSaved}
                  onClose={() => setManagerOpen(false)}
                />
              )}
            </section>
          )}
          <div className="management-actions">
            <Link className="primary-link" to={`${basePath}/${id}/edit`}>Edit profile</Link>
            <button
              className="secondary-button"
              type="button"
              disabled={isChangingStatus}
              onClick={changeStatus}
            >
              {isChangingStatus
                ? 'Saving…'
                : staff.status === 'ACTIVE' ? 'Deactivate account' : 'Reactivate account'}
            </button>
            <button
              className="text-button"
              type="button"
              onClick={() => navigate(basePath)}
            >
              Return to list
            </button>
          </div>
        </>
      )}
    </section>
  )
}
