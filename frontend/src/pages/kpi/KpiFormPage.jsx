import { useCallback, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  createKpi,
  getKpiById,
  updateKpi,
} from '../../api/kpiApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

const EMPTY_FORM = {
  name: '',
  description: '',
  category: '',
  defaultWeightage: '',
  kpiType: 'MANAGER',
}

function validateKpi(form) {
  const name = form.name.trim()
  const category = form.category.trim()
  const weightage = form.defaultWeightage.trim()

  if (!name) {
    return 'KPI name is required.'
  }
  if (name.length > 150) {
    return 'KPI name must be at most 150 characters.'
  }
  if (category.length > 80) {
    return 'Category must be at most 80 characters.'
  }
  if (weightage) {
    if (!/^\d+(?:\.\d{1,2})?$/.test(weightage)) {
      return 'Default weightage must be a number with at most 2 decimal places.'
    }
    const parsedWeightage = Number(weightage)
    if (parsedWeightage <= 0 || parsedWeightage > 100) {
      return 'Default weightage must be greater than 0 and at most 100.'
    }
  }
  return ''
}

function makePayload(form) {
  const weightage = form.defaultWeightage.trim()
  return {
    name: form.name.trim(),
    description: form.description.trim() || null,
    category: form.category.trim() || null,
    defaultWeightage: weightage ? Number(weightage) : null,
    kpiType: form.kpiType,
  }
}

export default function KpiFormPage() {
  const { id } = useParams()
  const isEditing = Boolean(id)
  const loader = useCallback(
    () => (isEditing ? getKpiById(id) : Promise.resolve(null)),
    [id, isEditing],
  )
  const {
    data: kpi,
    error: loadError,
    isLoading,
    retry,
  } = useApiResource(loader, `kpi-form:${id || 'new'}`)

  if (isEditing && (isLoading || loadError || !kpi)) {
    return (
      <section aria-labelledby="kpi-form-title">
        <div className="management-page-heading">
          <div>
            <p className="dashboard-eyebrow">HR workspace</p>
            <h1 id="kpi-form-title">Edit KPI</h1>
          </div>
          <Link className="secondary-button button-link" to={`/hr/kpis/${id}`}>
            Cancel
          </Link>
        </div>
        <ManagementRequestState error={loadError} isLoading={isLoading} onRetry={retry} />
      </section>
    )
  }

  return <KpiEditor key={id || 'new'} id={id} kpi={kpi} />
}

function KpiEditor({ id, kpi }) {
  const navigate = useNavigate()
  const isEditing = Boolean(id)
  const [form, setForm] = useState(() => (kpi ? {
    name: kpi.name || '',
    description: kpi.description || '',
    category: kpi.category || '',
    defaultWeightage: kpi.defaultWeightage == null ? '' : String(kpi.defaultWeightage),
    kpiType: kpi.kpiType === 'HR' ? 'HR' : 'MANAGER',
  } : EMPTY_FORM))
  const [isSaving, setIsSaving] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({ ...current, [name]: value }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')
    const validationMessage = validateKpi(form)
    if (validationMessage) {
      setErrorMessage(validationMessage)
      return
    }

    setIsSaving(true)
    try {
      const payload = makePayload(form)
      const saved = isEditing
        ? await updateKpi(id, payload)
        : await createKpi(payload)
      navigate(isEditing ? `/hr/kpis/${id}` : `/hr/kpis/${saved.id}`, {
        replace: true,
        state: { successMessage: `KPI ${isEditing ? 'updated' : 'created'} successfully.` },
      })
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <section aria-labelledby="kpi-form-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="kpi-form-title">{isEditing ? 'Edit KPI' : 'Create KPI'}</h1>
        </div>
        <Link className="secondary-button button-link" to={isEditing ? `/hr/kpis/${id}` : '/hr/kpis'}>
          Cancel
        </Link>
      </div>

      <form className="staff-form kpi-form" onSubmit={handleSubmit} noValidate>
        {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
        <div className="kpi-form-fields">
          <label>
            KPI name <span aria-hidden="true">*</span>
            <input
              name="name"
              value={form.name}
              onChange={updateField}
              maxLength={150}
              required
            />
          </label>
          <label>
            Category
            <input
              name="category"
              value={form.category}
              onChange={updateField}
              maxLength={80}
            />
          </label>
          <label>
            Assessment type
            <select name="kpiType" value={form.kpiType} onChange={updateField}>
              <option value="MANAGER">Manager-assessed KPI — scored in the 75% manager category</option>
              <option value="HR">HR-assessed KPI — scored in the 25% HR category</option>
            </select>
            <span className="field-hint">
              Managers rate every assigned KPI. HR adds a separate rating for
              HR-assessed KPIs only. Manager KPIs contribute to the 75% manager
              category; HR KPIs combine the manager and HR ratings in the 25% HR
              category. Employee self-ratings are not scored.
            </span>
          </label>
          <label>
            Description
            <textarea
              name="description"
              value={form.description}
              onChange={updateField}
              rows={5}
            />
          </label>
          <label>
            Default weightage (optional)
            <div className="weightage-input">
              <input
                name="defaultWeightage"
                type="number"
                min="0.01"
                max="100"
                step="0.01"
                value={form.defaultWeightage}
                onChange={updateField}
                inputMode="decimal"
              />
              <span>%</span>
            </div>
            <span className="field-hint">Enter a value greater than 0 and no greater than 100, with up to 2 decimal places.</span>
          </label>
        </div>
        <div className="management-actions">
          <button className="primary-button" type="submit" disabled={isSaving}>
            {isSaving ? 'Saving…' : isEditing ? 'Save changes' : 'Create KPI'}
          </button>
        </div>
      </form>
    </section>
  )
}
