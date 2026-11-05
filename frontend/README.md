# Evalora frontend setup

The frontend is a React and JavaScript single-page application built with
Vite. It provides authentication, role-specific dashboards, HR administration
for staff, KPIs and performance cycles, cycle configuration and publishing,
and the employee, manager and HR assessment workflows.

## Requirements

- Node.js 20.19+ or 22.12+ (the current Vite release requirement)
- npm
- The Evalora Spring Boot backend for live authentication

## Install and configure

From this directory:

```powershell
npm install
Copy-Item .env.example .env.local
```

Set `VITE_API_BASE_URL` in `.env.local` to the backend API root. The default
development value is `http://localhost:8080/api`. The backend allows the local
Vite origin by default; in other environments it is controlled by the
comma-separated `EVALORA_CORS_ALLOWED_ORIGINS` setting. Keep Vite on the
configured origin, or update the backend CORS setting to match your dev server.

## Start

```powershell
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`).
Sign-in requires a real backend account; no demo credentials are provided.

## Authentication

The frontend calls `POST /auth/login`, `GET /auth/me`, and
`POST /auth/logout` through one Axios client. `src/api/authApi.js` also exposes
`PUT /auth/change-password`, but no user-facing page currently calls it; the
frontend has no change-password screen. The role and identity come from backend
responses; the frontend does not choose a role or treat a hidden link as
authorization.

The JWT is kept in `localStorage` under the single key
`evalora.auth.token`, and is sent as `Authorization: Bearer <token>`. This
matches the backend's stateless bearer-token contract and allows a page
reload to restore the current user through `/auth/me`. Logout calls the
backend and clears the local token. The backend logout is stateless and does
not revoke the JWT, which remains valid until expiry if it has been copied.

`localStorage` is accessible to JavaScript running on the page and does not
protect tokens from cross-site scripting. Use only in this local-development
foundation; production token-storage policy requires a separate security
decision.

The current-user DTO contains only `id`, `email`, and `role`; it does not
contain a full name. The navigation displays the backend-provided email and
role rather than inventing a name field.

## Dashboards and navigation

After authentication, the backend-provided role determines the destination:

| Role | Route | API endpoint |
|---|---|---|
| HR | `/hr/dashboard` | `GET /api/hr/dashboard` |
| MANAGER | `/manager/dashboard` | `GET /api/manager/dashboard` |
| EMPLOYEE | `/employee/dashboard` | `GET /api/employee/dashboard` |

The root route redirects to the matching role dashboard. Protected routes
check the authenticated user's backend-provided role; a forbidden role sees an
access-denied page. HR navigation includes Dashboard, Employees, Managers,
KPIs, Performance Cycles, HR Assessments, and Results. MANAGER navigation
includes Dashboard, Assessments, and Results; EMPLOYEE navigation includes
Dashboard, Self-assessments, and Results. The backend remains the authorization
boundary.

The HR dashboard displays employee and manager totals, cycle totals, pending
self/manager/HR assessment counts, completed assessments and published
results. The manager dashboard displays active direct reports and its
assessment workload and completion/result counts. The employee dashboard
shows the current cycle and assessment status, optional due date, and latest
published result. The employee page displays explicit empty states when no
current cycle/assessment or published result is available; draft or otherwise
unpublished results are not requested or shown.

Dashboard API requests use the shared Axios client and its bearer-token
interceptor. Pages show loading and safe error states with a retry action.
HTTP 401 follows the existing session-clearing behavior; HTTP 403 shows an
access-denied message without signing the user out. Network errors explain
that the backend may not be running. Dashboard data is loaded when its page
is opened and is not cached.

## HR employee and manager management

HR-only routes:

| Screen | Route |
|---|---|
| Employees list | `/hr/employees` |
| Create employee | `/hr/employees/new` |
| Employee details | `/hr/employees/:id` |
| Edit employee | `/hr/employees/:id/edit` |
| Managers list | `/hr/managers` |
| Create manager | `/hr/managers/new` |
| Manager details | `/hr/managers/:id` |
| Edit manager | `/hr/managers/:id/edit` |

These pages use the shared Axios client and the backend staff API:

| Operation | Endpoint |
|---|---|
| Create employee / manager | `POST /api/hr/employees`, `POST /api/hr/managers` |
| List employees / managers | `GET /api/hr/employees?status=ACTIVE`, `GET /api/hr/managers?status=ACTIVE` |
| Read employee / manager | `GET /api/hr/employees/{employeeId}`, `GET /api/hr/managers/{managerId}` |
| Update employee / manager | `PUT /api/hr/employees/{employeeId}`, `PUT /api/hr/managers/{managerId}` |
| Assign employee manager | `PUT /api/hr/employees/{employeeId}/manager` |
| Deactivate / reactivate | `PATCH /api/hr/{employees|managers}/{id}/{deactivate|reactivate}` |

`status` supports `ACTIVE` and `INACTIVE`. The search box filters the returned
list in the browser; the backend does not provide search or pagination. Employee
creation loads active managers for the optional reporting-manager selection.
Manager accounts cannot be assigned a manager.

Creation sends the backend `CreateStaffRequest` fields: `firstName`, `lastName`,
`email`, `employeeCode`, optional `department`, `designation`, `joiningDate`,
required `initialPassword`, and optional `managerId` for employees. The role is
never included in the request body: the employee and manager endpoints choose
`EMPLOYEE` and `MANAGER` respectively, and creation never depends on a
follow-up request. The optional employee `managerId` is applied in that same
POST. Client-side checks validate required names/code/email, DTO length limits,
and the backend password rule (8+ characters with a letter, number, and special
character). The server remains the source of truth for validation and
uniqueness.

Updates send only `firstName`, `lastName`, `email`, `employeeCode`,
`department`, `designation`, and `joiningDate`, matching `UpdateStaffRequest`.
Role, account status, password, user ID, and manager assignment are not
included in profile updates. The response exposes profile IDs and safe profile
details, but no password hash, token, or lockout state.

The staff response provides only a combined `fullName`, so the edit form's
first/last split is inherently ambiguous. HR must review the original and
resulting full name and explicitly verify the split before saving. Changing
either name field clears that verification and requires HR to confirm again.

Status changes require a confirmation prompt and are disabled while pending.
Successful changes refresh the current view. Backend conflicts (including a
manager with active direct reports) are shown without retrying, deleting
records, or reassigning reports. Deactivation and reactivation use the
backend's soft status operations. Reactivating an employee does not restore a
previous manager assignment; HR must explicitly assign an active manager
through the dedicated endpoint when appropriate.

HR can also reassign an employee's reporting manager from the employee detail
page. The page shows the current reporting manager and loads
`GET /api/hr/managers?status=ACTIVE` when the reassignment editor is opened;
only active manager accounts with enabled logins are offered. Reassignment uses
only `PUT /api/hr/employees/{employeeId}/manager` with
`{ "managerId": <id> }`; it sends no profile fields and does not modify
reporting history directly. The backend requires an active employee and an
active manager account, rejects self-assignment, and requires `managerId`, so
the UI does not offer manager removal. Backend validation and conflict messages
are displayed, the selected manager is preserved after a failure, duplicate
submissions are disabled while pending, and a successful change refreshes the
employee details.

`docs/API_DOCUMENTATION.md` documents these HR staff endpoints. The frontend
contracts above were verified against `HrEmployeeController`,
`EmployeeManagementService`, and the request/response DTOs in backend source;
the frontend follows those source contracts.

Run the existing frontend suite with `npm test`; staff API calls and UI
workflows are mocked in Vitest and do not claim live backend verification.

## HR KPI management

HR-only routes:

| Screen | Route |
|---|---|
| KPI list | `/hr/kpis` |
| Create KPI | `/hr/kpis/new` |
| KPI details | `/hr/kpis/:id` |
| Edit KPI | `/hr/kpis/:id/edit` |

The HR navigation includes KPIs. MANAGER and EMPLOYEE navigation and access
remain unchanged. The KPI pages use the shared Axios client through
`src/api/kpiApi.js`:

| Function | Endpoint |
|---|---|
| `getKpis()` | `GET /api/hr/kpis` |
| `getKpis(true)` / `getKpis(false)` | `GET /api/hr/kpis?active=true` / `?active=false` |
| `getKpiById(id)` | `GET /api/hr/kpis/{id}` |
| `createKpi(payload)` | `POST /api/hr/kpis` |
| `updateKpi(id, payload)` | `PUT /api/hr/kpis/{id}` |
| `deactivateKpi(id)` / `reactivateKpi(id)` | `PATCH /api/hr/kpis/{id}/deactivate` / `.../reactivate` |

The list supports All, Active, and Inactive backend filters plus browser-side
search by name or category. It does not paginate. Forms send only `name`,
`description`, `category`, and `defaultWeightage`; optional blank description,
category, and weightage are sent as `null`. Client-side validation matches the
backend rules: trimmed required name up to 150 characters, optional category
up to 80 characters, and optional weightage greater than 0 through 100 with at
most two decimal places. The backend creates KPIs active by default. Status
changes require confirmation, disable duplicate submissions while pending,
and refresh the list or details after success. Backend conflict messages are
shown without optimistic status changes or hard deletion.

The backend KPI paths and DTO fields were verified from `HrKpiController`,
`KpiManagementService`, `CreateKpiRequest`, `UpdateKpiRequest`, and
`KpiResponse`. `docs/API_DOCUMENTATION.md` documents the KPI catalogue
endpoints; the frontend follows the implementation and the existing KPI
security documentation.

Frontend tests use Vitest and React Testing Library with mocked API calls:

```powershell
npm test
```

## HR performance cycle management

HR-only routes:

| Screen | Route |
|---|---|
| Cycle list | `/hr/cycles` |
| Create cycle | `/hr/cycles/new` |
| Cycle details | `/hr/cycles/:id` |
| Edit cycle | `/hr/cycles/:id/edit` |

Only HR navigation includes Performance Cycles; the routes use the existing
authenticated role guard. The UI uses the shared Axios client through
`src/api/cycleApi.js`:

| Function | Endpoint |
|---|---|
| `getCycles()` | `GET /api/hr/cycles` |
| `getCycleById(id)` | `GET /api/hr/cycles/{id}` |
| `createCycle(payload)` | `POST /api/hr/cycles` |
| `updateCycle(id, payload)` | `PUT /api/hr/cycles/{id}` |

Create and edit forms send only `name`, `description`, `startDate`, `endDate`,
`selfAssessmentDeadline`, `managerReviewDeadline`, and `hrReviewDeadline`.
Names are trimmed, required, and limited to 150 characters. Blank descriptions
are sent as `null`. Dates use ISO `YYYY-MM-DD` strings, matching the backend's
`LocalDate` contract. The form enforces the backend date rules: start precedes
end, the self-assessment deadline is within the cycle dates, the manager
deadline is on or after the self deadline, and the HR deadline is on or after
the manager deadline. All five dates are required.

The backend creates cycles with `DRAFT` status. Only a DRAFT cycle can be
updated; the UI hides edit actions for other statuses and also blocks a direct
edit route for them. If a cycle changes status before an update, the backend
409 response is displayed while preserving form entries. 404 and other API
errors use the existing safe request-state/error handling. Status and available
creation/publication/completion timestamps are displayed from the response.

The cycle detail section also shows KPI assignments and supports KPI
configuration for DRAFT cycles; see the HR KPI assignment section below.
Employee assignment is described below. Cycle publishing is available only
for DRAFT cycles after the KPI and employee summary has loaded and both counts
are greater than zero. If either count is zero, the page explains what HR must
configure and leaves publication disabled. Publication also stays disabled
while the summary is loading or unavailable; a summary load failure is shown
with a retry action. The page shows the configured KPI and assigned employee
counts, then asks HR to confirm the cycle name and counts before making the
publish request. The confirmation warns that publishing starts the cycle,
freezes KPI snapshots for each employee, and makes the draft configuration
immutable; publication should be treated as potentially irreversible.

Publishing uses `POST /api/hr/cycles/{id}/publish` through
`publishCycle(id)` in `src/api/cycleApi.js`. On success, cycle details and
assignment information refresh from the backend, the returned cycle status is
displayed, and draft-only edit/publish actions disappear. Backend validation
remains authoritative: for example, it rejects publication without configured
KPIs or assigned employees, with invalid weightages, inactive KPIs, or
inactive/disabled assigned staff. A 409 refreshes the latest cycle and
assignment information; other errors are displayed without discarding the
loaded cycle details. Tests mock publish and refresh calls and do not claim
live backend integration.

Tests mock cycle APIs and cover request contracts, route protection, listing,
validation, create, details, draft updates, conflicts, and publication. Run the
frontend suite with `npm test`; run `npm run lint` and `npm run build` for
linting and production compilation.

## HR KPI assignment to cycles

KPI assignments are managed in the **Assigned KPIs** section of
`/hr/cycles/:id`; no additional route is needed. The existing cycle detail page
uses the shared Axios client through `src/api/cycleApi.js`:

| Function | Endpoint |
|---|---|
| `getCycleKpis(id)` | `GET /api/hr/cycles/{id}/kpis` |
| `configureCycleKpis(id, kpis)` | `POST /api/hr/cycles/{id}/kpis` with `{ "kpis": [...] }` |

The POST replaces the full KPI selection. Each item contains only `kpiId` and
`weightage`; no mode enum or per-KPI delete endpoint exists. The assignment
manager loads active catalogue entries through `getKpis(true)`. Existing
assignments are shown from the cycle snapshot response, and currently assigned
KPIs that are no longer active are identified so HR can remove them from the
replacement selection. Duplicate entries are prevented by the selection UI.

Choose one explicit weightage mode:

- **Configure weightages:** every selected KPI must have a positive value no
  greater than 100, with at most two decimal places. The total must equal
  exactly 100.00%. Running total and remainder are displayed.
- **Use equal/unweighted scoring:** all selected KPI weightages are sent as
  `null`, so the backend uses its average mode.

Switching modes clears all weightage inputs; partial weighted/unweighted mixes
are rejected. The UI requires at least one KPI selection before saving. The
backend also permits an empty replacement to clear a cycle's KPI collection,
but this UI intentionally follows the requested non-empty-selection rule.

KPI assignment is available only while the cycle is `DRAFT`. Other statuses
show the assigned KPI snapshots read-only. Before replacing the selection, the
frontend rechecks the cycle status; the backend remains authoritative for
concurrent status changes. A 409 triggers cycle and assignment refresh while
retaining the attempted selection and values; if the cycle became non-DRAFT,
the retained editor is read-only and saving is disabled. API errors, including
404, are shown without discarding edited inputs. Saving disables
duplicate submissions and refreshes the assigned KPI data.

The cycle API tests verify the read/replacement URLs, HTTP methods, request
payloads including null weightages, response data, and error propagation.
Cycle UI tests use mocked API requests to cover role protection, assigned and
active KPI display, both weightage modes, selection and validation rules,
replacement success, conflict handling, and read-only non-DRAFT cycles.

## HR employee assignment to cycles

Employee assignment is managed in the **Assigned Employees** section of
`/hr/cycles/:id`; it does not add a separate route. The cycle detail page uses
the shared Axios client through `src/api/cycleApi.js`:

| Function | Endpoint |
|---|---|
| `getCycleEmployees(id)` | `GET /api/hr/cycles/{id}/employees` |
| `configureCycleEmployees(id, employees)` | `PUT /api/hr/cycles/{id}/employees` with `{ "employees": [...] }` |

The PUT replaces the full employee roster. Each request item contains
`employeeId` and the selected `managerId`. Existing assignments retain their
original cycle manager ID unless HR explicitly changes it. Newly selected
employees default to their current manager when that manager is active and
enabled; HR can choose another active manager for the cycle without changing
the employee's reporting history. An employee with no active current manager
remains selectable but requires HR to choose a manager. Manager ownership is
displayed from the assignment response.

The selector loads `GET /api/hr/employees?status=ACTIVE` and
`GET /api/hr/managers?status=ACTIVE`. It offers only employees with an active
employee profile and enabled account; this endpoint provides employee-role
profiles. It offers managers with an active profile and enabled account.
Employees without an active current manager are not excluded. Existing
assignments are retained in the editor even when the employee is no longer
eligible. Their original reviewing-manager snapshot is also retained rather
than silently replaced by the employee's current manager. If that cycle
manager is inactive or disabled, the editor flags it and lets HR choose an
active manager to retain the employee. The backend remains authoritative and
can reject concurrent status or manager changes; the error is shown while
preserving all roster and manager selections.

Saving an empty selection clears the roster, as supported by the backend.
The interface makes the full-replacement behavior and empty-roster effect
explicit. It prevents duplicate employees and requires every selected employee
to have an active, enabled manager before saving. It searches by employee name,
code, or department, preserves user choices after errors, and disables repeated
submissions while a request is pending.

Employee assignment is available only while the cycle is `DRAFT`. The editor
rechecks cycle status before saving, and a backend conflict refreshes cycle and
assignment state without discarding selections. If the cycle leaves `DRAFT`
while the editor is open, the editor becomes read-only, save is disabled, and
the close control remains available so HR can dismiss it without changing the
roster. Other cycle statuses show the assignment list read-only. Tests mock
cycle, employee, and manager API calls; they cover the replacement contract,
employee eligibility, default and selectable managers, manager snapshot
preservation/change, intentional removal, validation, search, errors, cycle
conflicts, closable read-only editors, and non-DRAFT read-only access. For
publication workflow and backend publication validations, see
**HR performance cycle management** above.

## Employee self-assessment

Authenticated employees can view their published assignments at
`/employee/self-assessments` and open an assignment at
`/employee/self-assessments/:assignmentId`. The employee-only navigation and
role-protected routes reuse the existing dashboard layout and authentication
guard. The API derives employee identity from the authenticated user; the
frontend never sends an employee ID. The backend returns only the employee's
own assignments for cycles in `PUBLISHED` status, and inaccessible assignments
are reported as not found.

The employee page displays cycle dates, deadline, assignment status, and the
frozen KPI snapshot fields returned by the backend (name, description,
category, and weightage). Each KPI has a numeric rating selector with values
1–5 and an optional comment. The backend defines no comment length limit, so
the frontend does not impose one. KPI definitions, weightages, and manager
assignments are read-only. Self-ratings are reference-only and do not
contribute to the final score.

The employee API contracts, implemented in `src/api/selfAssessmentApi.js`, are:

| Operation | Endpoint | Request |
|---|---|---|
| List the authenticated employee's published assignments | `GET /api/employee/self-assessments/cycles` | None |
| Read one assignment and saved KPI ratings/comments | `GET /api/employee/self-assessments/{assignmentId}` | None |
| Read assignment submission status | `GET /api/employee/self-assessments/{assignmentId}/status` | None |
| Save or update a draft | `PUT /api/employee/self-assessments/{assignmentId}/draft` | `{ "kpis": [{ "employeeKpiId", "rating", "comment" }] }` |
| Submit the saved draft | `POST /api/employee/self-assessments/{assignmentId}/submit` | No body |

Draft saving is separate from submission and accepts incomplete ratings,
including an empty draft. Each draft entry may contain a nullable rating and
comment; ratings must be whole numbers from 1 through 5. The backend upserts
only the submitted KPI entries, so the page sends the full current form on
each save. Submission first saves the current form, then calls the distinct
submit endpoint. The submit request succeeds only when every assigned KPI has
a valid saved rating. A confirmation explains that submission is sent to the
assigned manager. The backend transitions the assignment from
`SELF_ASSESSMENT_PENDING` to `MANAGER_REVIEW_PENDING` and records its
submission time; the cycle remains `PUBLISHED`. After submission the form is
read-only and there is no resubmit action.

The backend contract was verified against `EmployeeSelfAssessmentController`,
`SelfAssessmentService`, its request/response DTOs, assignment status enum,
and `EmployeeSelfAssessmentIntegrationTest`. API and UI tests mock requests;
they do not claim live backend integration. Run `npm test` for the frontend
suite.

## HR assessment

HR can view the review queue at `/hr/assessments` and open an assignment at
`/hr/assessments/:assignmentId`. Both routes require the authenticated `HR`
role. The backend derives HR identity from authentication and returns only
eligible assignments: a published cycle with submitted employee and manager
assessments in `HR_REVIEW_PENDING`. Ineligible assignments are not offered by
the queue and inaccessible details return not found.

The queue shows employee identity, department/designation when available,
cycle, assignment status, and manager submission time. Cycle dates and the HR
deadline are shown in assessment details. The detail page displays frozen KPI
snapshots alongside read-only employee self-ratings/comments and read-only
manager ratings/comments. Only HR ratings and optional comments are editable
while the backend marks the assessment editable. Ratings are numeric 1–5;
comments are optional and have no backend-defined length restriction.

The HR API contracts in `src/api/hrAssessmentApi.js` are:

| Operation | Endpoint | Request |
|---|---|---|
| List the authenticated HR review queue | `GET /api/hr/assessments` | None |
| Read one HR assessment | `GET /api/hr/assessments/{assignmentId}` | None |
| Read assessment status | `GET /api/hr/assessments/{assignmentId}/status` | None |
| Save or update an HR draft | `PUT /api/hr/assessments/{assignmentId}/draft` | `{ "kpis": [{ "employeeKpiId", "rating", "comment" }] }` |
| Finalize the assessment | `POST /api/hr/assessments/{assignmentId}/submit` | No body |

Draft saving is separate from finalization and accepts empty or incomplete
ratings. The page sends every KPI in the draft; null ratings are allowed.
Finalization requires a rating for every KPI and asks for confirmation. If the
form has unsaved edits, the page first saves the draft, then calls the distinct
finalization endpoint. The backend transitions `HR_REVIEW_PENDING` to
`COMPLETED`, persists its unpublished performance result, and returns status
only. The page displays the completion status and makes fields read-only; it
does not calculate scores, expose result values, or implement result
publication. Backend scoring remains authoritative: manager ratings contribute
75%, HR ratings 25%, and employee self-ratings 0%.
The detail page refreshes status after finalization; returning to the queue
loads the current backend queue again.

The contracts were verified against `HrAssessmentController`,
`HrAssessmentService`, HR request/response DTOs, `SecurityConfig`, and
`HrAssessmentIntegrationTest`. API and component tests mock requests and do
not claim live backend integration. Run `npm test` for the frontend suite.

## Manager assessment

Managers can view their review queue at `/manager/assessments` and open an
assignment at `/manager/assessments/:assignmentId`. Both routes require the
authenticated `MANAGER` role and use the shared dashboard layout. The frontend
sends no manager identity; the backend derives it from the authenticated user
and scopes each assignment to the reviewing manager snapshot. The queue
contains only assignments awaiting manager review after the employee has
submitted. Other managers' and ineligible assignments are hidden by the
backend as not found.

The queue displays employee name/code, cycle name, assignment status, and the
employee submission timestamp. `ManagerQueueItemResponse` does not include
cycle dates; those dates and the manager-review deadline are shown after
opening the assessment, using `ManagerAssessmentResponse`. The detail page
shows employee identity, cycle details and frozen KPI snapshots. Employee
self-ratings and comments are visibly marked read-only and reference-only.
Managers can select numeric ratings 1–5 and add optional comments. The backend
defines no comment length limit. KPI snapshots, employee ratings/comments,
cycle data, and manager assignment are not editable. Manager ratings carry
75% of the final score; employee ratings are reference-only at 0%. No scoring
is calculated in this phase.

The manager API contracts, implemented in `src/api/managerAssessmentApi.js`,
are:

| Operation | Endpoint | Request |
|---|---|---|
| List the authenticated manager's pending queue | `GET /api/manager/assessments` | None |
| Read one manager assessment | `GET /api/manager/assessments/{assignmentId}` | None |
| Read assignment assessment status | `GET /api/manager/assessments/{assignmentId}/status` | None |
| Save or update a draft | `PUT /api/manager/assessments/{assignmentId}/draft` | `{ "kpis": [{ "employeeKpiId", "rating", "comment" }] }` |
| Submit the saved draft to HR | `POST /api/manager/assessments/{assignmentId}/submit` | No body |

Draft saving is separate from submission and accepts partial or empty drafts;
ratings, when supplied, must be whole numbers from 1 through 5. Draft entries
upsert only the included KPI values. Submission requires every assigned KPI
to have a saved rating. The UI requires the current edits to be saved before
submission, then asks for confirmation and calls the separate submit endpoint
without silently saving. Successful submission transitions the assignment
from `MANAGER_REVIEW_PENDING` to `HR_REVIEW_PENDING`, records the manager
submission timestamp, refreshes the status, and disables further editing.
Conflicts refresh assignment state while retaining values in the form.

The backend contract was verified against `ManagerAssessmentController`,
`ManagerAssessmentService`, its DTOs and repositories, `EmployeeAssignment`
and `AssignmentStatus`, `SecurityConfig`, and
`ManagerAssessmentIntegrationTest`. API and component tests mock requests and
do not claim live backend integration.

## Performance results

Result pages use `src/api/resultApi.js` and the shared result display
components. The backend derives HR, employee, and manager identity from the
authenticated principal; the frontend never sends a role or user ID. The
verified contracts are:

| Operation | Endpoint | Request |
|---|---|---|
| List HR results awaiting publication | `GET /api/hr/results` | None |
| Read one result as HR | `GET /api/hr/results/{resultId}` | None |
| Publish one result | `POST /api/hr/results/{resultId}/publish` | No body |
| Publish eligible cycle results | `POST /api/hr/results/cycles/{cycleId}/publish` | No body |
| List the employee's published results | `GET /api/employee/results` | None |
| Read one published employee result | `GET /api/employee/results/{resultId}` | None |
| List the manager's published team results | `GET /api/manager/results` | None |
| Read one published manager team result | `GET /api/manager/results/{resultId}` | None |

HR result management is available at `/hr/results` and
`/hr/results/:resultId`. The list contains only the DRAFT results the backend
returns as eligible for publication, grouped by performance cycle. Each cycle
group has a bulk action that calls the cycle publication endpoint. The detail
page shows the backend-provided overall score, grade, calculation mode, KPI
names, weightages, ratings, comments, and final KPI scores, and offers
individual publication only while the result status is `DRAFT`. Publication is
confirmation-protected; submission locks prevent duplicate requests; a
successful publication refreshes the affected result or queue. Bulk responses
report the backend's `publishedCount`, `skippedCount`, and published result IDs.
Skipped and already-published results are not treated as failures. Backend
`400`, `404`, and `409` messages are shown, and conflicts/unknown results
refresh the affected state. The frontend never publishes based on a locally
derived score or status.

Employees see their published results at `/employee/results` and
`/employee/results/:resultId`. The backend returns only the authenticated
employee's own published results; unknown, draft, inconsistent, and other
employees' results are hidden as `404`. Managers see published results at
`/manager/results` and `/manager/results/:resultId`. The backend scopes them to
the manager snapshot stored on the assignment for that cycle, not the
employee's current manager. Neither page sends an employee selector or manager
ID and neither page offers publication, editing, or resubmission controls.
Unavailable detail results show a not-found state with a retry action; loading,
empty, authorization, and transport errors use the existing management request
states.

All displayed scores are stored backend values. The result components do not
calculate, reconstruct, approximate, or zero-fill scores, do not treat missing
ratings as zero, and do not use employee self-ratings in final scores. Employee
self-ratings are labelled reference-only.

The contracts were verified against `HrResultController`,
`EmployeeResultController`, `ManagerResultController`,
`PerformanceResultService`, the result DTOs and repositories,
`PerformanceScoreCalculator`, `SecurityConfig`, and
`PerformanceResultPublicationIntegrationTest`. Frontend API and UI tests mock
requests and do not claim live backend or PostgreSQL integration.

## Checks

```powershell
npm test
npm run lint
npm run build
```

Tests use Vitest, jsdom and React Testing Library. Frontend tests mock the
backend; they verify request contracts, route behavior and error handling but
do not claim a live backend login.
