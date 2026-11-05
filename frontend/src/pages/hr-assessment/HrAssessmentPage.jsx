import { useCallback, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import {
  finalizeHrAssessment,
  getHrAssessment,
  getHrAssessmentStatus,
  saveHrAssessmentDraft,
} from '../../api/hrAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

function createEntries(kpis) {
  return kpis.filter((kpi) => kpi.kpiType === 'HR').map((kpi) => ({
    employeeKpiId: kpi.employeeKpiId,
    rating: kpi.hrRating == null ? '' : String(kpi.hrRating),
    comment: kpi.hrComment || '',
  }))
}

function entriesMatch(left, right) {
  return left.length === right.length
    && left.every((entry, index) => entry.employeeKpiId === right[index].employeeKpiId
      && entry.rating === right[index].rating
      && entry.comment === right[index].comment)
}

function HrAssessmentForm({ assessment, onRefresh }) {
  const initialEntries = createEntries(assessment.kpis)
  const [entries, setEntries] = useState(initialEntries)
  const [savedEntries, setSavedEntries] = useState(initialEntries)
  const [submissionStatus, setSubmissionStatus] = useState(null)
  const [isSaving, setIsSaving] = useState(false)
  const [isFinalizing, setIsFinalizing] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const saveLock = useRef(false)
  const finalizeLock = useRef(false)
  const { ask, dialog } = useConfirmation()
  const dirty = !entriesMatch(entries, savedEntries)
  const finalized = submissionStatus?.submitted || !assessment.editable
  const editable = assessment.editable && !finalized
  const ratedCount = entries.filter((entry) => entry.rating !== '').length
  const status = submissionStatus?.assignmentStatus || assessment.assignmentStatus
  const finalizedAt = submissionStatus?.hrFinalizedAt || assessment.hrFinalizedAt

  function updateEntry(employeeKpiId, field, value) {
    setEntries((current) => current.map((entry) => (
      entry.employeeKpiId === employeeKpiId ? { ...entry, [field]: value } : entry
    )))
    setErrorMessage('')
    setSuccessMessage('')
  }

  function makePayload(source = entries) {
    return source.map((entry) => ({
      employeeKpiId: entry.employeeKpiId,
      rating: entry.rating === '' ? null : Number(entry.rating),
      comment: entry.comment,
    }))
  }

  function validateRatings() {
    if (assessment.kpis.length === 0) {
      return 'No KPI snapshots are assigned to this assessment.'
    }
    if (entries.some((entry) => {
      const rating = Number(entry.rating)
      return entry.rating === '' || !Number.isInteger(rating) || rating < 1 || rating > 5
    })) {
      return 'Choose an HR rating from 1 to 5 for every HR-assessed KPI before finalizing.'
    }
    return ''
  }

  async function handleSave() {
    if (!editable || saveLock.current || finalizeLock.current) {
      return
    }
    saveLock.current = true
    setIsSaving(true)
    setErrorMessage('')
    setSuccessMessage('')
    const snapshot = entries.map((entry) => ({ ...entry }))
    try {
      await saveHrAssessmentDraft(assessment.assignmentId, makePayload(snapshot))
      setSavedEntries(snapshot)
      setSuccessMessage('HR draft saved.')
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409) {
        onRefresh()
      }
    } finally {
      saveLock.current = false
      setIsSaving(false)
    }
  }

  function handleFinalize() {
    if (!editable || finalizeLock.current || saveLock.current) {
      return
    }
    const validationMessage = validateRatings()
    if (validationMessage) {
      setErrorMessage(validationMessage)
      setSuccessMessage('')
      return
    }

    ask({
      title: 'Finalize HR assessment',
      message: `Finalize the HR assessment for ${assessment.employee.fullName} `
        + `in "${assessment.cycleName}"? This completes the assessment and `
        + 'stores the performance result for the existing workflow.',
      confirmLabel: 'Finalize assessment',
      busyLabel: 'Finalizing…',
      onConfirm: () => finalizeAssessment(),
    })
  }

  async function finalizeAssessment() {
    finalizeLock.current = true
    setIsFinalizing(true)
    setErrorMessage('')
    setSuccessMessage('')
    const snapshot = entries.map((entry) => ({ ...entry }))
    try {
      if (dirty) {
        await saveHrAssessmentDraft(assessment.assignmentId, makePayload(snapshot))
        setSavedEntries(snapshot)
      }
      const finalizedStatus = await finalizeHrAssessment(assessment.assignmentId)
      setSubmissionStatus(finalizedStatus)
      setSuccessMessage('HR assessment finalized. The assignment is complete.')
      onRefresh()
      try {
        setSubmissionStatus(await getHrAssessmentStatus(assessment.assignmentId))
      } catch (refreshError) {
        setErrorMessage(
          `Finalization succeeded, but the latest status could not be refreshed. ${getApiErrorMessage(refreshError)}`,
        )
      }
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409) {
        onRefresh()
        try {
          setSubmissionStatus(await getHrAssessmentStatus(assessment.assignmentId))
        } catch (refreshError) {
          setErrorMessage(`${getApiErrorMessage(requestError)} ${getApiErrorMessage(refreshError)}`)
        }
      }
    } finally {
      finalizeLock.current = false
      setIsFinalizing(false)
    }
  }

  return (
    <>
      {dialog}
      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {finalized && (
        <p className="management-message management-success" role="status">
          HR assessment finalized. This assignment is now read-only.
        </p>
      )}
      <div className="manager-assessment-status">
        <span>Assignment status: <strong>{formatStatus(status)}</strong></span>
        {finalizedAt && <span>HR finalized: {finalizedAt}</span>}
        <span>HR ratings completed: {ratedCount} of {entries.length}</span>
      </div>
      <p className="manager-assessment-reference-note">
        Employee self-ratings are reference only. Manager ratings are read-only; rate HR-assessed
        KPIs only. HR KPI scoring combines the manager and HR ratings.
      </p>
      {assessment.kpis.length === 0 && (
        <p className="management-empty">No KPI snapshots are assigned to this assessment.</p>
      )}
      <div className="manager-assessment-kpis">
        {assessment.kpis.map((kpi) => {
          const entry = entries.find((item) => item.employeeKpiId === kpi.employeeKpiId)
          return (
            <fieldset
              className="manager-assessment-kpi"
              key={kpi.employeeKpiId}
              disabled={!editable || isSaving || isFinalizing}
            >
              <legend>{kpi.name}</legend>
              {kpi.description && <p className="manager-assessment-description">{kpi.description}</p>}
              <dl className="manager-assessment-kpi-details">
                {kpi.category && <div><dt>Category</dt><dd>{kpi.category}</dd></div>}
                <div>
                  <dt>Assessment</dt>
                  <dd>{kpi.kpiType === 'HR' ? 'HR assessed' : 'Manager assessed'}</dd>
                </div>
                <div>
                  <dt>Weightage</dt>
                  <dd>{kpi.weightage == null ? 'Unweighted' : `${kpi.weightage}%`}</dd>
                </div>
              </dl>
              <div className="hr-assessment-comparison">
                <section aria-label={`Employee self-assessment for ${kpi.name}`}>
                  <h3>Employee self-rating (reference only)</h3>
                  <p><strong>Rating:</strong> {kpi.selfRating ?? 'Not provided'}</p>
                  <p><strong>Comment:</strong> {kpi.selfComment || 'No comment'}</p>
                </section>
                <section aria-label={`Manager assessment for ${kpi.name}`}>
                  <h3>Manager assessment (read-only)</h3>
                  <p><strong>Rating:</strong> {kpi.managerRating ?? 'Not provided'}</p>
                  <p><strong>Comment:</strong> {kpi.managerComment || 'No comment'}</p>
                </section>
                {kpi.kpiType === 'HR' && <section aria-label={`HR assessment for ${kpi.name}`}>
                  <h3>HR assessment</h3>
                  <label className="manager-assessment-field">
                    HR rating
                    <select
                      aria-label={`HR rating for ${kpi.name}`}
                      value={entry?.rating || ''}
                      onChange={(event) => updateEntry(kpi.employeeKpiId, 'rating', event.target.value)}
                    >
                      <option value="">Select a rating</option>
                      {[1, 2, 3, 4, 5].map((rating) => (
                        <option key={rating} value={rating}>{rating}</option>
                      ))}
                    </select>
                  </label>
                  <label className="manager-assessment-field">
                    HR comment (optional)
                    <textarea
                      aria-label={`HR comment for ${kpi.name}`}
                      rows="3"
                      value={entry?.comment || ''}
                      onChange={(event) => updateEntry(kpi.employeeKpiId, 'comment', event.target.value)}
                    />
                  </label>
                </section>}
              </div>
            </fieldset>
          )
        })}
      </div>
      {editable && (
        <div className="manager-assessment-actions">
          <button
            className="secondary-button"
            type="button"
            disabled={isSaving || isFinalizing || !dirty}
            onClick={handleSave}
          >
            {isSaving ? 'Saving draft…' : 'Save draft'}
          </button>
          <button
            className="primary-button"
            type="button"
            disabled={isSaving || isFinalizing}
            onClick={handleFinalize}
          >
            {isFinalizing ? 'Finalizing…' : 'Finalize assessment'}
          </button>
        </div>
      )}
    </>
  )
}

export default function HrAssessmentPage() {
  const { assignmentId } = useParams()
  const loader = useCallback(() => getHrAssessment(assignmentId), [assignmentId])
  const { data, error, isLoading, retry } = useApiResource(loader, `hr-assessment:${assignmentId}`)
  const assessment = data && String(data.assignmentId) === String(assignmentId) ? data : null
  const errorState = error?.status === 404
    ? { ...error, message: 'This assessment was not found or is not available for HR review.' }
    : error

  return (
    <section aria-labelledby="hr-assessment-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="hr-assessment-title">HR assessment</h1>
        </div>
        <Link className="secondary-button button-link" to="/hr/assessments">
          Back to HR review queue
        </Link>
      </div>
      <ManagementRequestState error={errorState} isLoading={isLoading} onRetry={retry} />
      {assessment && (
        <>
          <section className="manager-assessment-cycle" aria-label="Cycle and employee details">
            <h2>{assessment.employee.fullName}</h2>
            <dl className="cycle-details">
              {assessment.employee.employeeCode && (
                <div><dt>Employee code</dt><dd>{assessment.employee.employeeCode}</dd></div>
              )}
              {assessment.employee.department && (
                <div><dt>Department</dt><dd>{assessment.employee.department}</dd></div>
              )}
              {assessment.employee.designation && (
                <div><dt>Designation</dt><dd>{assessment.employee.designation}</dd></div>
              )}
              <div><dt>Performance cycle</dt><dd>{assessment.cycleName}</dd></div>
              <div><dt>Cycle dates</dt><dd>{assessment.startDate} – {assessment.endDate}</dd></div>
              {assessment.hrReviewDeadline && (
                <div><dt>HR review deadline</dt><dd>{assessment.hrReviewDeadline}</dd></div>
              )}
              <div><dt>Assignment status</dt><dd>{formatStatus(assessment.assignmentStatus)}</dd></div>
            </dl>
          </section>
          <HrAssessmentForm
            key={assignmentId}
            assessment={assessment}
            onRefresh={retry}
          />
        </>
      )}
    </section>
  )
}
