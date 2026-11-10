import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getApiErrorMessage } from '../../api/axiosInstance.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

const EMPTY_FORM = {
  firstName: '',
  lastName: '',
  email: '',
  employeeCode: '',
  department: '',
  designation: '',
  joiningDate: '',
  managerId: '',
}

function splitFullName(fullName = '') {
  const [firstName = '', ...lastName] = fullName.trim().split(/\s+/)
  return { firstName, lastName: lastName.join(' ') }
}

function formFromStaff(staff) {
  return {
    ...EMPTY_FORM,
    ...splitFullName(staff.fullName),
    email: staff.email || '',
    employeeCode: staff.employeeCode || '',
    department: staff.department || '',
    designation: staff.designation || '',
    joiningDate: staff.joiningDate || '',
    originalFullName: staff.fullName || '',
    nameSplitVerified: false,
  }
}

function validateForm(form, isEditing, managerRequired = false) {
  if (!form.firstName.trim() || !form.lastName.trim()) {
    return 'Enter both first and last name.'
  }
  if (form.firstName.trim().length > 75 || form.lastName.trim().length > 75) {
    return 'First and last names must each be 75 characters or fewer.'
  }
  if (form.firstName.trim().length + form.lastName.trim().length + 1 > 150) {
    return 'Full name must be 150 characters or fewer.'
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()) || form.email.trim().length > 255) {
    return 'Enter a valid email address (maximum 255 characters).'
  }
  if (!form.employeeCode.trim() || form.employeeCode.trim().length > 30) {
    return 'Enter an employee code of 30 characters or fewer.'
  }
  if (form.department.length > 100 || form.designation.length > 100) {
    return 'Department and designation must each be 100 characters or fewer.'
  }
  if (managerRequired && !form.managerId) {
    return 'Select a reporting manager before creating this employee.'
  }
  if (isEditing) {
    if (!form.nameSplitVerified) {
      return 'Verify or correct the first and last name split before saving.'
    }
  }
  return ''
}

export default function StaffFormPage({
  resource,
  title,
  getStaff,
  createStaff,
  updateStaff,
  listManagers,
}) {
  const { id } = useParams()
  const navigate = useNavigate()
  const isEditing = Boolean(id)
  const [form, setForm] = useState(EMPTY_FORM)
  const [staff, setStaff] = useState(null)
  const [isLoadingStaff, setIsLoadingStaff] = useState(isEditing)
  const [staffError, setStaffError] = useState(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [isSaving, setIsSaving] = useState(false)
  const savingRef = useRef(false)
  const basePath = `/hr/${resource}`
  const managerLoader = useCallback(
    () => resource === 'employees' && !isEditing
      ? listManagers('ACTIVE')
      : Promise.resolve([]),
    [isEditing, listManagers, resource],
  )
  const managersResource = useApiResource(managerLoader, 'ACTIVE')
  const managers = Array.isArray(managersResource.data)
    ? [...new Map(managersResource.data
      .filter((manager) => manager.role === 'MANAGER'
        && manager.status === 'ACTIVE'
        && manager.accountEnabled !== false)
      .map((manager) => [String(manager.id), manager])).values()]
    : []
  const isEmployeeCreate = !isEditing && resource === 'employees'
  const managerListUnavailable = managersResource.isLoading
    || Boolean(managersResource.error)
    || managers.length === 0

  useEffect(() => {
    if (!isEditing) {
      return undefined
    }

    let active = true
    Promise.resolve()
      .then(() => getStaff(id))
      .then((record) => {
        if (active) {
          setStaff(record)
          setForm(formFromStaff(record))
        }
      })
      .catch((requestError) => {
        if (active) {
          setStaffError({
            message: getApiErrorMessage(requestError),
            status: requestError?.response?.status,
          })
        }
      })
      .finally(() => {
        if (active) {
          setIsLoadingStaff(false)
        }
      })

    return () => {
      active = false
    }
  }, [getStaff, id, isEditing])

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({
      ...current,
      [name]: value,
      ...(name === 'firstName' || name === 'lastName' ? { nameSplitVerified: false } : {}),
    }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setErrorMessage('')
    if (savingRef.current) {
      return
    }
    const validationMessage = validateForm(form, isEditing, isEmployeeCreate)
    if (validationMessage) {
      setErrorMessage(validationMessage)
      return
    }
    if (!isEditing) {
      if (isEmployeeCreate && !managers.some((manager) => String(manager.id) === form.managerId)) {
        setErrorMessage('Select an active reporting manager from the available list.')
        return
      }
    }

    const payload = {
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email: form.email.trim(),
      employeeCode: form.employeeCode.trim(),
      department: form.department.trim() || null,
      designation: form.designation.trim() || null,
      joiningDate: form.joiningDate || null,
    }
    if (!isEditing) {
      if (resource === 'employees' && form.managerId) {
        payload.managerId = Number(form.managerId)
      }
    }

    savingRef.current = true
    setIsSaving(true)
    try {
      const saved = isEditing
        ? await updateStaff(id, payload)
        : await createStaff(payload)
      navigate(`${basePath}/${saved.id}`, {
        replace: true,
        state: { successMessage: `${title.slice(0, -1)} ${isEditing ? 'updated' : 'created'} successfully.` },
      })
    } catch (requestError) {
      setErrorMessage(getApiErrorMessage(requestError))
    } finally {
      savingRef.current = false
      setIsSaving(false)
    }
  }

  return (
    <section aria-labelledby="staff-form-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="staff-form-title">{isEditing ? `Edit ${title.slice(0, -1)}` : `Add ${title.slice(0, -1)}`}</h1>
        </div>
        <Link className="secondary-button button-link" to={staff ? `${basePath}/${id}` : basePath}>
          Cancel
        </Link>
      </div>
      <ManagementRequestState error={staffError} isLoading={isLoadingStaff} />
      {(!isEditing || staff) && (
        <form className="staff-form" onSubmit={handleSubmit} noValidate>
          {errorMessage && <p className="management-message management-error" role="alert">{errorMessage}</p>}
          <div className="staff-form-grid">
            <label>
              First name
              <input name="firstName" value={form.firstName} onChange={updateField} maxLength={75} required />
            </label>
            <label>
              Last name
              <input name="lastName" value={form.lastName} onChange={updateField} maxLength={75} required />
            </label>
            {isEditing && staff && (
              <div className="name-verification">
                <p>
                  The API provides only the combined full name. Verify or correct how it is split before saving.
                </p>
                <dl>
                  <div><dt>Current full name</dt><dd>{form.originalFullName}</dd></div>
                  <div>
                    <dt>Will be saved as</dt>
                    <dd>{`${form.firstName.trim()} ${form.lastName.trim()}`.trim()}</dd>
                  </div>
                </dl>
                <label className="name-verification-check">
                  <input
                    type="checkbox"
                    checked={form.nameSplitVerified}
                    onChange={(event) => {
                      setForm((current) => ({ ...current, nameSplitVerified: event.target.checked }))
                    }}
                  />
                  I verified the first and last name split.
                </label>
              </div>
            )}
            <label>
              Email
              <input name="email" type="email" value={form.email} onChange={updateField} maxLength={255} required />
            </label>
            <label>
              Employee code
              <input name="employeeCode" value={form.employeeCode} onChange={updateField} maxLength={30} required />
            </label>
            <label>
              Department
              <input name="department" value={form.department} onChange={updateField} maxLength={100} />
            </label>
            <label>
              Designation
              <input name="designation" value={form.designation} onChange={updateField} maxLength={100} />
            </label>
            <label>
              Joining date
              <input name="joiningDate" type="date" value={form.joiningDate} onChange={updateField} />
            </label>
            {!isEditing && resource === 'employees' && (
              <label>
                Reporting manager <span aria-hidden="true">*</span>
                <select
                  name="managerId"
                  value={form.managerId}
                  onChange={updateField}
                  required
                  disabled={managerListUnavailable}
                  aria-describedby="reporting-manager-help"
                  aria-invalid={Boolean(errorMessage && !form.managerId)}
                >
                  <option value="">Select a reporting manager</option>
                  {managers.map((manager) => (
                    <option key={manager.id} value={manager.id}>
                      {manager.fullName} ({manager.employeeCode})
                    </option>
                  ))}
                </select>
                <span id="reporting-manager-help" className="field-hint">
                  {managersResource.isLoading && 'Loading eligible managers…'}
                  {!managersResource.isLoading && !managersResource.error && managers.length === 0 && (
                    <>No active managers are available. <Link to="/hr/managers">Create or activate a manager first.</Link></>
                  )}
                  {!managersResource.isLoading && !managersResource.error && managers.length > 0
                    && 'Choose an active manager for this employee.'}
                </span>
                {managersResource.error && (
                  <span className="field-hint" role="alert">
                    Active managers could not be loaded. Employee creation is disabled until the list is available. <Link to="/hr/managers">Check managers</Link> or{' '}
                    <button className="inline-button" onClick={managersResource.retry} type="button">retry</button>.
                  </span>
                )}
              </label>
            )}
          </div>
          <div className="management-actions">
            <button className="primary-button" type="submit" disabled={isSaving || isLoadingStaff || (isEmployeeCreate && managerListUnavailable)}>
              {isSaving ? 'Saving…' : isEditing ? 'Save changes' : `Create ${title.slice(0, -1).toLowerCase()}`}
            </button>
          </div>
          {!isEditing && (
            <p className="field-hint">
              Evalora emails an activation link so the new account holder chooses their own password.
              HR never sets or sees the permanent password.
            </p>
          )}
        </form>
      )}
    </section>
  )
}