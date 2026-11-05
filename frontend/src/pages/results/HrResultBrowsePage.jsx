import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { getCycles } from '../../api/cycleApi.js'
import { getHrCycleResults } from '../../api/resultApi.js'
import ManagementRequestState from '../../components/ManagementRequestState.jsx'
import { useApiResource } from '../../hooks/useApiResource.js'

function formatValue(value) {
  return value == null || value === '' ? 'Not provided' : value
}

function formatStatus(value) {
  return value ? value.replaceAll('_', ' ') : 'Unknown'
}

function formatDate(value) {
  return value || 'Not provided'
}

/**
 * The most recent non-draft cycle is the current one. This is presentation
 * only: cycle status and publication state always come from the backend.
 */
function currentCycleOf(cycles) {
  if (!Array.isArray(cycles) || cycles.length === 0) {
    return null
  }
  const sorted = [...cycles].sort((left, right) => (
    String(right.startDate || '').localeCompare(String(left.startDate || ''))
  ))
  return sorted.find((cycle) => cycle.status !== 'DRAFT') || sorted[0]
}

export default function HrResultBrowsePage() {
  const [selectedCycleId, setSelectedCycleId] = useState('')
  const [department, setDepartment] = useState('')
  const [search, setSearch] = useState('')

  const cycleLoader = useCallback(() => getCycles(), [])
  const {
    data: cyclesData,
    error: cyclesError,
    isLoading: cyclesLoading,
    retry: retryCycles,
  } = useApiResource(cycleLoader, 'hr-result-browse-cycles')
  const cycles = useMemo(() => (Array.isArray(cyclesData) ? cyclesData : []), [cyclesData])
  const currentCycle = useMemo(() => currentCycleOf(cycles), [cycles])
  const effectiveCycleId = selectedCycleId || (currentCycle ? String(currentCycle.id) : '')

  const resultsLoader = useCallback(
    () => (effectiveCycleId ? getHrCycleResults(effectiveCycleId) : Promise.resolve([])),
    [effectiveCycleId],
  )
  const {
    data: resultsData,
    error: resultsError,
    isLoading: resultsLoading,
    retry: retryResults,
  } = useApiResource(resultsLoader, `hr-result-browse:${effectiveCycleId}`)
  const results = useMemo(() => (Array.isArray(resultsData) ? resultsData : []), [resultsData])

  // Departments come from the real result rows of the selected cycle; nothing
  // is hardcoded and a department without results is not offered.
  const departments = useMemo(() => {
    const unique = new Set()
    for (const result of results) {
      const value = result.employee?.department
      if (value) {
        unique.add(value)
      }
    }
    return [...unique].sort((left, right) => left.localeCompare(right))
  }, [results])

  const searchTerm = search.trim().toLocaleLowerCase()
  const visibleResults = results.filter((result) => {
    const employee = result.employee || {}
    const departmentMatches = !department || employee.department === department
    const searchMatches = !searchTerm
      || [employee.fullName, employee.employeeCode, employee.department, employee.designation]
        .filter(Boolean)
        .some((value) => value.toLocaleLowerCase().includes(searchTerm))
    return departmentMatches && searchMatches
  })

  const selectedCycle = cycles.find((cycle) => String(cycle.id) === String(effectiveCycleId))
  const isCurrentCycle = currentCycle && String(currentCycle.id) === String(effectiveCycleId)
  const publishedResultCount = results.filter((result) => result.status === 'PUBLISHED').length
  const filterError = cyclesError || resultsError
  const isLoading = cyclesLoading || resultsLoading

  return (
    <section aria-labelledby="hr-result-browse-title">
      <div className="management-page-heading">
        <div>
          <p className="dashboard-eyebrow">HR workspace</p>
          <h1 id="hr-result-browse-title">Results by cycle</h1>
        </div>
        <Link className="secondary-button button-link" to="/hr/results">
          Results awaiting publication
        </Link>
      </div>
      <p className="dashboard-intro">
        Every result of the selected performance cycle, including results that are not
        published yet. Historical published results are shown exactly as stored.
      </p>

      <div className="kpi-list-controls result-browse-controls">
        <label>
          Performance cycle
          <select
            value={effectiveCycleId}
            onChange={(event) => {
              setSelectedCycleId(event.target.value)
              setDepartment('')
            }}
            disabled={cyclesLoading || cycles.length === 0}
          >
            {cycles.length === 0 && <option value="">No cycles available</option>}
            {cycles.map((cycle) => (
              <option key={cycle.id} value={String(cycle.id)}>
                {cycle.name} ({formatStatus(cycle.status)})
                {currentCycle && String(currentCycle.id) === String(cycle.id) ? ' — current cycle' : ''}
              </option>
            ))}
          </select>
        </label>
        <label>
          Department
          <select
            value={department}
            onChange={(event) => setDepartment(event.target.value)}
            disabled={departments.length === 0}
          >
            <option value="">All departments</option>
            {departments.map((value) => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </label>
        <label>
          Search employees
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search by name, code or designation"
          />
        </label>
      </div>

      {selectedCycle && (
        <p className="result-browse-context" role="status">
          {selectedCycle.name}
          {isCurrentCycle ? ' (current cycle)' : ' (historical cycle)'}
          {' · '}{formatDate(selectedCycle.startDate)} – {formatDate(selectedCycle.endDate)}
          {' · '}{visibleResults.length} result{visibleResults.length === 1 ? '' : 's'} shown
          {' · '}{publishedResultCount} published
        </p>
      )}

      <ManagementRequestState
        error={filterError}
        isLoading={isLoading}
        onRetry={() => {
          retryCycles()
          retryResults()
        }}
      />

      {!isLoading && !filterError && cycles.length === 0 && (
        <p className="management-empty">No performance cycles have been created.</p>
      )}
      {!isLoading && !filterError && cycles.length > 0 && results.length === 0 && (
        <p className="management-empty">
          This cycle has no results yet. Results appear after HR finalizes an assessment.
        </p>
      )}
      {!isLoading && !filterError && results.length > 0 && visibleResults.length === 0 && (
        <p className="management-empty">No results match the selected department or search.</p>
      )}

      {!isLoading && !filterError && visibleResults.length > 0 && (
        <div className="staff-table-wrap">
          <table className="staff-table result-browse-table">
            <thead>
              <tr>
                <th scope="col">Employee</th>
                <th scope="col">Employee code</th>
                <th scope="col">Department</th>
                <th scope="col">Designation</th>
                <th scope="col">Overall score</th>
                <th scope="col">Grade</th>
                <th scope="col">Publication status</th>
                <th scope="col">Published date</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleResults.map((result) => {
                const employee = result.employee || {}
                return (
                  <tr key={result.resultId}>
                    <td>{employee.fullName || 'Employee result'}</td>
                    <td>{formatValue(employee.employeeCode)}</td>
                    <td>{formatValue(employee.department)}</td>
                    <td>{formatValue(employee.designation)}</td>
                    <td>{formatValue(result.overallScore)}</td>
                    <td>{result.grade ? formatStatus(result.grade) : 'Not provided'}</td>
                    <td>
                      <span className={`status-pill status-${(result.status || '').toLowerCase()}`}>
                        {formatStatus(result.status)}
                      </span>
                    </td>
                    <td>{formatValue(result.publishedAt)}</td>
                    <td>
                      <div className="table-actions">
                        <Link to={`/hr/results/${result.resultId}`}>View result</Link>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
