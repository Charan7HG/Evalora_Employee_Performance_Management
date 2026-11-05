function formatValue(value) {
  return value == null || value === '' ? 'Not provided' : value
}

function formatStatus(value) {
  return value ? value.replaceAll('_', ' ') : 'Unknown'
}

function displayDate(value, missingLabel = 'Not provided') {
  return value || missingLabel
}

function formatWeightage(value) {
  return value == null ? 'Unweighted' : `${value}%`
}

function formatCalculationMode(value) {
  if (value === 'WEIGHTED') {
    return 'Weighted'
  }
  if (value === 'AVERAGE') {
    return 'Average (unweighted)'
  }
  return formatStatus(value)
}

function Rating({ label, rating, comment }) {
  return (
    <section>
      <h4>{label}</h4>
      <p><strong>Rating:</strong> {formatValue(rating)}</p>
      <p><strong>Comment:</strong> {comment || 'No comment'}</p>
    </section>
  )
}

export default function ResultDetails({ result }) {
  if (!result) {
    return null
  }

  const employee = result.employee || {}
  const cycle = result.cycle || {}
  const items = result.items || []

  return (
    <>
      <section className="result-summary" aria-label="Employee and cycle details">
        <p className="dashboard-eyebrow">Employee</p>
        <h2>{employee.fullName || 'Employee result'}</h2>
        <dl className="cycle-details">
          {employee.employeeCode && (
            <div><dt>Employee code</dt><dd>{employee.employeeCode}</dd></div>
          )}
          {employee.department && (
            <div><dt>Department</dt><dd>{employee.department}</dd></div>
          )}
          {employee.designation && (
            <div><dt>Designation</dt><dd>{employee.designation}</dd></div>
          )}
          <div><dt>Performance cycle</dt><dd>{cycle.cycleName || 'Not provided'}</dd></div>
          <div>
            <dt>Cycle dates</dt>
            <dd>{displayDate(cycle.startDate)} – {displayDate(cycle.endDate)}</dd>
          </div>
          <div><dt>Result status</dt><dd>{formatStatus(result.status)}</dd></div>
          <div><dt>HR finalized</dt><dd>{displayDate(result.hrFinalizedAt)}</dd></div>
          <div>
            <dt>Publication date</dt>
            <dd>{displayDate(result.publishedAt, 'Not published')}</dd>
          </div>
        </dl>
      </section>

      <section className="result-overall" aria-labelledby="result-overall-title">
        <h2 id="result-overall-title">Overall result</h2>
        <div className="result-score-grid">
          <div>
            <span>Overall score</span>
            <strong>{formatValue(result.overallScore)}</strong>
          </div>
          <div>
            <span>Grade</span>
            <strong>{result.grade ? formatStatus(result.grade) : 'Not provided'}</strong>
          </div>
          <div>
            <span>Calculation mode</span>
            <strong>{formatCalculationMode(result.calculationMode)}</strong>
          </div>
        </div>
        <p className="result-source-note">
          The overall score, grade and KPI final scores are supplied by the backend.
          This page only displays stored values; it does not calculate, reconstruct or
          approximate scores. Employee self-ratings are reference-only and are not
          treated as final scores.
        </p>
      </section>

      <section className="result-kpi-section" aria-labelledby="result-kpi-title">
        <h2 id="result-kpi-title">KPI results</h2>
        {items.length === 0 && (
          <p className="management-empty">No KPI result items were returned for this result.</p>
        )}
        <div className="result-kpis">
          {items.map((item) => (
            <article className="result-kpi" key={item.employeeKpiId}>
              <h3>{item.kpiName || 'KPI'}</h3>
              <dl className="result-kpi-details">
                <div><dt>Weightage</dt><dd>{formatWeightage(item.weightage)}</dd></div>
                <div><dt>Final KPI score</dt><dd>{formatValue(item.finalScore)}</dd></div>
              </dl>
              <div className="result-kpi-ratings">
                <Rating
                  label="Employee self-rating (reference only)"
                  rating={item.selfRating}
                  comment={item.selfComment}
                />
                <Rating
                  label="Manager rating"
                  rating={item.managerRating}
                  comment={item.managerComment}
                />
                <Rating
                  label="HR rating"
                  rating={item.hrRating}
                  comment={item.hrComment}
                />
              </div>
            </article>
          ))}
        </div>
      </section>
    </>
  )
}
