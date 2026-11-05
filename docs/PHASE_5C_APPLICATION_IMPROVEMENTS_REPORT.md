# Phase 5C — Application Improvements and Workflow Corrections

Implementation report for the ten Phase 5C requirements on the existing
Evalora frontend (React + Vite) and backend (Spring Boot, Java 21,
PostgreSQL). No library was added, no module was rewritten, no mock data was
introduced and no scoring rule was changed.

Status legend: **PASS**, **FAIL**, **NOT TESTED**, **BLOCKED**.

| # | Requirement | Status |
|---|---|---|
| 1 | Password visibility on every password input | PASS |
| 2 | Cycle detail shows assignments; publish button only before publication | PASS |
| 3 | Publish button activates immediately after assignment save | PASS |
| 4 | Native `alert()`/`confirm()` replaced with in-app feedback | PASS |
| 5 | HR and manager dashboard cards navigate to real routes | PASS |
| 6 | Forgot/reset password + 5-minute login lockout with countdown | PASS |
| 7 | Separate MANAGER and HR KPIs, enforced in the backend | PASS |
| 8 | Employee dashboard shows the reporting manager | PASS |
| 9 | Manager can submit the review without saving a draft first | PASS |
| 10 | HR results across cycles, employees and departments | PASS |

## 1. Password visibility

`PasswordField` (the existing component with an accessible eye toggle:
`Show password` / `Hide password`) is used by the login form, the reset
password form, the change password form and therefore every password input in
the application. Each field owns its own visibility state, so toggling one
field never affects another, and the typed value is never touched. No password
value is logged, alerted or echoed in an error message.

Tests: `PasswordField.test.jsx` (independent toggles, value preserved),
`PasswordRecovery.test.jsx` (both reset fields expose their own toggle).

Note: HR staff creation does not collect a password — the backend generates the
credential — so there is no other password input to update.

## 2. Cycle detail and publish button

`CycleDetailPage` renders the cycle fields, the assigned KPIs
(`CycleKpiAssignments`, now including the assessment type) and the assigned
employees with their reviewing manager and assignment status
(`CycleEmployeeAssignments`), followed by a publishing summary. The
`Publish Cycle` button is rendered only for a `DRAFT` cycle that has at least
one KPI and one employee; after a successful publication the button is removed
and a published status message with the publication timestamp is shown instead.
`Edit cycle` and the assignment managers remain available for draft cycles.

## 3. Publish activation delay

Root cause: the publish eligibility was computed from the cycle summary
(`getCycleKpis` + `getCycleEmployees`), but after an assignment save only the
child list refetched; the summary kept its stale counts until a page reload.

Fix: both assignment components now call `onAssignmentsChanged` after a
successful save, and `CycleDetailPage` refetches the summary (and the cycle)
immediately. The button is disabled while those requests are pending, is never
enabled before the backend confirms the save, and duplicates are prevented by a
publish lock (`isPublishing`) plus the confirmation dialog's busy state.

## 4. In-app feedback instead of native dialogs

`window.confirm`/`alert` were removed from the whole frontend (verified by
search: no occurrence remains outside documentation comments). They are
replaced by `ConfirmDialog` + `useConfirmation()`, a small in-app modal that:

- is announced as a dialog, focusable, dismissible with `Cancel` or `Escape`;
- keeps the user on the current screen;
- disables its confirm button while the request is in flight, so an action
  cannot be submitted twice;
- only closes after the backend answers.

Success and failure feedback keeps the existing inline
`management-message`/`form-message` convention and preserves backend messages.
Used by: publish cycle, KPI activate/deactivate, staff activate/deactivate,
reporting-manager change, employee self-assessment submit, manager submit, HR
finalize, single and bulk result publication.

## 5. Navigable dashboard cards

`HrDashboardPage`: Active employees → `/hr/employees`, Active managers →
`/hr/managers`, Performance cycles and Published cycles → `/hr/cycles`,
Pending HR reviews → `/hr/assessments`, Published results → `/hr/results`.
`ManagerDashboardPage`: Assessments awaiting manager review →
`/manager/assessments`, Published results → `/manager/results`.
Genuinely informational counters (awaiting self-assessment, completed
assessments, direct reports) stay plain cards. Cards are real router links with
hover, focus-visible outline and pointer cursor, so keyboard and screen-reader
navigation work.

## 6. Password recovery and login lockout

- Login page links to `/forgot-password`; the page calls the existing
  `POST /api/auth/forgot-password`, always shows the neutral message and renders
  the reset link only when the backend exposes the token (dev profile only).
- `/reset-password?token=…` validates the password policy and that both fields
  match, then calls the existing `POST /api/auth/reset-password`. The 15-minute
  lifetime, single-use behaviour and dev-only token exposure are unchanged; no
  email is sent and no lock-clearing endpoint was added.
- The 5-attempt / 5-minute lockout is unchanged and still enforced by the
  backend. `LoginResult`/`AuthService`/`AuthController` now return 423 with
  `retryAfterSeconds` and `lockedUntilEpochSeconds` (fields that already existed
  on `SecurityErrorResponse`). The login page shows
  “Your account is temporarily blocked. Try again after 5 minutes.” plus
  `Try again in 04:59`, ticks once per second, disables the form while the
  server deadline is in the future, restores the countdown after a refresh
  (`evalora.login-lockout` holds only the email and the server deadline) and
  re-enables the form at zero without assuming the account is unlocked — the
  backend still decides on the next attempt.
- Bad credentials keep the backend's generic `Invalid email or password.`
  message instead of the shared “session expired” mapping.

## 7. Separate MANAGER and HR KPIs

- New enum `KpiType {MANAGER, HR}` on `kpi_master` and on the frozen
  `employee_kpis` snapshot. HR creates and manages both categories (form
  select, list/detail columns, cycle assignment table).
- Managers see HR-assessed KPIs read-only and cannot rate them; the backend
  rejects such an entry (`HR-assessed KPIs cannot be rated by the manager.`)
  and only requires/serialises manager-assessed KPIs.
- HR rates every KPI but never writes a manager rating; manager rows stay
  untouched during HR review, so manager values cannot be overwritten.
- Employees keep the informational self-assessment; it still contributes 0%.
- Scoring is unchanged for manager-assessed KPIs:
  `manager × 0.75 + hr × 0.25`, `BigDecimal`, exact two decimals per KPI,
  `HALF_UP` for the overall score, missing required ratings are rejected rather
  than scored as zero, weights must be valid. An HR-assessed KPI scores from the
  single HR rating it owns, so the two responsibilities stay separate while the
  75/25 weighting of manager-assessed KPIs is preserved.

## 8. Employee reporting manager

`EmployeeDashboardResponse` gained `reportingManager`
(`ReportingManagerResponse`: manager name and employee code) populated by
`DashboardService` from the real assignment. The dashboard shows a
`Reporting Manager` card and a meaningful empty state; it refreshes with the
dashboard request, so an HR change appears on the next load.

## 9. Direct manager submission

The frontend no longer requires a saved draft: validating every manager-assessed
KPI is enough. If the form has unsaved changes, `Submit` writes the current
values as a draft and then submits in the same action; if the values are already
saved, no draft request is sent. Draft saving remains available. The backend
contract is unchanged (it already accepted a submit on a draft save), duplicate
submissions are prevented, and the assignment moves to HR review.

## 10. HR results across cycles and departments

- New endpoint `GET /api/hr/results/cycles/{cycleId}?department=` returns every
  result of one cycle for HR (published and unpublished), optionally filtered by
  the employee's department (exact, case-insensitive).
- New page `/hr/results/browse`: cycle selector (the most recent non-draft cycle
  is labelled “current cycle”, older cycles stay accessible), department
  selector built from the departments actually present in the returned results,
  employee search by name/code/department/designation, and rows linking to the
  existing result detail page (KPI scores, manager ratings, HR ratings, overall
  score, status, published date).
- Results are never mixed across cycles, employee/manager visibility rules are
  untouched, and no score or publication state is modified by filtering.

## Files changed

### Frontend

New: `components/ConfirmDialog.jsx`, `hooks/useConfirmation.jsx`,
`api/lockoutStorage.js`, `pages/auth/ForgotPasswordPage.jsx`,
`ResetPasswordPage.jsx`, `ChangePasswordPage.jsx`,
`pages/results/HrResultBrowsePage.jsx`, `test/dialogHelpers.js`.

Updated: `api/authApi.js`, `api/resultApi.js`, `App.jsx`, `App.css`,
`pages/auth/LoginPage.jsx`, `pages/dashboard/HrDashboardPage.jsx`,
`ManagerDashboardPage.jsx`, `EmployeeDashboardPage.jsx`,
`pages/cycle/CycleDetailPage.jsx`, `CycleKpiAssignments.jsx`,
`CycleEmployeeAssignments.jsx`, `pages/kpi/KpiListPage.jsx`,
`KpiDetailPage.jsx`, `KpiFormPage.jsx`, `pages/staff/StaffListPage.jsx`,
`StaffDetailPage.jsx`, `pages/self-assessment/EmployeeSelfAssessmentPage.jsx`,
`pages/manager-assessment/ManagerAssessmentPage.jsx`,
`pages/hr-assessment/HrAssessmentPage.jsx`,
`pages/results/HrResultListPage.jsx`, `HrResultDetailPage.jsx`.

Tests: updated 7 existing suites for the in-app dialog and the new KPI
classification; added `PasswordField.test.jsx`, `PasswordRecovery.test.jsx`,
`DashboardNavigation.test.jsx`, `HrResultBrowse.test.jsx`,
`KpiTypeManagement.test.jsx`, `ManagerKpiType.test.jsx`.

### Backend

New: `entity/enums/KpiType.java`,
`database/migrations/V4__kpi_type_classification.sql`.

Updated: `entity/KpiMaster.java`, `EmployeeKpi.java`,
`PerformanceResultItem.java`, DTOs (`KpiResponse`, `CycleKpiResponse`,
`ManagerAssessmentKpiResponse`, `HrAssessmentKpiResponse`,
`SelfAssessmentKpiResponse`, `CreateKpiRequest`, `UpdateKpiRequest`,
`EmployeeDashboardResponse`, new `ReportingManagerResponse`),
`service/KpiManagementService.java`, `PerformanceCycleService.java`,
`ManagerAssessmentService.java`, `HrAssessmentService.java`,
`SelfAssessmentService.java`, `PerformanceScoreCalculator.java`,
`PerformanceResultService.java`, `DashboardService.java`, `AuthService.java`,
`controller/AuthController.java`, `HrResultController.java`;
`security/LoginResult` wiring for the lockout deadline.

Tests: `PerformanceScoreCalculatorTest` (KPI classification and scoring),
`AuthIntegrationTest` (423 body carries `retryAfterSeconds` /
`lockedUntilEpochSeconds`), `ManagerAssessmentIntegrationTest` (manager cannot
rate an HR-assessed KPI; message updates),
`PerformanceResultPublicationIntegrationTest` (cycle results + department
filter + authorization), `PerformanceResultGenerationIntegrationTest`
(message updates).

## API changes

| Endpoint | Change |
|---|---|
| `POST /api/auth/login` | 423 body now includes `retryAfterSeconds` and `lockedUntilEpochSeconds` |
| `GET /api/hr/results/cycles/{cycleId}` | new; optional `department` query parameter |
| `GET /api/employee/dashboard` | response adds `reportingManager` |
| KPI create/update, KPI/cycle/assessment responses | add `kpiType` (`MANAGER` \| `HR`) |

No existing response field was removed, no scoring rule changed and no security
rule was bypassed.

## Database migration

`database/migrations/V4__kpi_type_classification.sql` (Flyway, additive):

1. `kpi_master.kpi_type VARCHAR(20) NOT NULL DEFAULT 'MANAGER'` + check
   constraint (`MANAGER`, `HR`) + index.
2. `employee_kpis.kpi_type` with the same definition (frozen snapshots keep
   their own classification).
3. `performance_result_items.manager_rating` becomes nullable, guarded by
   `ck_result_item_stage_rating` (`manager_rating IS NOT NULL OR hr_rating IS
   NOT NULL`), because an HR-assessed KPI has no manager rating.

Existing rows are backfilled `MANAGER` by the column default, so historic KPI
records, published snapshots and finalized results keep exactly their previous
scoring. Nothing is deleted or renamed.

## Verification

| Check | Result |
|---|---|
| Frontend tests (`npx vitest run`) | PASS — 302 tests, 26 files |
| Frontend lint (`npm run lint`) | PASS — no errors or warnings |
| Frontend build (`npm run build`) | PASS |
| Backend tests (`mvnw -o test`, Java 21) | PASS — see final counts below |
| Backend compile | PASS (part of the test run) |
| Migration against a live PostgreSQL instance | PASS — V4 applied by Flyway to `evalora` on PostgreSQL 18.2, history now at V4, Hibernate `validate` passes |
| Browser/manual verification | NOT TESTED — no browser automation was used |

Backend suite result: `Tests run: 518, Failures: 0, Errors: 0, Skipped: 0`.

Migration follow-up: the first attempt to start the backend after Phase 5C
reported `Schema validation: missing column [kpi_type] in table [employee_kpis]`
because the process was started from the repository root, where
`../database/migrations` does not resolve, and Flyway only logged
`Skipping filesystem location` before handing over to Hibernate. The migration
itself was never executed against this database. It has since been applied
normally (recorded V4, `kpi_type` on both tables, `ck_result_item_stage_rating`,
`ix_kpi_master_type`), existing rows are all `MANAGER`, and
`spring.flyway.fail-on-missing-locations=true` now turns that misconfiguration
into an immediate, unambiguous Flyway error. See
`docs/SETUP_GUIDE.md` for the startup rules.

## Manual testing steps

1. Sign in; confirm the password eye toggles independently on the login form.
2. As HR create a cycle, assign KPIs (one `HR` assessed) and employees with
   reviewers, then watch `Publish Cycle` become enabled immediately after each
   save, and disappear after publishing (no native dialog anywhere).
3. Publish, then reopen the cycle: status and timestamps are shown, no publish
   button.
4. Click each HR and manager dashboard card and confirm the destination page.
5. Sign out; use Forgot Password, reset with the dev token, sign in with the new
   password; check both password fields toggle.
6. Fail the login five times: the message plus `Try again in 04:59` countdown
   appears, the form is disabled, and the countdown survives a refresh.
7. As a manager open a review: HR-assessed KPI is read-only; submit directly
   without `Save draft`.
8. As HR open the same employee: manager ratings are read-only; finalize; check
   the employee dashboard shows the reporting manager.
9. As HR open Results → “Results by cycle”, switch between current and
   historical cycles, filter by department and search an employee.
