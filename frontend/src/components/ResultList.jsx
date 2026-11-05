import { Link } from 'react-router-dom'

function formatValue(value) {
  return value == null || value === '' ? 'Not provided' : value
}

function formatStatus(value) {
  return value ? value.replaceAll('_', ' ') : 'Unknown'
}

export default function ResultList({ results, detailBasePath, emptyMessage }) {
  if (!results || results.length === 0) {
    return <p className="management-empty">{emptyMessage}</p>
  }

  return (
    <div className="result-list">
      {results.map((result) => {
        const employee = result.employee || {}
        const cycle = result.cycle || {}
        return (
          <article className="result-card" key={result.resultId}>
            <div>
              <p className="dashboard-eyebrow">Employee</p>
              <h2>{employee.fullName || 'Employee result'}</h2>
              {employee.employeeCode && (
                <p className="result-card-code">{employee.employeeCode}</p>
              )}
            </div>
            <dl className="cycle-details">
              {employee.department && (
                <div><dt>Department</dt><dd>{employee.department}</dd></div>
              )}
              <div><dt>Performance cycle</dt><dd>{cycle.cycleName || 'Not provided'}</dd></div>
              <div><dt>Overall score</dt><dd>{formatValue(result.overallScore)}</dd></div>
              <div>
                <dt>Grade</dt>
                <dd>{result.grade ? formatStatus(result.grade) : 'Not provided'}</dd>
              </div>
              <div><dt>Published</dt><dd>{formatValue(result.publishedAt)}</dd></div>
            </dl>
            <Link
              className="primary-link result-card-action"
              to={`${detailBasePath}/${result.resultId}`}
            >
              View result
            </Link>
          </article>
        )
      })}
    </div>
  )
}
