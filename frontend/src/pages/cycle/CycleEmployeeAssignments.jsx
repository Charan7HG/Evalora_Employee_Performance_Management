import { useCallback, useState } from 'react'
import {
  configureCycleEmployees,
  getCycleById,
  getCycleEmployees,
} from '../../api/cycleApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { listEmployees } from '../../api/employeeApi.js'
import { listManagers } from '../../api/managerApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function AssignmentList({ assignedEmployees }) {
  if (assignedEmployees.length === 0) {
    return <p className="management-empty">No employees are assigned to this cycle.</p>
  }

  return (
    <div className="staff-table-wrap">
      <table className="staff-table cycle-employee-table">
        <thead>
          <tr>
            <th scope="col">Employee</th>
            <th scope="col">Employee code</th>
            <th scope="col">Reviewing manager</th>
            <th scope="col">Assignment status</th>
          </tr>
        </thead>
        <tbody>
          {assignedEmployees.map((employee) => (
            <tr key={employee.id}>
              <td>{employee.employeeName}</td>
              <td>{employee.employeeCode}</td>
              <td>{employee.managerName || 'Not provided'}</td>
              <td>{employee.status.replaceAll('_', ' ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function CycleEmployeeAssignments({
  cycleId,
  cycleStatus,
  onCycleConflict,
  onAssignmentsChanged,
}) {
  const [manageOpen, setManageOpen] = useState(false)
  const assignedLoader = useCallback(() => getCycleEmployees(cycleId), [cycleId])
  const {
    data: assignedEmployees,
    error: assignedError,
    isLoading: assignedLoading,
    retry: retryAssigned,
  } = useApiResource(assignedLoader, `cycle-employees:${cycleId}`)

  async function refreshAfterConflict() {
    retryAssigned()
    await onCycleConflict()
  }

  return (
    <section className="cycle-kpi-section" aria-labelledby="assigned-employees-title">
      <div className="cycle-section-heading">
        <div>
          <p className="dashboard-eyebrow">Cycle configuration</p>
          <h2 id="assigned-employees-title">Assigned Employees</h2>
        </div>
        {cycleStatus === 'DRAFT' && (
          <button
            className="secondary-button"
            type="button"
            disabled={assignedLoading || Boolean(assignedError)}
            onClick={() => setManageOpen((open) => !open)}
          >
            {manageOpen ? 'Close employee manager' : 'Manage Employees'}
          </button>
        )}
        {manageOpen && cycleStatus !== 'DRAFT' && (
          <button
            className="secondary-button"
            type="button"
            onClick={() => setManageOpen(false)}
          >
            Close employee manager
          </button>
        )}
      </div>

      <ManagementRequestState
        error={assignedError}
        isLoading={assignedLoading}
        onRetry={retryAssigned}
      />
      {!assignedLoading && !assignedError && (
        <AssignmentList assignedEmployees={Array.isArray(assignedEmployees) ? assignedEmployees : []} />
      )}
      {manageOpen && Array.isArray(assignedEmployees) && (
        <CycleEmployeeEditor
          key={cycleId}
          cycleId={cycleId}
          assignedEmployees={assignedEmployees}
          cycleStatus={cycleStatus}
          onConflict={refreshAfterConflict}
          onSaved={retryAssigned}
          onAssignmentsChanged={onAssignmentsChanged}
        />
      )}
    </section>
  )
}

function CycleEmployeeEditor({
  cycleId,
  assignedEmployees,
  cycleStatus,
  onConflict,
  onSaved,
  onAssignmentsChanged,
}) {
  const choicesLoader = useCallback(async () => {
    const [employees, managers] = await Promise.all([
      listEmployees('ACTIVE'),
      listManagers('ACTIVE'),
    ])
    const activeManagers = managers.filter((manager) => (
      manager.status === 'ACTIVE' && manager.accountEnabled
    ))
    const eligibleEmployees = employees.filter((employee) => (
      employee.role === 'EMPLOYEE'
      && employee.status === 'ACTIVE'
      && employee.accountEnabled
    ))
    return { employees: eligibleEmployees, managers: activeManagers }
  }, [])
  const {
    data: choices,
    error: choicesError,
    isLoading: choicesLoading,
    retry: retryChoices,
  } = useApiResource(choicesLoader, 'eligible-cycle-employees')
  const assignedById = new Map(assignedEmployees.map((employee) => [
    String(employee.employeeId),
    employee,
  ]))
  const eligibleList = Array.isArray(choices?.employees) ? choices.employees : []
  const activeManagers = Array.isArray(choices?.managers) ? choices.managers : []
  const activeManagerById = new Map(activeManagers.map((manager) => [
    String(manager.id),
    manager,
  ]))
  const [selection, setSelection] = useState(() => Object.fromEntries(
    assignedEmployees.map((employee) => [
      String(employee.employeeId),
      {
        employeeId: employee.employeeId,
        managerId: employee.managerId == null ? '' : String(employee.managerId),
      },
    ]),
  ))
  const [search, setSearch] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const [statusConflict, setStatusConflict] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const eligibleById = new Map(eligibleList.map((employee) => [String(employee.id), employee]))
  const selectedIds = Object.keys(selection)
  const options = [
    ...assignedEmployees
      .filter((employee) => !eligibleById.has(String(employee.employeeId)))
      .map((employee) => ({
        id: employee.employeeId,
        employeeCode: employee.employeeCode,
        fullName: employee.employeeName,
        department: '',
        available: false,
      })),
    ...eligibleList.map((employee) => ({ ...employee, available: true })),
  ]
  const normalizedSearch = search.trim().toLocaleLowerCase()
  const filteredOptions = options.filter((employee) => (
    `${employee.fullName} ${employee.employeeCode} ${employee.department || ''}`
      .toLocaleLowerCase()
      .includes(normalizedSearch)
  ))
  const isEditable = cycleStatus === 'DRAFT' && !statusConflict

  function toggleEmployee(employee, checked) {
    setErrorMessage('')
    setSuccessMessage('')
    setSelection((current) => {
      const next = { ...current }
      const key = String(employee.id)
      if (checked) {
        const currentManagerId = employee.managerId == null
          ? ''
          : String(employee.managerId)
        next[key] = {
          employeeId: employee.id,
          managerId: activeManagerById.has(currentManagerId) ? currentManagerId : '',
        }
      } else {
        delete next[key]
      }
      return next
    })
  }

  function updateManager(employeeId, managerId) {
    setErrorMessage('')
    setSuccessMessage('')
    setSelection((current) => ({
      ...current,
      [String(employeeId)]: { ...current[String(employeeId)], managerId },
    }))
  }

  async function saveAssignments(event) {
    event.preventDefault()
    if (!isEditable || isSaving || choicesLoading || choicesError) {
      return
    }

    setErrorMessage('')
    setSuccessMessage('')

    if (new Set(selectedIds).size !== selectedIds.length) {
      setErrorMessage('An employee cannot be assigned more than once.')
      return
    }

    const missingManager = selectedIds.find((id) => !selection[id].managerId)
    if (missingManager) {
      setErrorMessage('Select an active reviewing manager for every selected employee.')
      return
    }

    const inactiveManager = selectedIds.find((id) => (
      !activeManagerById.has(String(selection[id].managerId))
    ))
    if (inactiveManager) {
      setErrorMessage(
        'A selected cycle manager is no longer active or enabled. Choose an active manager for that employee.',
      )
      return
    }

    setIsSaving(true)

    try {
      const currentCycle = await getCycleById(cycleId)
      if (currentCycle.status !== 'DRAFT') {
        setStatusConflict(true)
        setErrorMessage('This cycle is no longer a draft and its employee roster cannot be changed.')
        retryChoices()
        await onConflict()
        return
      }

      const employees = selectedIds.map((id) => ({
        employeeId: selection[id].employeeId,
        managerId: Number(selection[id].managerId),
      }))
      await configureCycleEmployees(cycleId, employees)
      setSuccessMessage('Cycle employee assignments saved successfully.')
      onSaved()
      // Refresh the publish summary immediately, so the Publish Cycle button
      // reflects the saved roster without a page reload.
      onAssignmentsChanged?.()
    } catch (requestError) {
      const backendMessage = requestError?.response?.data?.message
      setErrorMessage(
        requestError?.response?.status === 409
          && backendMessage === 'The selected manager is inactive.'
          ? 'A selected manager is inactive or disabled. Your roster and manager selections are preserved; choose an active manager and save again.'
          : getApiErrorMessage(requestError),
      )
      if (requestError?.response?.status === 409) {
        if (requestError.response?.data?.message === 'Only a DRAFT cycle can be modified.') {
          setStatusConflict(true)
        }
        retryChoices()
        await onConflict()
      }
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <form className="cycle-kpi-editor" onSubmit={saveAssignments} noValidate>
      <div className="cycle-kpi-editor-heading">
        <h3>Manage cycle employees</h3>
        <p>
          Saving replaces the full employee roster. Keep every employee you want
          to retain selected.
        </p>
        <p>
          Existing reviewing managers are preserved. New employees default to
          their active current manager when available; you can choose a
          different active manager for this cycle without changing reporting
          history.
        </p>
      </div>

      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {!isEditable && (
        <p className="management-message management-error" role="status">
          This cycle is no longer a draft. Assigned employees are read-only; your unsaved selection is preserved below.
        </p>
      )}

      <label className="cycle-employee-search">
        Search employees
        <input
          type="search"
          value={search}
          disabled={!isEditable || isSaving}
          onChange={(event) => setSearch(event.target.value)}
        />
      </label>

      <ManagementRequestState
        error={choicesError}
        isLoading={choicesLoading}
        onRetry={retryChoices}
      />
      {!choicesLoading && !choicesError && (
        options.length === 0
          ? <p className="management-empty">No eligible employees are available.</p>
          : filteredOptions.length === 0
            ? <p className="management-empty">No employees match your search.</p>
            : (
              <div className="cycle-employee-options">
                {filteredOptions.map((employee) => {
                  const key = String(employee.id)
                  const selected = selection[key] != null
                  const alreadyAssigned = assignedById.has(key)
                  const selectedManagerId = selected ? selection[key].managerId : ''
                  const managerUnavailable = selectedManagerId
                    && !activeManagerById.has(String(selectedManagerId))
                  const existingAssignment = assignedById.get(key)
                  return (
                    <div className="cycle-employee-option" key={key}>
                      <input
                        type="checkbox"
                        aria-label={`Select ${employee.fullName}`}
                        checked={selected}
                        disabled={!isEditable || isSaving || (!employee.available && !selected)}
                        onChange={(event) => toggleEmployee(employee, event.target.checked)}
                      />
                      <span>
                        <strong>{employee.fullName}</strong>
                        <small>{employee.employeeCode}</small>
                        {employee.department && <small>{employee.department}</small>}
                        {alreadyAssigned && <small>Already assigned</small>}
                        {!employee.available && (
                          <small className="cycle-kpi-inactive">
                            No longer eligible. Remove this employee before saving.
                          </small>
                        )}
                      </span>
                      {selected && (
                        <label className="cycle-employee-manager">
                          Reviewing manager
                          <select
                            aria-label={`Reviewing manager for ${employee.fullName}`}
                            value={selectedManagerId}
                            disabled={!isEditable || isSaving}
                            onChange={(event) => updateManager(employee.id, event.target.value)}
                          >
                            <option value="">Select an active manager</option>
                            {managerUnavailable && (
                              <option value={String(selectedManagerId)}>
                                {existingAssignment?.managerName || 'Previously assigned manager'} (inactive or unavailable)
                              </option>
                            )}
                            {activeManagers.map((manager) => (
                              <option key={manager.id} value={String(manager.id)}>
                                {manager.fullName} ({manager.employeeCode})
                              </option>
                            ))}
                          </select>
                          {managerUnavailable && (
                            <small className="field-error">
                              The existing cycle manager is not active. Choose an active manager to keep this employee assigned.
                            </small>
                          )}
                          {!selectedManagerId && (
                            <small className="field-error">
                              Select an active manager before saving.
                            </small>
                          )}
                        </label>
                      )}
                    </div>
                  )
                })}
              </div>
            )
      )}

      <p className="cycle-employee-selection-count" aria-live="polite">
        {selectedIds.length} {selectedIds.length === 1 ? 'employee' : 'employees'} selected.
      </p>
      {selectedIds.length === 0 && assignedEmployees.length > 0 && (
        <p className="field-hint">
          Saving an empty selection removes every employee from this cycle.
        </p>
      )}
      <div className="management-actions">
        <button
          className="primary-button"
          type="submit"
          disabled={!isEditable || isSaving || choicesLoading || Boolean(choicesError)}
        >
          {isSaving ? 'Saving…' : 'Save employee assignments'}
        </button>
      </div>
    </form>
  )
}
