# Performance workflow

This document describes the employee-to-HR assessment lifecycle, result
release and read-only dashboards implemented in Phases 3D–3J.

## Lifecycle

1. HR publishes a cycle. The backend creates an assignment for each employee
   and freezes the selected KPI name, description, category and weightage in
   `employee_kpis`.
2. The employee saves a draft and submits a self-assessment. Self-ratings are
   retained for context and contribute 0% to the final score.
3. The assignment moves to `MANAGER_REVIEW_PENDING`. The manager saves and
   submits ratings; manager ratings contribute 75%.
4. The assignment moves to `HR_REVIEW_PENDING`. HR reviews the frozen KPI
   snapshot and both prior assessments, saves HR ratings, and submits; HR
   ratings contribute 25%.
5. HR submission calculates the score and grade, writes one result header and
   one item per assigned KPI, sets `hr_finalized_at`, and moves the assignment
   to `COMPLETED`.
6. HR publishes one result or eligible results for a cycle. Publication moves
   the result from `DRAFT` to `PUBLISHED`, moves the assignment from
   `COMPLETED` to `RESULTS_PUBLISHED`, and sets `results_published_at`.
7. Employees can view their own published result. Managers can view results
   for employees whose assignment snapshot identifies them as the reviewer
   for that cycle.

The finalization write is one transaction. Validation, calculation, header,
item and assignment changes either all commit or all roll back. The workflow
locks the assignment row before checking its state, so concurrent submissions
cannot both finalize it. Finalization is one-way; draft edits and repeated
submissions conflict after completion.

## Result state

Finalization is not publication. After HR submission, the result is stored as
`DRAFT`, its `published_at` and `published_by` fields remain `NULL`, and the
assignment's `results_published_at` remains `NULL`.

Publication requires a `PUBLISHED` cycle, a DRAFT result, and an assignment
that is `COMPLETED` with HR finalization recorded. Individual publication
conflicts if already published. Bulk publication skips ineligible assignments
and publishes all eligible DRAFT results in one transaction; a persistence
failure rolls back the eligible batch. It never creates a missing result.
Bulk locks the cycle and then assignment rows in ascending ID order. Both
individual and bulk publication use assignment locks, so competing operations
cannot publish the same result twice.

The published result and assignment timestamps/statuses must agree for
employee and manager reads. Unpublished, inconsistent, unknown, or
unauthorized results return `404`. Employee access is scoped to the
authenticated employee. Manager access uses the `manager_id` snapshot on the
assignment, so changing an employee's current manager does not rewrite
historical access.

The application writes the same publication instant to both timestamp
columns, and employee/manager queries also require the stored timestamps to be
equal. V1 constrains each table's status/timestamp combination independently;
cross-table status and timestamp equality is enforced by the transactional
publication service and visibility queries rather than a database constraint.

The result item copies the frozen KPI name and weightage and the submitted
self-, manager- and HR-ratings/comments. Only manager and HR ratings are used
for `final_score`; employee snapshots and assessment rows are not rewritten.
KPI master edits after cycle publication do not change the frozen snapshot or
the generated result. Publication changes only status and publication
metadata; stored scores and items are never recalculated.

## Read-only dashboards (Phase 3J)

The dashboards aggregate existing records and do not change assessments,
assignments, cycles or results. HR counts active enabled employee and manager
profiles separately, all cycles, cycles with a non-null `published_at`,
published-cycle assignment queues by their exact pending status, assignments
whose status is exactly `COMPLETED`, and results visible under the same
published-status/timestamp rules as employee result reads.

The manager's active team size uses the current `employees.manager_id`
relationship. Cycle workload, completed assignments and published result
counters use the historical manager snapshot in
`employee_assignments.manager_id`; the current team hierarchy never
reassigns historical work. Workflow queue counts include only employee-role
assignments in cycles whose status is exactly `PUBLISHED`.

The employee current cycle is the newest assigned cycle (descending cycle ID)
whose status is exactly `PUBLISHED`. Its due date corresponds to the
assignment's current pending stage and is null after completion. The latest
published result is chosen by publication timestamp descending, with result
ID as a deterministic tie-breaker, and is read from the persisted result
score. Both employee values are null when no eligible row exists. Draft or
inconsistent results are never included.

## References

- [Scoring rules](./SCORING_LOGIC.md)
- [HR assessment API](./API_DOCUMENTATION.md)
- [Test coverage and limits](./TESTING.md)
- [Database result schema](./DATABASE_DESIGN.md)
