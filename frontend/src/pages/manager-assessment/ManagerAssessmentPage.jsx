import { useCallback, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import {
  getManagerAssessment,
  getManagerAssessmentStatus,
  saveManagerAssessmentDraft,
  submitManagerAssessment,
} from '../../api/managerAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

/**
 * One editable row per assigned KPI. HR-assessed KPIs retain the manager's
 * initial rating separately from HR's later rating.
 */
function createEntries(kpis) {
  return kpis.map((kpi) => ({
      employeeKpiId: kpi.employeeKpiId,
      rating: kpi.managerRating == null ? '' : String(kpi.managerRating),
      comment: kpi.managerComment || '',
    }))
}

function entriesMatch(left, right) {
  return left.length === right.length
    && left.every((entry, index) => entry.employeeKpiId === right[index].employeeKpiId
      && entry.rating === right[index].rating
      && entry.comment === right[index].comment)
}

function ManagerAssessmentForm({ assessment, onRefresh }) {
  const initialEntries = createEntries(assessment.kpis)
  const [entries, setEntries] = useState(initialEntries)
  const [savedEntries, setSavedEntries] = useState(initialEntries)
  const [submissionStatus, setSubmissionStatus] = useState(null)
  const [isSaving, setIsSaving] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const saveLock = useRef(false)
  const submitLock = useRef(false)
  const { ask, dialog } = useConfirmation()
  const dirty = !entriesMatch(entries, savedEntries)
  const submitted = submissionStatus?.submitted || !assessment.editable
  const editable = assessment.editable && !submitted
  const ratedCount = entries.filter((entry) => entry.rating !== '').length

  function updateEntry(employeeKpiId, field, value) {
    setEntries((current) => current.map((entry) => (
      entry.employeeKpiId === employeeKpiId ? { ...entry, [field]: value } : entry
    )))
    setErrorMessage('')
    setSuccessMessage('')
  }

  function makePayload() {
    return entries.map((entry) => ({
      employeeKpiId: entry.employeeKpiId,
      rating: entry.rating === '' ? null : Number(entry.rating),
      comment: entry.comment,
    }))
  }

  function validateRatings() {
    if (assessment.kpis.length === 0) {
      return 'No KPI snapshots are assigned to this assessment.'
    }
    if (entries.length === 0) {
      return 'No KPIs are assigned to this assessment.'
    }
    if (entries.some((entry) => {
      const rating = Number(entry.rating)
      return entry.rating === '' || !Number.isInteger(rating) || rating < 1 || rating > 5
    })) {
      return 'Choose a manager rating from 1 to 5 for every assigned KPI before submitting.'
    }
    return ''
  }

  async function handleSave() {
    if (!editable || saveLock.current || submitLock.current) {
      return
    }
    saveLock.current = true
    setIsSaving(true)
    setErrorMessage('')
    setSuccessMessage('')
    const payload = makePayload()
    const savedSnapshot = entries.map((entry) => ({ ...entry }))
    try {
      await saveManagerAssessmentDraft(assessment.assignmentId, payload)
      setSavedEntries(savedSnapshot)
      setSuccessMessage('Manager draft saved.')
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

  function handleSubmit() {
    if (!editable || submitLock.current || saveLock.current) {
      return
    }
    const validationMessage = validateRatings()
    if (validationMessage) {
      setErrorMessage(validationMessage)
      setSuccessMessage('')
      return
    }

    ask({
      title: 'Submit manager assessment',
      message: `Submit the manager assessment for ${assessment.employee.fullName} `
        + `in "${assessment.cycleName}"? It will be sent to HR for review, `
        + 'and you will not be able to edit it afterward.',
      confirmLabel: 'Submit to HR',
      busyLabel: 'Submitting…',
      onConfirm: () => submitReview(),
    })
  }

  /**
   * Submits the review directly. If the ratings have not been saved as a draft
   * yet, the current values are written first in the same action, so the manager
   * never has to click "Save draft" before submitting.
   */
  async function submitReview() {
    submitLock.current = true
    setIsSubmitting(true)
    setErrorMessage('')
    setSuccessMessage('')
    const snapshot = entries.map((entry) => ({ ...entry }))
    try {
      if (dirty) {
        await saveManagerAssessmentDraft(assessment.assignmentId, makePayload())
        setSavedEntries(snapshot)
      }
      const status = await submitManagerAssessment(assessment.assignmentId)
      setSubmissionStatus(status)
      setSuccessMessage('Manager assessment submitted to HR for review.')
      onRefresh()
      try {
        setSubmissionStatus(await getManagerAssessmentStatus(assessment.assignmentId))
      } catch (refreshError) {
        setErrorMessage(
          `Your submission was received, but its latest status could not be refreshed. ${getApiErrorMessage(refreshError)}`,
        )
      }
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409) {
        onRefresh()
        try {
          setSubmissionStatus(await getManagerAssessmentStatus(assessment.assignmentId))
        } catch (refreshError) {
          setErrorMessage(`${getApiErrorMessage(requestError)} ${getApiErrorMessage(refreshError)}`)
        }
      }
    } finally {
      submitLock.current = false
      setIsSubmitting(false)
    }
  }

  const currentStatus = submissionStatus?.assignmentStatus || assessment.assignmentStatus
  const managerSubmittedAt = submissionStatus?.managerSubmittedAt || assessment.managerSubmittedAt

  return (
    <>
      {dialog}
      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {submitted && (
        <p className="management-message management-success" role="status">
          Manager assessment submitted. This assignment is now read-only.
        </p>
      )}
      <div className="manager-assessment-status">
        <span>Assignment status: <strong>{formatStatus(currentStatus)}</strong></span>
        {managerSubmittedAt && <span>Manager submission: {managerSubmittedAt}</span>}
        <span>Manager ratings completed: {ratedCount} of {entries.length}</span>
      </div>
      <p className="manager-assessment-reference-note">
        Employee self-ratings and comments are shown for reference only and are read-only.
        Self-ratings are reference only. Your ratings are stored separately from HR ratings; HR
        will add a separate rating to each HR-assessed KPI.
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
              disabled={!editable || isSaving || isSubmitting}
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
              <div className="manager-assessment-comparison">
                <section aria-label={`Employee self-assessment for ${kpi.name}`}>
                  <h3>Employee self-rating (reference only)</h3>
                  <p><strong>Rating:</strong> {kpi.selfRating ?? 'Not provided'}</p>
                  <p><strong>Comment:</strong> {kpi.selfComment || 'No comment'}</p>
                </section>
                <section aria-label={`Manager assessment for ${kpi.name}`}>
                  <h3>Manager assessment</h3>
                  <label className="manager-assessment-field">
                    Manager rating
                    <select
                      aria-label={`Manager rating for ${kpi.name}`}
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
                    Manager comment (optional)
                    <textarea
                      aria-label={`Manager comment for ${kpi.name}`}
                      rows="3"
                      value={entry?.comment || ''}
                      onChange={(event) => updateEntry(kpi.employeeKpiId, 'comment', event.target.value)}
                    />
                  </label>
                </section>
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
            disabled={isSaving || isSubmitting || !dirty}
            onClick={handleSave}
          >
            {isSaving ? 'Saving draft…' : 'Save draft'}
          </button>
          <button
            className="primary-button"
            type="button"
            disabled={isSaving || isSubmitting}
            onClick={handleSubmit}
          >
            {isSubmitting ? 'Submitting…' : 'Submit assessment to HR'}
          </button>
        </div>
      )}
    </>
  )
}

export default function ManagerAssessmentPage() {
  const { assignmentId } = useParams()
  const loader = useCallback(() => getManagerAssessment(assignmentId), [assignmentId])
  const { data, error, isLoading, retry } = useApiResource(
    loader,
    `manager-assessment:${assignmentId}`,
  )
  const assessment = data && String(data.assignmentId) === String(assignmentId) ? data : null

  return (
    <section aria-labelledby="manager-assessment-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">Manager workspace</p>
          <h1 id="manager-assessment-title">Manager assessment</h1>
        </div>
        <Link className="secondary-button button-link" to="/manager/assessments">
          Back to review queue
        </Link>
      </div>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
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
              <div><dt>Performance cycle</dt><dd>{assessment.cycleName}</dd></div>
              <div><dt>Cycle dates</dt><dd>{assessment.startDate} – {assessment.endDate}</dd></div>
              <div><dt>Manager review deadline</dt><dd>{assessment.managerReviewDeadline || 'Not provided'}</dd></div>
              <div><dt>Employee submitted</dt><dd>{assessment.selfSubmittedAt || 'Not provided'}</dd></div>
            </dl>
          </section>
          <ManagerAssessmentForm
            key={assignmentId}
            assessment={assessment}
            onRefresh={retry}
          />
        </>
      )}
    </section>
  )
}
