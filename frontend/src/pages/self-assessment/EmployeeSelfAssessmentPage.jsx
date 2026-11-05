import { useCallback, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import {
  getSelfAssessment,
  getSelfAssessmentStatus,
  saveSelfAssessmentDraft,
  submitSelfAssessment,
} from '../../api/selfAssessmentApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatStatus(value) {
  return (value || 'UNKNOWN').replaceAll('_', ' ')
}

function createEntries(kpis) {
  return kpis.map((kpi) => ({
    employeeKpiId: kpi.employeeKpiId,
    rating: kpi.rating == null ? '' : String(kpi.rating),
    comment: kpi.comment || '',
  }))
}

function AssessmentForm({ assessment, onRefresh }) {
  const [entries, setEntries] = useState(() => createEntries(assessment.kpis))
  const [isSaving, setIsSaving] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const [submissionStatus, setSubmissionStatus] = useState(null)
  const saveLock = useRef(false)
  const submitLock = useRef(false)
  const { ask, dialog } = useConfirmation()
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
    if (entries.some((entry) => {
      const rating = Number(entry.rating)
      return entry.rating === '' || !Number.isInteger(rating) || rating < 1 || rating > 5
    })) {
      return 'Choose a rating from 1 to 5 for every KPI before submitting.'
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
    try {
      await saveSelfAssessmentDraft(assessment.assignmentId, makePayload())
      setSuccessMessage('Draft saved. You can return and continue before submitting.')
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
      title: 'Submit self-assessment',
      message: `Submit your self-assessment for "${assessment.cycleName}"? `
        + 'It will be sent to your assigned manager for review, and you will not be able to edit it afterward.',
      confirmLabel: 'Submit self-assessment',
      busyLabel: 'Submitting…',
      onConfirm: submitAssessment,
    })
  }

  async function submitAssessment() {
    submitLock.current = true
    setIsSubmitting(true)
    setErrorMessage('')
    setSuccessMessage('')
    try {
      await saveSelfAssessmentDraft(assessment.assignmentId, makePayload())
      const submittedStatus = await submitSelfAssessment(assessment.assignmentId)
      setSubmissionStatus(submittedStatus)
      setSuccessMessage('Self-assessment submitted to your assigned manager.')
      onRefresh()

      try {
        setSubmissionStatus(await getSelfAssessmentStatus(assessment.assignmentId))
      } catch (refreshError) {
        setErrorMessage(
          `Your submission was received, but its latest status could not be refreshed. ${getApiErrorMessage(refreshError)}`,
        )
      }
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
      if (requestError?.response?.status === 409) {
        onRefresh()
      }
    } finally {
      submitLock.current = false
      setIsSubmitting(false)
    }
  }

  const assignmentStatus = submissionStatus?.assignmentStatus || assessment.assignmentStatus
  const submittedAt = submissionStatus?.selfSubmittedAt || assessment.selfSubmittedAt

  return (
    <>
      {dialog}
      {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {submitted && (
        <p className="management-message management-success" role="status">
          Self-assessment submitted. Your assigned manager can now review it.
        </p>
      )}
      <div className="self-assessment-status">
        <span>Assignment status: <strong>{formatStatus(assignmentStatus)}</strong></span>
        {submittedAt && <span>Submitted: {submittedAt}</span>}
        <span>Ratings completed: {ratedCount} of {assessment.kpis.length}</span>
      </div>
      <p className="self-assessment-reference-note">
        Your self-ratings are for your manager’s reference only. They do not contribute to your final performance score.
      </p>
      {assessment.kpis.length === 0 && (
        <p className="management-empty">No KPI snapshots are assigned to this assessment.</p>
      )}
      <div className="self-assessment-kpis">
        {assessment.kpis.map((kpi) => {
          const entry = entries.find((item) => item.employeeKpiId === kpi.employeeKpiId)
          return (
            <fieldset className="self-assessment-kpi" key={kpi.employeeKpiId} disabled={!editable || isSaving || isSubmitting}>
              <legend>{kpi.name}</legend>
              {kpi.description && <p className="self-assessment-description">{kpi.description}</p>}
              <dl className="self-assessment-kpi-details">
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
              <label className="self-assessment-field">
                Rating
                <select
                  aria-label={`Rating for ${kpi.name}`}
                  value={entry?.rating || ''}
                  onChange={(event) => updateEntry(kpi.employeeKpiId, 'rating', event.target.value)}
                >
                  <option value="">Select a rating</option>
                  {[1, 2, 3, 4, 5].map((rating) => (
                    <option key={rating} value={rating}>{rating}</option>
                  ))}
                </select>
              </label>
              <label className="self-assessment-field">
                Comment (optional)
                <textarea
                  aria-label={`Comment for ${kpi.name}`}
                  rows="3"
                  value={entry?.comment || ''}
                  onChange={(event) => updateEntry(kpi.employeeKpiId, 'comment', event.target.value)}
                />
              </label>
            </fieldset>
          )
        })}
      </div>
      {editable && (
        <div className="self-assessment-actions">
          <button
            className="secondary-button"
            type="button"
            disabled={isSaving || isSubmitting}
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
            {isSubmitting ? 'Submitting…' : 'Submit self-assessment'}
          </button>
        </div>
      )}
    </>
  )
}

export default function EmployeeSelfAssessmentPage() {
  const { assignmentId } = useParams()
  const loader = useCallback(() => getSelfAssessment(assignmentId), [assignmentId])
  const {
    data,
    error,
    isLoading,
    retry,
  } = useApiResource(loader, `employee-self-assessment:${assignmentId}`)
  const assessment = data && String(data.assignmentId) === String(assignmentId) ? data : null

  return (
    <section aria-labelledby="self-assessment-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">Employee workspace</p>
          <h1 id="self-assessment-title">Self-assessment</h1>
        </div>
        <Link className="secondary-button button-link" to="/employee/self-assessments">
          Back to assignments
        </Link>
      </div>
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {assessment && (
        <>
          <section className="self-assessment-cycle" aria-label="Cycle details">
            <h2>{assessment.cycleName}</h2>
            <dl className="cycle-details">
              <div><dt>Cycle status</dt><dd>{formatStatus(assessment.cycleStatus)}</dd></div>
              <div><dt>Assignment status</dt><dd>{formatStatus(assessment.assignmentStatus)}</dd></div>
              <div><dt>Cycle dates</dt><dd>{assessment.startDate} – {assessment.endDate}</dd></div>
              <div><dt>Self-assessment deadline</dt><dd>{assessment.selfAssessmentDeadline || 'Not provided'}</dd></div>
            </dl>
          </section>
          <AssessmentForm
            key={assignmentId}
            assessment={assessment}
            onRefresh={retry}
          />
        </>
      )}
    </section>
  )
}
