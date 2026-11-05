# Scoring logic

Scoring runs only when HR submits an eligible assignment in
`HR_REVIEW_PENDING`. All values and intermediate arithmetic use `BigDecimal`.
The employee self-rating is stored for context and has no scoring weight.

## KPI classification (Phase 5C)

Every KPI master row and every frozen `employee_kpis` snapshot carries a
`kpi_type` of `MANAGER` or `HR`, so the two evaluation responsibilities stay
separate:

| KPI type | Rated by manager | Rated by HR | Scoring input |
|---|---|---|---|
| `MANAGER` | required | required | `manager × 0.75 + hr × 0.25` |
| `HR` | never (rejected by the backend) | required | `hr` only |

Rows created before Phase 5C are classified `MANAGER` by the V4 migration, so
their score is unchanged. Published and finalized results are never rescored.

A manager can only view and rate `MANAGER` KPIs; sending an HR-assessed KPI in a
manager draft or submission fails with
`HR-assessed KPIs cannot be rated by the manager.` HR can only rate HR-assessed
KPIs and never writes manager rating columns, so manager values cannot be
overwritten during HR review.

## Per-KPI score

For each frozen manager-assessed employee KPI:

```text
final KPI score = manager rating × 0.75 + HR rating × 0.25
```

For each HR-assessed KPI the final KPI score is the HR rating itself.

Both ratings are required integers from 1 through 5. Missing or invalid ratings
reject finalization; they are never treated as zero. These coefficients and
integer ratings produce an exact score with two decimal places. No
intermediate rounding is performed.

Examples:

| Manager | HR | Final KPI score |
|---:|---:|---:|
| 4 | 5 | 4.25 |
| 3 | 4 | 3.25 |
| 1 | 1 | 1.00 |
| 5 | 5 | 5.00 |

## Overall score mode

The scoring mode is selected from the frozen `employee_kpis.weightage` values.
There is no fallback between modes.

### Weighted

Weighted mode is valid only when every KPI has a weightage, each weight is
greater than 0 and no greater than 100, and the total is exactly 100.00:

```text
overall = sum(final KPI score × weightage) / 100
```

The weighted total is calculated exactly and only the final overall score is
rounded to two decimal places using `HALF_UP`.

For example, scores `4.25`, `3.50`, and `4.00` with weights `40.00`, `30.00`,
and `30.00` produce `3.95`.

### Average

Average mode is valid only when every KPI weightage is `NULL`:

```text
overall = sum(final KPI scores) / number of KPIs
```

The final division is rounded to two decimal places using `HALF_UP`. For
example, `4.25`, `3.50`, and `4.00` produce `3.92` from the exact average
`3.9166…`. An exact halfway value such as `4.125` rounds to `4.13`.

### Rejected configurations

Finalization fails for an empty KPI set, mixed or partially missing weights,
zero or negative weights, weights above 100, or a weighted total other than
exactly 100.00. All rating and weight validation occurs before result
persistence.

## Grade scale

The grade is assigned from the overall score after its final two-decimal
rounding. Thresholds are inclusive floors:

| Overall score | Grade |
|---:|---|
| 4.50–5.00 | `OUTSTANDING` |
| 4.00–4.49 | `EXCELLENT` |
| 3.50–3.99 | `VERY_GOOD` |
| 3.00–3.49 | `GOOD` |
| 2.00–2.99 | `NEEDS_IMPROVEMENT` |
| 1.00–1.99 | `UNSATISFACTORY` |

## Persistence

The overall score, grade and calculation mode are written to
`performance_results`. Each KPI's frozen name/weight, submitted ratings and
exact per-KPI score are written to `performance_result_items`. After HR
finalization the result status is `DRAFT` and publication fields are `NULL`.
Phase 3I publication changes the result status and publication metadata only;
it never recalculates scores or changes result items. The Phase 3J employee
dashboard reads the persisted overall score only after publication and does
not calculate or expose a draft result. See
[Performance workflow](./PERFORMANCE_WORKFLOW.md) and
[database design](./DATABASE_DESIGN.md) for lifecycle and schema details.
