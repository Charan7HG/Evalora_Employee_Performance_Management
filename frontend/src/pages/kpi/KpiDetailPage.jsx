import { useCallback, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import {
  deactivateKpi,
  getKpiById,
  reactivateKpi,
} from '../../api/kpiApi.js'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'
import { useConfirmation } from '../../hooks/useConfirmation.jsx'

function formatWeightage(weightage) {
  return weightage == null ? 'Not set' : `${Number(weightage).toFixed(2)}%`
}

export default function KpiDetailPage() {
  const { id } = useParams()
  const location = useLocation()
  const [attempt, setAttempt] = useState(0)
  const [isChangingStatus, setIsChangingStatus] = useState(false)
  const [actionError, setActionError] = useState('')
  const [successMessage, setSuccessMessage] = useState(location.state?.successMessage || '')
  const loader = useCallback(() => getKpiById(id), [id])
  const { data: kpi, error, isLoading, retry } = useApiResource(loader, `${id}:${attempt}`)
  const { ask, dialog } = useConfirmation()

  function changeStatus() {
    if (!kpi) {
      return
    }
    const activating = !kpi.active
    ask({
      title: `${activating ? 'Reactivate' : 'Deactivate'} KPI`,
      message: `${activating ? 'Reactivate' : 'Deactivate'} "${kpi.name}"? This will mark the KPI ${activating ? 'active' : 'inactive'}.`,
      confirmLabel: activating ? 'Reactivate' : 'Deactivate',
      onConfirm: () => applyStatusChange(activating),
    })
  }

  async function applyStatusChange(activating) {
    setActionError('')
    setSuccessMessage('')
    setIsChangingStatus(true)
    try {
      if (activating) {
        await reactivateKpi(id)
      } else {
        await deactivateKpi(id)
      }
      setSuccessMessage(`KPI "${kpi.name}" ${activating ? 'reactivated' : 'deactivated'} successfully.`)
      setAttempt((current) => current + 1)
    } catch (requestError) {
      setActionError(getApiErrorMessage(requestError))
    } finally {
      setIsChangingStatus(false)
    }
  }

  return (
    <section aria-labelledby="kpi-detail-title">
      {dialog}
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="kpi-detail-title">KPI details</h1>
        </div>
        <Link className="secondary-button button-link" to="/hr/kpis">Back to KPIs</Link>
      </div>
      {successMessage && <p className="management-message management-success" role="status">{successMessage}</p>}
      {actionError && <p className="management-message management-error" role="alert">{actionError}</p>}
      <ManagementRequestState error={error} isLoading={isLoading} onRetry={retry} />
      {kpi && !error && !isLoading && (
        <>
          <dl className="staff-details kpi-details">
            <div><dt>Name</dt><dd>{kpi.name}</dd></div>
            <div><dt>Category</dt><dd>{kpi.category || 'Not provided'}</dd></div>
            <div>
              <dt>Assessment</dt>
              <dd>{kpi.kpiType === 'HR' ? 'HR assessed' : 'Manager assessed'}</dd>
            </div>
            <div className="kpi-detail-description"><dt>Description</dt><dd>{kpi.description || 'Not provided'}</dd></div>
            <div><dt>Default weightage</dt><dd>{formatWeightage(kpi.defaultWeightage)}</dd></div>
            <div>
              <dt>Status</dt>
              <dd>
                <span className={`status-pill status-${kpi.active ? 'active' : 'inactive'}`}>
                  {kpi.active ? 'Active' : 'Inactive'}
                </span>
              </dd>
            </div>
          </dl>
          <div className="management-actions">
            <Link className="primary-link" to={`/hr/kpis/${id}/edit`}>Edit KPI</Link>
            <button
              className="secondary-button"
              type="button"
              disabled={isChangingStatus}
              onClick={changeStatus}
            >
              {isChangingStatus ? 'Saving…' : kpi.active ? 'Deactivate KPI' : 'Reactivate KPI'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
