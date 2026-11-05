import { useCallback, useState } from 'react'
import {
  configureCycleKpis,
  getCycleById,
  getCycleKpis,
} from '../../api/cycleApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import { getKpis } from '../../api/kpiApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function formatWeightage(weightage) {
  return weightage == null ? 'Unweighted' : `${Number(weightage).toFixed(2)}%`
}

function initialMode(assignedKpis) {
  return assignedKpis.some((kpi) => kpi.weightage != null) ? 'weighted' : 'unweighted'
}

function centsFor(value) {
  const [whole, fraction = ''] = value.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
}

function validateWeightage(value) {
  if (!value) {
    return 'Enter a weightage for every selected KPI.'
  }
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) {
    return 'Weightage must be a number with at most 2 decimal places.'
  }
  const cents = centsFor(value)
  if (cents <= 0 || cents > 10000) {
    return 'Weightage must be greater than 0 and no greater than 100.'
  }
  return ''
}

function summarizeWeightages(assignedKpis) {
  if (assignedKpis.length === 0) {
    return 'No weightage mode configured.'
  }
  return assignedKpis.every((kpi) => kpi.weightage == null)
    ? 'Unweighted — every KPI counts equally; no weightages are needed.'
    : 'Weighted scoring — the configured weightages are applied.'
}

function AssignmentList({ assignedKpis }) {
  if (assignedKpis.length === 0) {
    return <p className="management-empty">No KPIs are assigned to this cycle.</p>
  }

  return (
    <>
      <p className="cycle-weightage-summary">{summarizeWeightages(assignedKpis)}</p>
      <div className="staff-table-wrap">
        <table className="staff-table cycle-kpi-table">
          <thead>
            <tr>
              <th scope="col">KPI</th>
              <th scope="col">Category</th>
              <th scope="col">Assessment</th>
              <th scope="col">Description</th>
              <th scope="col">Weightage</th>
            </tr>
          </thead>
          <tbody>
            {assignedKpis.map((kpi) => (
              <tr key={kpi.id}>
                <td>{kpi.name}</td>
                <td>{kpi.category || '—'}</td>
                <td>{kpi.kpiType === 'HR' ? 'HR assessed' : 'Manager assessed'}</td>
                <td className="cycle-kpi-description">{kpi.description || '—'}</td>
                <td>{formatWeightage(kpi.weightage)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

export default function CycleKpiAssignments({
  cycleId,
  cycleStatus,
  onCycleConflict,
  onAssignmentsChanged,
}) {
  const [manageOpen, setManageOpen] = useState(false)
  const assignedLoader = useCallback(() => getCycleKpis(cycleId), [cycleId])
  const {
    data: assignedKpis,
    error: assignedError,
    isLoading: assignedLoading,
    retry: retryAssigned,
  } = useApiResource(assignedLoader, `cycle-kpis:${cycleId}`)

  async function handleConflict() {
    retryAssigned()
    await onCycleConflict()
  }

  return (
    <section className="cycle-kpi-section" aria-labelledby="assigned-kpis-title">
      <div className="cycle-section-heading">
        <div>
          <p className="dashboard-eyebrow">Cycle configuration</p>
          <h2 id="assigned-kpis-title">Assigned KPIs</h2>
        </div>
        {cycleStatus === 'DRAFT' && (
          <button
            className="secondary-button"
            type="button"
            disabled={assignedLoading || Boolean(assignedError)}
            onClick={() => setManageOpen((open) => !open)}
          >
            {manageOpen ? 'Close KPI manager' : 'Manage KPIs'}
          </button>
        )}
        {manageOpen && cycleStatus !== 'DRAFT' && (
          <button
            className="secondary-button"
            type="button"
            onClick={() => setManageOpen(false)}
          >
            Close KPI manager
          </button>
        )}
      </div>

      <ManagementRequestState
        error={assignedError}
        isLoading={assignedLoading}
        onRetry={retryAssigned}
      />
      {!assignedLoading && !assignedError && (
        <AssignmentList assignedKpis={Array.isArray(assignedKpis) ? assignedKpis : []} />
      )}
      {manageOpen && Array.isArray(assignedKpis) && (
        <CycleKpiEditor
          key={cycleId}
          cycleId={cycleId}
          assignedKpis={Array.isArray(assignedKpis) ? assignedKpis : []}
          isEditable={cycleStatus === 'DRAFT'}
          onConflict={handleConflict}
          onSaved={retryAssigned}
          onAssignmentsChanged={onAssignmentsChanged}
        />
      )}
    </section>
  )
}

function CycleKpiEditor({
  cycleId,
  assignedKpis,
  isEditable,
  onConflict,
  onSaved,
  onAssignmentsChanged,
}) {
  const activeKpiLoader = useCallback(() => getKpis(true), [])
  const {
    data: activeKpis,
    error: activeKpiError,
    isLoading: activeKpiLoading,
    retry: retryActiveKpis,
  } = useApiResource(activeKpiLoader, 'active-kpis-for-cycle')
  const initialSelection = Object.fromEntries(assignedKpis.map((kpi) => [
    String(kpi.kpiId),
    {
      kpiId: kpi.kpiId,
      weightage: kpi.weightage == null ? '' : String(kpi.weightage),
    },
  ]))
  const [selection, setSelection] = useState(initialSelection)
  const [mode, setMode] = useState(() => initialMode(assignedKpis))
  const [isSaving, setIsSaving] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const availableKpis = Array.isArray(activeKpis) ? activeKpis : []
  const assignedByKpiId = new Map(assignedKpis.map((kpi) => [String(kpi.kpiId), kpi]))
  const availableByKpiId = new Map(availableKpis.map((kpi) => [String(kpi.id), kpi]))
  const options = [
    ...assignedKpis
      .filter((kpi) => !availableByKpiId.has(String(kpi.kpiId)))
      .map((kpi) => ({
        id: kpi.kpiId,
        name: kpi.name,
        category: kpi.category,
        active: false,
        description: kpi.description,
      })),
    ...availableKpis,
  ]
  const selectedIds = Object.keys(selection)
  const weightageErrors = mode === 'weighted'
    ? Object.fromEntries(selectedIds.map((id) => [id, validateWeightage(selection[id].weightage)]))
    : {}
  const allWeightagesValid = Object.values(weightageErrors).every((message) => !message)
  const weightageTotal = selectedIds.reduce(
    (total, id) => total + (selection[id].weightage && !weightageErrors[id]
      ? centsFor(selection[id].weightage)
      : 0),
    0,
  )
  const remainingCents = 10000 - weightageTotal

  function toggleKpi(kpi, checked) {
    setErrorMessage('')
    setSuccessMessage('')
    setSelection((current) => {
      const next = { ...current }
      const key = String(kpi.id)
      if (checked) {
        next[key] = {
          kpiId: kpi.id,
          weightage: mode === 'weighted' && assignedByKpiId.has(key)
            ? String(assignedByKpiId.get(key).weightage ?? '')
            : '',
        }
      } else {
        delete next[key]
      }
      return next
    })
  }

  function updateWeightage(kpiId, weightage) {
    setErrorMessage('')
    setSuccessMessage('')
    setSelection((current) => ({
      ...current,
      [String(kpiId)]: { ...current[String(kpiId)], weightage },
    }))
  }

  function switchMode(nextMode) {
    setMode(nextMode)
    setErrorMessage('')
    setSuccessMessage('')
    setSelection((current) => Object.fromEntries(
      Object.entries(current).map(([id, selected]) => [id, { ...selected, weightage: '' }]),
    ))
  }

  async function saveAssignments(event) {
    event.preventDefault()
    if (!isEditable) {
      return
    }
    setErrorMessage('')
    setSuccessMessage('')

    if (selectedIds.length === 0) {
      setErrorMessage('Select at least one KPI before saving.')
      return
    }

    const inactiveSelection = selectedIds.find((id) => !availableByKpiId.has(id))
    if (inactiveSelection) {
      setErrorMessage('Remove currently assigned KPIs that are no longer active before saving.')
      return
    }

    if (mode === 'weighted') {
      if (!allWeightagesValid) {
        setErrorMessage(Object.values(weightageErrors).find(Boolean))
        return
      }
      if (weightageTotal !== 10000) {
        setErrorMessage(`Weightages must total exactly 100%. Current total: ${(weightageTotal / 100).toFixed(2)}%.`)
        return
      }
    }

    setIsSaving(true)
    try {
      const currentCycle = await getCycleById(cycleId)
      if (currentCycle.status !== 'DRAFT') {
        setErrorMessage('This cycle is no longer a draft and cannot be changed.')
        await onConflict()
        return
      }

      const kpis = selectedIds.map((id) => ({
        kpiId: selection[id].kpiId,
        weightage: mode === 'weighted' ? Number(selection[id].weightage) : null,
      }))
      await configureCycleKpis(cycleId, kpis)
      setSuccessMessage('Cycle KPI assignments saved successfully.')
      onSaved()
      // Refresh the publish summary immediately, so the Publish Cycle button
      // reflects the saved configuration without a page reload.
      onAssignmentsChanged?.()
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409) {
        await onConflict()
      }
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <form className="cycle-kpi-editor" onSubmit={saveAssignments} noValidate>
      <div className="cycle-kpi-editor-heading">
        <h3>Manage cycle KPIs</h3>
        <p>Choose active KPIs. Saving replaces the cycle&apos;s full KPI selection.</p>
      </div>

      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {!isEditable && (
        <p className="management-message management-error" role="status">
          This cycle is no longer a draft. Assigned KPIs are read-only; your unsaved selection is preserved below.
        </p>
      )}

      <fieldset className="cycle-kpi-mode" disabled={!isEditable || isSaving}>
        <legend>Scoring weightage</legend>
        <label>
          <input
            type="radio"
            name="weightageMode"
            value="weighted"
            checked={mode === 'weighted'}
            onChange={() => switchMode('weighted')}
            disabled={!isEditable || isSaving}
          />
          Configure weightages
        </label>
        <label>
          <input
            type="radio"
            name="weightageMode"
            value="unweighted"
            checked={mode === 'unweighted'}
            onChange={() => switchMode('unweighted')}
            disabled={isSaving}
          />
          Use equal/unweighted scoring
        </label>
        <p className="field-hint">Switching modes clears all weightage inputs.</p>
        {mode === 'unweighted' && (
          <p className="field-hint cycle-unweighted-note">
            Equal/unweighted scoring averages every KPI at an equal share: each
            rating counts the same, so no weightages are required and none need
            to add up to 100%. Manager-assessed KPIs are still averaged into the
            75% manager category and HR-assessed KPIs into the 25% HR category.
          </p>
        )}
      </fieldset>

      <ManagementRequestState
        error={activeKpiError}
        isLoading={activeKpiLoading}
        onRetry={retryActiveKpis}
      />
      {!activeKpiLoading && !activeKpiError && (
        options.length === 0
          ? <p className="management-empty">No active KPIs are available to assign.</p>
          : (
            <div className="cycle-kpi-options">
              {options.map((kpi) => {
                const key = String(kpi.id)
                const selected = selection[key] != null
                const isActive = availableByKpiId.has(key)
                return (
                  <div className="cycle-kpi-option" key={key}>
                    <label className="cycle-kpi-choice">
                      <input
                        type="checkbox"
                        checked={selected}
                        disabled={!isEditable || isSaving}
                        onChange={(event) => toggleKpi(kpi, event.target.checked)}
                      />
                      <span>
                        <strong>{kpi.name}</strong>
                        <small>{kpi.category || 'No category'}</small>
                        <small>{kpi.kpiType === 'HR' ? 'HR assessed' : 'Manager assessed'}</small>
                        {assignedByKpiId.has(key) && isActive && <small>Already assigned</small>}
                        {!isActive && <small className="cycle-kpi-inactive">Currently assigned; no longer active. Remove to update.</small>}
                      </span>
                    </label>
                    {selected && mode === 'weighted' && (
                      <label className="cycle-weightage-field">
                        Weightage (%)
                        <input
                          type="text"
                          inputMode="decimal"
                          value={selection[key].weightage}
                          aria-label={`${kpi.name} weightage`}
                          aria-invalid={Boolean(weightageErrors[key])}
                          disabled={!isEditable || isSaving || !isActive}
                          onChange={(event) => updateWeightage(kpi.id, event.target.value)}
                        />
                        {weightageErrors[key] && <small className="field-error">{weightageErrors[key]}</small>}
                      </label>
                    )}
                  </div>
                )
              })}
            </div>
          )
      )}

      {mode === 'weighted' && selectedIds.length > 0 && (
        <div className="cycle-weightage-total" aria-live="polite">
          <span>Total: {(weightageTotal / 100).toFixed(2)}%</span>
          <span>Remaining: {(remainingCents / 100).toFixed(2)}%</span>
          <strong className={weightageTotal === 10000 ? 'valid-total' : 'invalid-total'}>
            {weightageTotal === 10000 ? 'Exactly 100%' : 'Total must equal 100%'}
          </strong>
        </div>
      )}
      <div className="management-actions">
        <button
          className="primary-button"
          type="submit"
          disabled={!isEditable || isSaving || activeKpiLoading || Boolean(activeKpiError)}
        >
          {isSaving ? 'Saving…' : 'Save KPI assignments'}
        </button>
      </div>
    </form>
  )
}
