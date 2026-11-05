# API documentation

This document describes the dashboard, staff management, KPI catalogue,
performance cycle, assessment and result APIs. Requests require an
authenticated bearer token with the role shown below. Identity is derived from
`EvaloraPrincipal`; request data is never an authorization source.

## Dashboard APIs (Phase 3J)

| Method | Path | Role | Purpose |
|---|---|---|---|
| `GET` | `/api/hr/dashboard` | `HR` | Organization-wide counts |
| `GET` | `/api/manager/dashboard` | `MANAGER` | Current team size and historical assignment counts |
| `GET` | `/api/employee/dashboard` | `EMPLOYEE` | The employee's current published-cycle assignment and latest published result |

These endpoints take no request body or identity parameters. The authenticated
principal supplies the user identity.

The HR response contains `totalActiveEmployees`, `totalActiveManagers`,
`totalPerformanceCycles`, `totalPublishedCycles`,
`employeesAwaitingSelfAssessment`, `employeesAwaitingManagerReview`,
`employeesAwaitingHrReview`, `completedAssessments`, and `publishedResults`
(all `long`). Active staff counts require the matching role, `ACTIVE` employee
profile status and an enabled user account. Total cycles includes every
workflow state; published cycles includes cycles with a non-null
`published_at`, including cycles that later move to `IN_PROGRESS` or
`COMPLETED`. Queue counters count employee-role assignments in exactly
`PUBLISHED` cycles at the matching pending status. `completedAssessments`
counts assignments in exactly `COMPLETED`; `publishedResults` counts only
consistent published result/assignment pairs with both equal, non-null
publication timestamps.

The manager response contains `totalActiveDirectReports`,
`employeesAwaitingSelfAssessment`, `assessmentsAwaitingManagerReview`,
`assessmentsAwaitingHrReview`, `completedAssessments`, and `publishedResults`
(all `long`). Team size uses the current `employees.manager_id` relationship
and counts only active, enabled employee-role profiles. Workflow and result
counters instead use the historical `employee_assignments.manager_id`
snapshot, so reassignment does not transfer an existing cycle's work. Queue
and completed counter definitions match HR's definitions.

The employee response contains `currentCycle` and `latestPublishedResult`,
each nullable when no visible record exists. `currentCycle` has `cycleId`
(`Long`), `cycleName`, `cycleStatus`, `assessmentStatus` (strings), and
`dueDate` (`LocalDate`, nullable). It is the employee's newest assignment by
cycle ID whose cycle status is exactly `PUBLISHED`; the due date corresponds
to the pending stage, and is null for `COMPLETED` or `RESULTS_PUBLISHED`.
`latestPublishedResult` has `score` (`BigDecimal`), `publishedAt`
(`OffsetDateTime`), and a `cycle` object containing `cycleId`, `cycleName`,
`startDate`, and `endDate`. It is selected by publication timestamp
descending, then result ID descending, and uses the persisted score without
recalculation. Only published result/assignment pairs with matching,
non-null publication timestamps are eligible. No current cycle or no
published result is represented as JSON `null`, not an error.

## Staff and manager management APIs

All routes require the `HR` role. The endpoint chooses the account role; no
request can create a `MANAGER` through the employee endpoint or the reverse.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/employees` | Create an `EMPLOYEE` account |
| `POST` | `/api/hr/managers` | Create a `MANAGER` account |
| `GET` | `/api/hr/employees?status=ACTIVE` | List employee profiles, optionally by status |
| `GET` | `/api/hr/employees/{employeeId}` | Read one employee profile |
| `GET` | `/api/hr/managers?status=ACTIVE` | List manager profiles, optionally by status |
| `GET` | `/api/hr/managers/{managerId}` | Read one manager profile |
| `PUT` | `/api/hr/employees/{employeeId}` | Replace an employee's profile fields |
| `PUT` | `/api/hr/managers/{managerId}` | Replace a manager's profile fields |
| `PUT` | `/api/hr/employees/{employeeId}/manager` | Assign or reassign the reporting manager |
| `PATCH` | `/api/hr/employees/{employeeId}/deactivate` | Deactivate an employee |
| `PATCH` | `/api/hr/employees/{employeeId}/reactivate` | Reactivate an employee |
| `PATCH` | `/api/hr/managers/{managerId}/deactivate` | Deactivate a manager |
| `PATCH` | `/api/hr/managers/{managerId}/reactivate` | Reactivate a manager |

`status` accepts `ACTIVE` or `INACTIVE` and defaults to `ACTIVE`. The list
response is ordered by full name and is not paginated.

### Create staff

`POST /api/hr/employees` and `POST /api/hr/managers` accept
`CreateStaffRequest`:

```json
{
  "firstName": "Asha",
  "lastName": "Rao",
  "email": "asha@example.test",
  "employeeCode": "EMP-021",
  "department": "Engineering",
  "designation": "Developer",
  "joiningDate": "2026-01-05",
  "initialPassword": "Employee@2026",
  "managerId": 31
}
```

`firstName`, `lastName`, `email`, `employeeCode` and `initialPassword` are
required. `department` and `designation` are limited to 100 characters,
`firstName` and `lastName` to 75 each, `email` to 255 and `employeeCode` to 30.
The combined full name is limited to 150 characters. Email is trimmed and
matched case-insensitively. The password must be at least 8 characters and
contain a letter, a number and a special (non-alphanumeric, non-whitespace)
character. The response is `201 Created` with a `StaffResponse`.

`managerId` is required for employees and is accepted only for that role.
Omitting it or sending `null` returns `400 A reporting manager is required for
employees.` Sending a manager id to the manager endpoint returns `400`.
An employee's selected manager must exist, have the `MANAGER` role, be `ACTIVE`
and have an enabled login. The initial employee reporting assignment is
recorded in reporting history; no follow-up request is needed. HR and Manager
account creation do not require a reporting manager. Existing employees and
their nullable `employees.manager_id` values are unchanged by this rule.

The employee form loads eligible options from `GET /api/hr/managers?status=ACTIVE`.
Only enabled active managers are offered. If none are available, HR must create
or activate a manager before creating an employee. This does not assign the
employee to a performance cycle; cycle assignment remains a separate HR action.

### Read and update staff

`StaffResponse` contains `id` (employee profile ID), `userId`, `employeeCode`,
`fullName`, `email`, `role`, `department`, `designation`, `joiningDate`,
`status`, `accountEnabled`, `managerId`, `managerName`, `createdAt` and
`updatedAt`. `managerId` and `managerName` are null when no manager is assigned.
No password hash, token or lockout state is returned.

`PUT /api/hr/employees/{employeeId}` and
`PUT /api/hr/managers/{managerId}` accept `UpdateStaffRequest`:

```json
{
  "firstName": "Asha",
  "lastName": "Rao",
  "email": "asha@example.test",
  "employeeCode": "EMP-021",
  "department": "Product",
  "designation": "Engineer",
  "joiningDate": "2026-01-05"
}
```

The same name, email, employee-code and optional-field limits apply. Role,
account status, password, user ID and manager assignment are deliberately not
accepted. An ID belonging to the other role returns `404`, not a role error.
Duplicate email or employee code returns `409`.

### Assign a reporting manager

`PUT /api/hr/employees/{employeeId}/manager` accepts the dedicated
`AssignManagerRequest`:

```json
{ "managerId": 31 }
```

`managerId` is required; there is no request shape for removing a manager.
The employee must be `ACTIVE`, and the target must exist, have the `MANAGER`
role, be `ACTIVE` and have an enabled login. An employee cannot be assigned to
themselves. The previous open reporting assignment is closed and a new row is
appended; the general profile update endpoint never changes the manager. The
response is the updated `StaffResponse`.

| Situation | Response |
|---|---|
| Employee ID does not exist or belongs to a manager | `404` |
| Manager ID does not exist | `404` |
| Employee is inactive | `409` |
| Selected account has a non-manager role, or the employee selects themselves | `400` |
| Selected manager is inactive or has a disabled login | `409` |

### Activate and deactivate staff

Deactivating an employee sets the profile to `INACTIVE`, disables the login,
closes the open reporting assignment and clears the current manager. Reactivating
sets the profile to `ACTIVE` and enables the login but does not restore the
previous manager; a manager must be assigned again explicitly.

A manager cannot be deactivated while any `ACTIVE` employee still reports to
them; the response is `409` with the active report count. Inactive reports are
cleared defensively and their open reporting assignments are closed. Reactivating
a manager does not recreate old employee assignments. Repeated deactivation or
reactivation of an already-matching state returns `409`.

## KPI catalogue APIs

All routes require the `HR` role. A new KPI is always active and records the
authenticated HR user as its creator.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/kpis` | Create a KPI |
| `GET` | `/api/hr/kpis` | List every KPI |
| `GET` | `/api/hr/kpis?active=true` | List only active (or `false` for inactive) KPIs |
| `GET` | `/api/hr/kpis/{kpiId}` | Read one KPI |
| `PUT` | `/api/hr/kpis/{kpiId}` | Replace editable KPI fields |
| `PATCH` | `/api/hr/kpis/{kpiId}/deactivate` | Mark a KPI inactive |
| `PATCH` | `/api/hr/kpis/{kpiId}/reactivate` | Mark a KPI active |

Create and update accept:

```json
{
  "name": "Delivery Quality",
  "description": "Meets agreed deadlines.",
  "category": "Delivery",
  "defaultWeightage": 25
}
```

`name` is required, trimmed, limited to 150 characters and unique
case-insensitively. `category` is optional and limited to 80 characters.
`description` is optional and unbounded because the database column is `TEXT`.
`defaultWeightage` is optional; when present it must be greater than 0 and at
most 100, with no more than 2 decimal places. Blank optional strings are stored
as `null`.

Create returns `201 Created`. `KpiResponse` contains `id`, `name`,
`description`, `category`, `defaultWeightage`, `active`, `createdById`,
`createdAt` and `updatedAt`. Unknown IDs return `404`; duplicate names return
`409`; invalid fields return `400`; deactivating an inactive KPI or reactivating
an active one returns `409`.

## Performance cycle APIs

All routes require the `HR` role. A new cycle is always created as `DRAFT`; its
status and publication timestamps can only be changed by the dedicated
publication operation.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/cycles` | Create a cycle |
| `GET` | `/api/hr/cycles` | List cycles, newest ID first |
| `GET` | `/api/hr/cycles/{cycleId}` | Read one cycle |
| `PUT` | `/api/hr/cycles/{cycleId}` | Replace DRAFT cycle fields |

Create and update accept `CycleRequest`:

```json
{
  "name": "Annual 2026",
  "description": "Annual performance review.",
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "selfAssessmentDeadline": "2026-11-30",
  "managerReviewDeadline": "2026-12-15",
  "hrReviewDeadline": "2026-12-31"
}
```

All five dates are required. `name` is required, trimmed, limited to 150
characters and unique case-insensitively. `description` is optional and blank
values are stored as `null`. Dates use ISO `YYYY-MM-DD`; start must be before
end, the self-assessment deadline must be within the cycle dates, the manager
deadline must be on or after the self-assessment deadline, and the HR deadline
must be on or after the manager deadline. Invalid dates return `400`; duplicate
names return `409`. Only a `DRAFT` cycle can be updated; otherwise `409`.

`CycleResponse` contains `id`, `name`, `description`, `startDate`, `endDate`,
`selfAssessmentDeadline`, `managerReviewDeadline`, `hrReviewDeadline`, `status`,
`createdById`, `publishedAt`, `completedAt`, `createdAt` and `updatedAt`.
Status is one of `DRAFT`, `PUBLISHED`, `IN_PROGRESS` or `COMPLETED`; publication
timestamps are nullable.

## Cycle KPI assignment APIs

`GET /api/hr/cycles/{cycleId}/kpis` returns the current KPI selection.
`POST /api/hr/cycles/{cycleId}/kpis` replaces the full selection of a `DRAFT`
cycle and accepts:

```json
{
  "kpis": [
    { "kpiId": 8, "weightage": 60 },
    { "kpiId": 9, "weightage": 40 }
  ]
}
```

`kpis` is required. An empty list is accepted by the backend and clears the
selection. Each entry needs `kpiId`; duplicate IDs return `400`. The KPI must
exist (`404` otherwise) and be active (`400` otherwise). Weightages must be all
`null` (equal/unweighted mode) or all non-null values greater than 0 and at most
100 with no more than 2 decimal places, totaling exactly `100.00`; mixed,
invalid or non-total values return `400`.

The response is the replacement list of `CycleKpiResponse` values with `id`
(the cycle-KPI row ID), `kpiId`, `name`, `description`, `category` and
`weightage`. Only a `DRAFT` cycle can be changed; otherwise `409`.

## Cycle employee assignment APIs

`GET /api/hr/cycles/{cycleId}/employees` returns the current roster.
`PUT /api/hr/cycles/{cycleId}/employees` replaces the full roster of a `DRAFT`
cycle and accepts:

```json
{
  "employees": [
    { "employeeId": 31, "managerId": 7 },
    { "employeeId": 32, "managerId": 8 }
  ]
}
```

`employees` is required. An empty list is accepted by the backend and clears the
roster. Each entry needs `employeeId`; duplicate employee IDs return `400`.
`managerId` is optional: when omitted, the employee's current reporting manager
is used, and `400` is returned if there is none. Explicit cycle managers do not
rewrite reporting history.

The selected employee must exist (`404`), have the `EMPLOYEE` role (`400`),
and be active with an enabled login (`409`). The selected manager must exist
(`404`), have the `MANAGER` role (`400`), and be active with an enabled login
(`409`). Self-assignment returns `400`. Each replacement row starts in
`SELF_ASSESSMENT_PENDING`.

The response is `CycleEmployeeResponse[]` with `id` (assignment row ID),
`employeeId`, `employeeCode`, `employeeName`, `managerId`, `managerName` and
`status`. Only a `DRAFT` cycle can be changed; otherwise `409`.

## Cycle publication API

`POST /api/hr/cycles/{cycleId}/publish` takes no body and publishes a `DRAFT`
cycle in one transaction. It requires at least one configured KPI and at least
one assigned employee, valid weightage mode/total, only active KPIs, and an
active, enabled employee and manager for every roster row. It revalidates the
live staff state and then creates one frozen `employee_kpis` snapshot per
employee/KPI pair, sets every assignment to `SELF_ASSESSMENT_PENDING`, and moves
the cycle to `PUBLISHED` with `publishedAt` set. Any failure rolls back the
whole operation.

| Situation | Response |
|---|---|
| Cycle does not exist | `404` |
| Cycle is not `DRAFT` | `409` |
| No configured KPIs or no assigned employees | `400` |
| Invalid or non-100% weightages | `400` |
| A configured KPI is no longer active | `409` |
| An assigned employee/manager is missing, inactive, disabled, the wrong role, self-assigned, or duplicated | `400`/`409` |

The response is the updated `CycleResponse`.

## Employee self-assessment APIs

All routes require the `EMPLOYEE` role. The employee is derived from the
authenticated principal; no employee ID is accepted. Only assignments whose
cycle is exactly `PUBLISHED` are visible, and another employee's assignment
returns the same `404` as a missing one.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/employee/self-assessments/cycles` | List the employee's published-cycle assignments |
| `GET` | `/api/employee/self-assessments/{assignmentId}` | Read the assignment and saved self-assessment |
| `GET` | `/api/employee/self-assessments/{assignmentId}/status` | Read progress and submission status |
| `PUT` | `/api/employee/self-assessments/{assignmentId}/draft` | Save or update self-assessment entries |
| `POST` | `/api/employee/self-assessments/{assignmentId}/submit` | Submit the saved draft to the manager |

The queue returns `EmployeeCycleResponse` values with `assignmentId`, `cycleId`,
`cycleName`, `description`, `startDate`, `endDate`, `selfAssessmentDeadline`,
`managerReviewDeadline`, `hrReviewDeadline`, `cycleStatus`, `publishedAt`,
`assignmentStatus` and `selfSubmittedAt`.

The detail response contains the cycle header, `assignmentStatus`,
`selfSubmittedAt`, `editable`, and `kpis` with `employeeKpiId`, `kpiId`, `name`,
`description`, `category`, `weightage`, `rating` and `comment`. `editable` is
true only while the assignment is `SELF_ASSESSMENT_PENDING`. Self-ratings are
reference-only and do not affect the final score.

Draft saving accepts:

```json
{
  "kpis": [
    { "employeeKpiId": 100, "rating": 4, "comment": "Delivered on time." }
  ]
}
```

`kpis` is required; an empty list is a valid no-op. Entries upsert the included
KPIs and KPIs omitted from the list keep their prior values. `rating` is
nullable while drafting but must be a whole number from 1 through 5; decimals
are rejected. `comment` is optional and blank values are stored as `null`.
Unknown `employeeKpiId`, duplicate IDs and invalid ratings return `400`. Draft
saving after submission returns `409`.

Submission takes no body. Every assigned KPI must already have a saved rating;
otherwise `409`. It records `selfSubmittedAt`, moves the assignment from
`SELF_ASSESSMENT_PENDING` to `MANAGER_REVIEW_PENDING`, and returns
`SelfAssessmentStatusResponse` with `assignmentId`, `cycleId`, `cycleStatus`,
`assignmentStatus`, `submitted`, `selfSubmittedAt`, `totalKpis` and `ratedKpis`.
Submission does not change the cycle status.

## Manager assessment APIs

All routes require the `MANAGER` role. The manager is derived from the
authenticated principal. Visibility uses the assignment's `manager_id` snapshot,
not the employee's current reporting line.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/manager/assessments` | List assignments awaiting manager review |
| `GET` | `/api/manager/assessments/{assignmentId}` | Read the assessment review screen |
| `GET` | `/api/manager/assessments/{assignmentId}/status` | Read progress and submission status |
| `PUT` | `/api/manager/assessments/{assignmentId}/draft` | Save or update manager entries |
| `POST` | `/api/manager/assessments/{assignmentId}/submit` | Submit the saved draft to HR |

The queue returns assignments in a `PUBLISHED` cycle at
`MANAGER_REVIEW_PENDING`. `ManagerQueueItemResponse` contains `assignmentId`,
`employeeId`, `employeeCode`, `employeeName`, `cycleId`, `cycleName`,
`selfSubmittedAt` and `assignmentStatus`.

The detail response adds cycle dates and the manager-review deadline, the
`editable` flag, `employee` identity (`employeeId`, `employeeCode`, `fullName`,
`department`, `designation`) and `kpis` with the frozen snapshot plus
`selfRating`, `selfComment`, `managerRating` and `managerComment`. The manager
can still read the review at `HR_REVIEW_PENDING`, `COMPLETED` and
`RESULTS_PUBLISHED`, but `editable` is true only at `MANAGER_REVIEW_PENDING`.

Draft saving uses the same `{ "kpis": [{ "employeeKpiId", "rating", "comment" }] }`
shape and the same null/whole-number 1–5, unknown-ID and duplicate-ID rules as
the employee draft. Empty drafts are valid. Submission requires a saved manager
rating for every KPI and no body. It records `managerSubmittedAt`, moves the
assignment from `MANAGER_REVIEW_PENDING` to `HR_REVIEW_PENDING`, and returns
`ManagerAssessmentStatusResponse` with `assignmentId`, `cycleId`,
`assignmentStatus`, `totalKpis`, `ratedKpis`, `submitted` and
`managerSubmittedAt`. Draft or submit calls after submission return `409`.

## HR assessment APIs

All routes require the `HR` role. HR reviews every eligible employee, so there
is no ownership predicate. Only assignments in a `PUBLISHED` cycle that have
reached `HR_REVIEW_PENDING` (or later, for read-only review) are visible. An
unknown, ineligible or hidden assignment returns the same `404`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/hr/assessments` | List assignments waiting for HR review |
| `GET` | `/api/hr/assessments/{assignmentId}` | Read the three-perspective review |
| `GET` | `/api/hr/assessments/{assignmentId}/status` | Read progress and finalization status |
| `PUT` | `/api/hr/assessments/{assignmentId}/draft` | Save or update HR ratings/comments |
| `POST` | `/api/hr/assessments/{assignmentId}/submit` | Finalize HR review and generate a DRAFT result |

The queue contains assignments at `HR_REVIEW_PENDING`. `HrQueueItemResponse`
contains `assignmentId`, `employeeId`, `employeeCode`, `employeeName`,
`department`, `designation`, `cycleId`, `cycleName`, `managerSubmittedAt` and
`assignmentStatus`.

The detail response adds cycle dates, the HR review deadline,
`selfSubmittedAt`, `managerSubmittedAt`, `hrFinalizedAt`, `editable`, the
employee summary and `kpis` with `selfRating`, `selfComment`, `managerRating`,
`managerComment`, `hrRating` and `hrComment`. `editable` is true only while the
assignment is `HR_REVIEW_PENDING`.

Drafts use `{ "kpis": [{ "employeeKpiId", "rating", "comment" }] }` and accept
partial or empty entries; omitted KPIs retain prior values. Ratings are whole
numbers 1–5, comments are optional, and unknown or duplicate IDs return `400`.
An empty list is a valid no-op. Submission takes no body and requires an HR
rating plus a manager rating for every KPI; otherwise `409`. It records
`hr_finalized_at`, changes the assignment to `COMPLETED`, and generates one
`DRAFT` performance result in the same transaction. Repeated submission or a
post-finalization draft edit returns `409 Conflict`. The status response
contains `assignmentId`, `cycleId`, `assignmentStatus`, `totalKpis`, `ratedKpis`,
`submitted` and `hrFinalizedAt`.

## HR result publication APIs

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/hr/results` | List eligible DRAFT results awaiting publication |
| `GET` | `/api/hr/results/{resultId}` | Read a result, including before publication |
| `POST` | `/api/hr/results/{resultId}/publish` | Publish one finalized result |
| `POST` | `/api/hr/results/cycles/{cycleId}/publish` | Publish eligible results in a cycle |

All routes require the `HR` role. Individual publication requires an existing
DRAFT result, a `COMPLETED` assignment with `hr_finalized_at`, no prior
publication timestamps/fields, and a cycle in `PUBLISHED`. It changes only
publication status/metadata: scores, result items, assessments, and frozen KPI
snapshots are not recalculated or changed. Repeated individual publication
returns `409 Conflict`.

Bulk publication is **partial-success, skip-ineligible**. It locks the cycle
then all assignments in ascending assignment ID order. A result is published
only when its assignment is completed/finalized and it has an unpublished DRAFT
result. Pending assignments, completed assignments with no result, and already
published or inconsistent rows are skipped; missing results are never created.
The response contains `cycleId`, `publishedCount`, `skippedCount`, and the IDs
published by that call. Repeating the operation is safe: previously published
rows are skipped. The eligible changes commit together; a persistence failure
rolls back the operation. A missing cycle returns `404`; a cycle not in
`PUBLISHED` returns `409`.

## Employee result APIs

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/employee/results` | List the authenticated employee's published results |
| `GET` | `/api/employee/results/{resultId}` | Read one of the employee's published results |

These routes require the `EMPLOYEE` role. The employee is resolved from the
authenticated principal; no employee or user ID is accepted. A result is
returned only when the result status is `PUBLISHED`, its assignment is
`RESULTS_PUBLISHED`, and both the result `published_at` and assignment
`results_published_at` are non-null and equal. Draft, inconsistent, unknown,
and another employee's results return `404`.

## Manager result APIs

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/manager/results` | List published results for the authenticated manager's historical team |
| `GET` | `/api/manager/results/{resultId}` | Read one published result from that manager's historical team |

These routes require the `MANAGER` role and use the `manager_id` snapshot on
the assignment for that cycle, not the employee's current manager. Published
result/assignment statuses and both non-null publication timestamps are
required, and the timestamps must be equal. Draft, inconsistent, unknown, and
results assigned to another manager return `404`.

## Result response

Result responses are DTOs, not entities. They include result and assignment
IDs, employee and cycle summary information, stored overall score, grade,
calculation mode, result status, HR finalization time, publication time, and
the frozen per-KPI names, weights, ratings, comments, and stored final scores.
Retrieval does not recalculate scores or substitute current KPI master data.

## Result UI

The frontend result routes use the endpoints above without adding any new
backend behavior:

- `/hr/results` lists the backend-eligible DRAFT results, grouped by cycle, and
  `/hr/results/:resultId` shows one result with individual publication while
  its status is `DRAFT`. Bulk publication is offered per cycle because
  `POST /api/hr/results/cycles/{cycleId}/publish` exists.
- `/employee/results` and `/employee/results/:resultId` show only the
  authenticated employee's published results returned by the backend.
- `/manager/results` and `/manager/results/:resultId` show only published
  results returned for the assignment manager snapshot. The frontend sends no
  employee, manager, or user identity.

Publication actions require confirmation, disable duplicate submissions while
pending, and refresh the affected result or queue after completion. The UI
displays backend `400`, `404`, and `409` messages and does not derive
eligibility or scores locally. All scores, grades, weights, KPI final scores,
and ratings are displayed exactly as supplied; missing values are not replaced
with zero, and employee self-ratings are displayed as reference-only.

## Errors and authorization

Errors use the shared response shape:

```json
{
  "timestamp": "2026-01-01T00:00:00Z",
  "status": 409,
  "error": "CONFLICT",
  "message": "The performance result has already been published or is inconsistent.",
  "path": "/api/hr/results/42/publish"
}
```

Values above are illustrative. Authentication and role failures return
`401`/`403`; unknown or hidden results return `404`; workflow conflicts return
`409`. Error responses do not include stack traces or database details.

## Swagger / OpenAPI documentation

Evalora provides an OpenAPI 3 document and Swagger UI, powered by
`springdoc-openapi-starter-webmvc-ui` 3.2.0 and configured through
`com.evalora.backend.config.OpenApiConfig`.

| Resource | URL | Authentication |
|----------|-----|----------------|
| OpenAPI document (JSON) | `GET /v3/api-docs` | None (public) |
| Swagger UI | `GET /swagger-ui.html` | None (public) |
| Swagger UI (index) | `GET /swagger-ui/index.html` | None (public) |

### JWT authorization in Swagger UI

1. Obtain a JWT token via `POST /api/auth/login` with email and password.
2. Open Swagger UI at `/swagger-ui.html`.
3. Click **Authorize** (or the lock icon).
4. Enter `Bearer <token>` (the word `Bearer`, a space, then the token value).
5. Click **Authorize**, then **Close**.

All subsequent requests from Swagger UI include the
`Authorization: Bearer <token>` header.

### Role-based access

Swagger UI documents the authentication requirement (HTTP Bearer, format JWT).
Role-based authorization is enforced by `SecurityConfig`, not by OpenAPI:

- `/api/hr/**` requires `ROLE_HR`
- `/api/employee/**` requires `ROLE_EMPLOYEE`
- `/api/manager/**` requires `ROLE_MANAGER`
- `POST /api/auth/login`, `POST /api/auth/forgot-password` and
  `POST /api/auth/reset-password` are public (no authentication required)

The public auth endpoints are explicitly marked as not requiring security in
the OpenAPI document; every other endpoint is documented as requiring bearer
authentication.

### Intended use

Swagger UI is intended for local development. It should only be exposed in a
suitably secured environment (for example, behind a VPN or restricted to
`localhost`) and is not intended for production use without additional access
controls.
