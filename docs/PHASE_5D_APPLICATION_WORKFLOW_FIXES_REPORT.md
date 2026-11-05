# Phase 5D — Application Workflow Fixes

Implementation report for the Phase 5D corrections to the existing Evalora
frontend (React + Vite) and backend (Spring Boot, Java 21, PostgreSQL). No
library was added, no module was rewritten, no mock data was introduced, and no
authentication, authorization, validation or database migration was weakened.

Status legend: **PASS**, **FAIL**, **NOT TESTED**, **BLOCKED**.

| # | Requirement | Status |
|---|---|---|
| 1 | Cycle configuration order: details, KPIs, employees, publish | PASS |
| 2 | Publish requires at least one KPI and one employee; no duplicate publication | PASS |
| 3 | Manager KPIs 75%, HR KPIs 25%, employee self-rating 0% | PASS |
| 4 | Employees self-rate both KPI types | PASS |
| 5 | Managers rate only manager-assessed KPIs | PASS |
| 6 | HR rates only HR-assessed KPIs and cannot edit manager ratings | PASS |
| 7 | Missing ratings are rejected, not scored as zero | PASS |
| 8 | Unweighted scoring is an equal-share average and needs no weights | PASS |
| 9 | Development password reset without an email step | PASS |
| 10 | No Change Password link on employee, manager or HR dashboards | PASS |
| 11 | Password eye icons inside and right-aligned in the input | PASS |

## 1. Scope and constraints

The phase was implemented on the existing application without restoring the old
per-KPI 75/25 calculation. The new model is category based: manager-assessed
KPIs form one category worth 75% of the overall score, HR-assessed KPIs form a
second category worth 25%, and the employee self-rating is reference
information only.

Preserved as required:

- JWT authentication, role checks and the existing security filter chain.
- Bean validation, DTO shapes and the existing API error convention.
- BCrypt password hashing, the password policy and the existing Flyway schema.
- The existing cycle, assessment, result and publication workflows.
- No data deletion, no database reset and no production security relaxation.

One compatibility decision: the `/change-password` route and its backend
endpoint were kept, but the navigation link was removed from the shared
dashboard layout. Removing the feature itself was out of scope and would have
changed password-management behavior unrelated to this phase.

## 2. Cycle configuration order

`CycleDetailPage` now presents the workflow in the required order:

1. Cycle details.
2. Assigned KPIs (`CycleKpiAssignments`), including assessment type and
   weightage mode.
3. Assigned employees (`CycleEmployeeAssignments`).
4. Publishing summary and the `Publish Cycle` action.

The draft-only `Edit cycle` action stays immediately before the assignment
sections, where it belongs to the editable cycle header rather than to the final
publishing step. The publishing summary was moved below both assignment
sections so HR reviews the configured KPIs and employees before publishing.

The summary shows separate counts for configured KPIs, manager-assessed KPIs
(75%), HR-assessed KPIs (25%) and assigned employees, and explains the category
split. The publish confirmation repeats the manager/HR KPI counts.

## 3. Publish readiness and duplicate protection

Frontend:

- Publishing is offered only for a `DRAFT` cycle.
- The button is disabled when the KPI or employee count is zero, and the page
  explains exactly which assignment is missing.
- Counts come from the live KPI and employee summary, which is refreshed after
  every successful assignment save, so the button no longer stays disabled
  until a page reload.
- A `publishingRef` guard plus the existing `isPublishing` state and the
  confirmation dialog's busy state prevent a second publish request from being
  sent if the dialog is confirmed twice in the same tick.
- A published cycle no longer shows the publish button; it shows a status
  message with the publication timestamp.

Backend (`PerformanceCycleService.publish`):

- Only a `DRAFT` cycle can be published; the cycle row is locked while
  publishing.
- At least one configured KPI and at least one assigned employee are required.
- Every configured KPI must still be active.
- Publishing creates the frozen KPI snapshots and changes the cycle status in
  the same transaction.
- Draft configuration operations lock and require a `DRAFT` cycle, so a
  published cycle is immutable at the service layer.

## 4. KPI classification: manager 75%, HR 25%, self 0%

`KpiType` already existed on `KpiMaster` and on the frozen `EmployeeKpi`
snapshot. Phase 5D uses that classification consistently:

- `MANAGER` KPI: rated by the reviewing manager only; its rating contributes to
  the manager category, weighted 75% of the overall score.
- `HR` KPI: rated by HR only; its rating contributes to the HR category,
  weighted 25% of the overall score.
- Employee self-rating: collected for both KPI types, shown in the report, and
  never passed to the score calculator. Its contribution is 0%.

The KPI form labels now state the category explicitly:

- “Manager-assessed KPI — scored in the 75% manager category”.
- “HR-assessed KPI — scored in the 25% HR category”.

The cycle KPI table and the KPI picker also show “Manager assessed” or
“HR assessed”, so the classification is visible during configuration.

## 5. Employee self-assessment behavior

`SelfAssessmentService` is unchanged in scope: the employee sees every assigned
KPI, regardless of type, and may rate both manager-assessed and HR-assessed
KPIs. Submission still requires a rating from 1 to 5 for every assigned KPI.
The employee self-rating is copied into the final result item for context but is
never used in any score calculation.

Tests cover both KPI types being self-rated; the previous behavior was already
correct and was not weakened.

## 6. Manager assessment behavior

`ManagerAssessmentService` now enforces the category boundary:

- Saving a manager rating for an HR-assessed KPI is rejected with
  `HR-assessed KPIs cannot be rated by the manager.`
- Submission checks only manager-assessed KPIs. Every manager-assessed KPI must
  have a manager rating from 1 to 5; HR-assessed KPIs are skipped and never
  require or receive a manager rating.
- A cycle containing only HR-assessed KPIs still allows the manager to submit
  the review (with no ratings), so the assignment can move to HR review.
- The manager submission timestamp and the `MANAGER_REVIEW_PENDING` to
  `HR_REVIEW_PENDING` transition are unchanged.

## 7. HR assessment behavior and separation of duties

`HrAssessmentService` was rewritten around the category model:

- HR draft saving accepts only HR-assessed KPIs. A manager-assessed KPI entry is
  rejected with `HR can only assess HR-assessed KPIs.`
- HR submission requires an HR rating from 1 to 5 for every HR-assessed KPI.
- Manager-assessed KPIs are read from the manager's submitted rows and are
  never written, changed or overwritten by HR.
- The result item stores only the owning assessor's rating: manager rating and
  comment on a manager KPI, HR rating and comment on an HR KPI. The other side
  is stored as `null`, so the frozen report cannot show an invented value.
- `HrAssessmentStatusResponse.totalKpis` and `ratedKpis` now count only
  HR-assessed KPIs, because HR's progress is measured against the KPIs HR owns.
- Submission still records `hr_finalized_at`, moves the assignment to
  `COMPLETED`, and writes one draft result with one item per KPI in the same
  transaction. The result remains unpublished until the existing publication
  step.

## 8. Missing ratings are rejected, not zero

Both assessment services reject an incomplete submission with a conflict
instead of storing or scoring a zero:

- Manager submission: `Every manager-assessed KPI must be rated from 1 to 5
  before submitting.`
- HR submission: `Every HR-assessed KPI must have an HR rating from 1 to 5
  before submitting.`
- The score calculator independently rejects a missing required rating:
  `Every manager-assessed KPI must have a manager rating before scoring.` and
  `Every HR-assessed KPI must have an HR rating before scoring.`

Tests assert that a rejected submission leaves the assignment pending, creates
no result header and creates no result items.

## 9. Score calculation and worked examples

`PerformanceScoreCalculator` implements the category model with `BigDecimal`:

1. Score each KPI from its owning rating.
2. Compute each category as the weighted mean of its KPI scores, or the plain
   mean when unweighted. Category scores keep four decimals internally.
3. Combine the categories once:
   `overall = managerCategory × 0.75 + hrCategory × 0.25`.
4. Round the overall score once with `HALF_UP` to the two decimals stored by the
   database.
5. Derive the grade from the inclusive application-wide scale.

A cycle containing only one category is scored from that category alone. The
missing category is not treated as zero and its percentage is not redistributed.

Worked examples verified by tests:

| Case | Calculation | Overall |
|---|---|---|
| Mixed, weighted (manager 4 at 60; HR 5 at 40) | 4.00 × 0.75 + 5.00 × 0.25 | 4.25 |
| Manager-only, weighted (4 at 60; 5 at 40) | manager category 4.40, no HR category | 4.40 |
| HR-only, weighted (4 at 60; 5 at 40) | HR category 4.40, no manager category | 4.40 |
| Unweighted mixed (manager 4 and 5; HR 3 and 4) | 4.50 × 0.75 + 3.50 × 0.25 | 4.25 |
| Half-up rounding | 4.00 × 0.75 + 4.50 × 0.25 = 4.125 | 4.13 |

The old per-KPI manager/HR blend is gone. A KPI is scored only by the assessor
who owns it, which avoids inventing a score that nobody submitted.

## 10. Unweighted scoring explanation

Unweighted scoring is a first-class mode, not a fallback:

- If no KPI in the cycle has a weightage, the calculation mode is `AVERAGE`.
- Each category uses the equal-share average: every KPI rating in that category
  counts the same, and no weightages are required.
- The manager and HR categories still contribute 75% and 25% respectively.
- Weightages must be all-or-nothing. A mixture of weighted and unweighted KPIs
  is rejected instead of being guessed.
- When weights are used, they must be greater than 0, at most 100, and add up to
  exactly 100.00 across the cycle.

The UI explains this in `CycleKpiAssignments` when the unweighted mode is
selected: every KPI counts equally, no weightages are required, and no total of
100% is expected. The assignment summary also states
“Unweighted — every KPI counts equally; no weightages are needed.” The KPI
weightage section is hidden in that mode, so HR is not asked to enter weights.

## 11. Labels and configuration clarity

The following user-facing labels were aligned with the category model:

- KPI form: assessment type options and hint text name the 75% and 25%
  categories, state who rates each type, and state that the self-rating is not
  scored.
- Cycle KPI table: shows the assessment type per KPI.
- Cycle KPI picker: shows the assessment type while selecting KPIs.
- Cycle detail: shows manager/HR KPI counts and the category explanation.
- HR assessment: status counts only the KPIs HR is responsible for.

No API field was renamed; the existing `kpiType` values `MANAGER` and `HR` are
used as before.

## 12. Development password reset

A direct, development-only reset was added:

- Endpoint: `POST /api/auth/dev-reset-password`.
- Controller: `DevPasswordResetController` is annotated `@Profile("dev")`, so
  the route does not exist outside the dev profile. A non-dev integration test
  confirms the endpoint returns 404 there.
- Request: `DevResetPasswordRequest` contains only `newPassword` and
  `confirmPassword`. There is no email and no token supplied by the browser.
- Target account: fixed on the server by `evalora.dev-reset.email`, which falls
  back to the bootstrap HR email. A caller cannot choose whose password changes.
- Validation and security: confirmation match, the existing password policy,
  the reuse check, BCrypt hashing and invalidation of outstanding reset tokens
  all apply.
- The endpoint is intentionally public in `SecurityConfig` only because the
  controller itself exists only under the dev profile.

Frontend:

- `/forgot-password` is now the direct new-password form. It validates the
  policy and confirmation locally, calls the new endpoint, and redirects to
  `/login` with a success message.
- The old `/reset-password?token=…` URL redirects to `/forgot-password`, so old
  bookmarks do not land on a dead form.
- The existing token-based forgot/reset backend endpoints remain available for
  compatibility; the new dev flow does not depend on them.

## 13. Password reset user experience

- The login page displays the reset success message passed through navigation
  state, without showing it when a newer login error exists.
- The direct reset form keeps the typed password when the backend rejects the
  request, so the user can correct it.
- The form uses the shared `PasswordField`, so both password inputs have their
  own show/hide control.
- No password value is logged, alerted or echoed in an error message.

## 14. Dashboard Change Password removal

`DashboardLayout` is shared by the employee, manager and HR dashboards. Its
`Change password` link was removed, so the link no longer appears on any of the
three role dashboards. The `/change-password` route and its backend endpoint
remain functional for compatibility and for direct navigation; only the
dashboard navigation entry was removed, as required.

## 15. Password eye icon alignment and accessibility

The shared stylesheet now defines the password field layout:

- The eye button is positioned inside the input wrapper, absolutely at
  `right: 7px` and `top: 50%`, with `translateY(-50%)` for vertical centering.
- The input reserves `padding-right: 54px`, so typed text never runs under the
  control.
- The button is a 36×36 target, keeps `type="button"` (so it never submits the
  form), and has a visible `:focus-visible` outline for keyboard users.
- The existing accessible name toggles between `Show password` and
  `Hide password`.
- Each field keeps its own visibility state, so toggling one field does not
  affect another.

## 16. Verification results

### Backend

Command: `mvnw.cmd test` with `JAVA_HOME` set to JDK 21.0.12.1. Maven
recompiled 140 main source files and 47 test source files.

Result: **529 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.**

Relevant suite results:

| Suite | Tests | Result |
|---|---|---|
| `PerformanceScoreCalculatorTest` | 28 | PASS |
| `PerformanceResultGenerationIntegrationTest` | 16 | PASS |
| `HrAssessmentIntegrationTest` | 29 | PASS |
| `PasswordManagementDevIntegrationTest` | 7 | PASS |
| `PasswordManagementIntegrationTest` | 28 | PASS |
| `CyclePublishingIntegrationTest` | 12 | PASS |

The first full run exposed 27 failures in older HR/publication suites whose
fixtures still expected HR to rate manager-assessed KPIs. Those fixtures were
updated to the category model (HR-rated fixture KPIs; manager submits without
ratings), and the complete suite was rerun successfully. This was a test-fixture
correction, not a relaxation of production validation.

### Frontend

- Focused suites (`PasswordRecovery`, `authApi`, `CycleManagement`,
  `DashboardNavigation`, `PasswordField`): **85 tests passed**.
- Full suite: **26 files, 304 tests passed**.
- `npm run lint`: **clean** (no errors or warnings).
- `npm run build`: **PASS**, 142 modules transformed, production bundle
  generated.

The frontend tests cover the direct dev reset form, the retired reset URL
redirect, the login success message, the dashboard link removal, the password
field toggle, the cycle publishing order/counts and the unweighted explanation.

## 17. What could not be verified

The following items were not tested and are not claimed as verified:

- **Live PostgreSQL**: the backend suites run on H2 in PostgreSQL compatibility
  mode. No migration was applied to a live PostgreSQL database and no
  PostgreSQL-specific lock behavior was exercised.
- **Manual browser testing**: no real browser session was used. The password
  icon alignment, responsive layout, focus ring and keyboard interaction were
  verified only through component tests and stylesheet inspection, not visually.
- **Live API smoke test**: the running local backend process was not restarted
  or used for manual end-to-end requests. Verification was performed through the
  automated Spring Boot test suite.
- **Real email delivery**: intentionally not applicable. The new dev reset sends
  no email, as required.
- **Git history**: the workspace is not a Git repository, so no commit, tag or
  push was created.
- **`mvn clean`**: not run because the running local backend process held files
  under `backend/target`. Test compilation was forced by removing Maven's
  incremental-build status, so the reported results still used freshly compiled
  sources.
