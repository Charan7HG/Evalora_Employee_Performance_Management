import { useCallback, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  createCycle,
  getCycleById,
  updateCycle,
} from '../../api/cycleApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

const EMPTY_FORM = {
  name: '',
  description: '',
  startDate: '',
  endDate: '',
  selfAssessmentDeadline: '',
  managerReviewDeadline: '',
  hrReviewDeadline: '',
}

function validateCycle(form) {
  if (!form.name.trim()) {
    return 'Cycle name is required.'
  }
  if (form.name.trim().length > 150) {
    return 'Cycle name must be at most 150 characters.'
  }
  const dateFields = [
    ['startDate', 'Start date'],
    ['endDate', 'End date'],
    ['selfAssessmentDeadline', 'Self-assessment deadline'],
    ['managerReviewDeadline', 'Manager review deadline'],
    ['hrReviewDeadline', 'HR review deadline'],
  ]
  const missingDate = dateFields.find(([field]) => !form[field])
  if (missingDate) {
    return `${missingDate[1]} is required.`
  }
  if (form.startDate >= form.endDate) {
    return 'Start date must be before the end date.'
  }
  if (form.selfAssessmentDeadline < form.startDate
      || form.selfAssessmentDeadline > form.endDate) {
    return 'Self-assessment deadline must be within the cycle dates.'
  }
  if (form.managerReviewDeadline < form.selfAssessmentDeadline) {
    return 'Manager review deadline must be on or after the self-assessment deadline.'
  }
  if (form.hrReviewDeadline < form.managerReviewDeadline) {
    return 'HR review deadline must be on or after the manager review deadline.'
  }
  return ''
}

function makePayload(form) {
  return {
    name: form.name.trim(),
    description: form.description.trim() || null,
    startDate: form.startDate,
    endDate: form.endDate,
    selfAssessmentDeadline: form.selfAssessmentDeadline,
    managerReviewDeadline: form.managerReviewDeadline,
    hrReviewDeadline: form.hrReviewDeadline,
  }
}

function formFromCycle(cycle) {
  return {
    name: cycle.name || '',
    description: cycle.description || '',
    startDate: cycle.startDate || '',
    endDate: cycle.endDate || '',
    selfAssessmentDeadline: cycle.selfAssessmentDeadline || '',
    managerReviewDeadline: cycle.managerReviewDeadline || '',
    hrReviewDeadline: cycle.hrReviewDeadline || '',
  }
}

export default function CycleFormPage() {
  const { id } = useParams()
  const isEditing = Boolean(id)
  const loader = useCallback(
    () => (isEditing ? getCycleById(id) : Promise.resolve(null)),
    [id, isEditing],
  )
  const {
    data: cycle,
    error: loadError,
    isLoading,
    retry,
  } = useApiResource(loader, `cycle-form:${id || 'new'}`)

  if (isEditing && (isLoading || loadError || !cycle)) {
    return (
      <section aria-labelledby="cycle-form-title">
        <div className="management-page-heading">
          <div>
            <p className="dashboard-eyebrow">HR workspace</p>
            <h1 id="cycle-form-title">Edit performance cycle</h1>
          </div>
          <Link className="secondary-button button-link" to={`/hr/cycles/${id}`}>Cancel</Link>
        </div>
        <ManagementRequestState error={loadError} isLoading={isLoading} onRetry={retry} />
      </section>
    )
  }

  if (isEditing && cycle.status !== 'DRAFT') {
    return (
      <section aria-labelledby="cycle-form-title">
        <div className="management-page-heading">
          <div>
            <p className="dashboard-eyebrow">HR workspace</p>
            <h1 id="cycle-form-title">Edit performance cycle</h1>
          </div>
          <Link className="secondary-button button-link" to={`/hr/cycles/${id}`}>Back to details</Link>
        </div>
        <p className="management-message management-error" role="alert">
          Only a DRAFT cycle can be updated. This cycle is {cycle.status.replaceAll('_', ' ')}.
        </p>
      </section>
    )
  }

  return <CycleEditor key={id || 'new'} id={id} cycle={cycle} />
}

function CycleEditor({ id, cycle }) {
  const navigate = useNavigate()
  const isEditing = Boolean(id)
  const [form, setForm] = useState(() => (cycle ? formFromCycle(cycle) : EMPTY_FORM))
  const [isSaving, setIsSaving] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({ ...current, [name]: value }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')
    const validationMessage = validateCycle(form)
    if (validationMessage) {
      setErrorMessage(validationMessage)
      return
    }

    setIsSaving(true)
    try {
      const payload = makePayload(form)
      const saved = isEditing
        ? await updateCycle(id, payload)
        : await createCycle(payload)
      navigate(`/hr/cycles/${saved.id}`, {
        replace: true,
        state: { successMessage: `Performance cycle ${isEditing ? 'updated' : 'created'} successfully.` },
      })
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <section aria-labelledby="cycle-form-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="cycle-form-title">{isEditing ? 'Edit performance cycle' : 'Create performance cycle'}</h1>
        </div>
        <Link
          className="secondary-button button-link"
          to={isEditing ? `/hr/cycles/${id}` : '/hr/cycles'}
        >
          Cancel
        </Link>
      </div>

      <form className="staff-form" onSubmit={handleSubmit} noValidate>
        {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
        <div className="staff-form-grid cycle-form-grid">
          <label>
            Cycle name
            <input name="name" value={form.name} onChange={updateField} maxLength={150} required />
          </label>
          <label className="cycle-description-field">
            Description
            <textarea name="description" value={form.description} onChange={updateField} rows={4} />
          </label>
          <label>
            Start date
            <input name="startDate" type="date" value={form.startDate} onChange={updateField} required />
          </label>
          <label>
            End date
            <input name="endDate" type="date" value={form.endDate} onChange={updateField} required />
          </label>
          <label>
            Self-assessment deadline
            <input
              name="selfAssessmentDeadline"
              type="date"
              value={form.selfAssessmentDeadline}
              onChange={updateField}
              required
            />
          </label>
          <label>
            Manager review deadline
            <input
              name="managerReviewDeadline"
              type="date"
              value={form.managerReviewDeadline}
              onChange={updateField}
              required
            />
          </label>
          <label>
            HR review deadline
            <input
              name="hrReviewDeadline"
              type="date"
              value={form.hrReviewDeadline}
              onChange={updateField}
              required
            />
          </label>
        </div>
        <div className="management-actions">
          <button className="primary-button" type="submit" disabled={isSaving}>
            {isSaving ? 'Saving…' : isEditing ? 'Save changes' : 'Create cycle'}
          </button>
        </div>
      </form>
    </section>
  )
}
