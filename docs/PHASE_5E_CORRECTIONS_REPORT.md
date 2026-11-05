# Phase 5E Corrections Report

## 1. Password reset wording

The password reset screen presents **Set New Password**, **New Password**, **Confirm Password**, and **Change Password**, with the concise helper “Enter and confirm your new password.” User-visible development account and backend configuration details were removed or sanitized. The development-only endpoint restriction remains in place.

## 2. Password reset success

The backend success message is consistently **“Password changed successfully.”** The page shows success and redirects to login only after the password reset API call succeeds. Validation and service errors remain errors; internal reset configuration messages are replaced with a user-safe unavailable message.

## 3. HR assessment dashboard destinations

The HR assessment counters use `/api/hr/dashboard` counts and link to `/hr/assessment-status/{status}`. These lists query the backend assessment status service using the same assignment statuses as the counters and show employee, manager, department, cycle, cycle status, assignment status, and due date. HR status access is restricted to HR accounts.

## 4. Published Results navigation

The HR Published Results card routes to **Results by cycle** at `/hr/results/browse`. It provides cycle selection and cycle status, published result counts, and the existing department and employee search filters over backend result data.

## 5. Sidebar terminology

The HR sidebar item for the eligible result queue is now **Results awaiting publication**. The separate cycle browse item remains **Results by cycle**. This distinguishes the actionable publication queue from published results.

## 6. Manager dashboard widgets

Manager dashboard counts come from the authenticated manager dashboard API. Pending self-assessment, manager review, HR review, completed assessments, and published results each route to a corresponding manager-scoped destination. Status pages use assignment status data and link to review details where applicable. Published results continue to use the manager results page.

## 7. Active Direct Reports decision

The widget is useful and remains. It routes to `/manager/direct-reports`, backed by `/api/manager/direct-reports`. The query returns active direct reports for the authenticated manager only, with employee name, employee code, department, and designation. The manager authorization rule is retained.

## 8. Keyboard navigation

Form fields retain native DOM order and browser Tab/Shift+Tab behavior. Password fields use an explicit label and a separate show/hide button with accessible hint/error descriptions. Rating controls remain native select controls, preserving their standard keyboard operation. No global arrow-key handler was added; ordinary text editing and browser control behavior remain intact. Focus styling remains visible through existing styles.

## 9. Manager rating scope

Managers can save and submit ratings for every assigned KPI type, including HR KPIs. Submission requires a valid manager rating for every assigned KPI. The backend enforces eligibility for the manager's assigned direct reports.

## 10. HR rating scope

HR can rate HR-type KPIs only. HR assessment drafts and submissions reject Manager-type KPIs. During HR review, manager ratings and comments are displayed read-only; the HR rating and comment are stored independently.

## 11. Rating storage

Manager and HR ratings remain in their separate assessment records and result fields. No duplicate rating columns were added. Migration V5 already adds the required nullability for HR ratings and was not modified.

## 12. Final scoring formula

Self-ratings contribute 0%. Manager-type KPIs use manager ratings. Each HR-type KPI uses `(manager rating + HR rating) / 2`. With both categories present, final score is `manager category score × 0.75 + HR category score × 0.25`. Existing category weighting and rounding rules are retained. A category-only cycle uses that category score directly rather than redistributing the absent category's contribution.

## 13. Weighted and unweighted behavior

Within a weighted category, KPI weights are normalized using the existing category calculation. In an unweighted category, KPI scores are averaged equally. The 75%/25% factors are applied once at category level. HR KPI combination happens before aggregating the HR category.

## 14. Missing ratings and historical data

Missing manager ratings block manager submission; missing HR ratings block HR completion and result generation. Published results remain immutable. Existing V5 behavior preserves historical rows and does not recalculate published results. No local database records were edited or deleted during verification.

## 15. Files changed

- Backend routing, DTOs, queries, assessment/status logic, scoring, and password wording: `AssessmentStatusController`, `AssessmentStatusService`, `AssessmentStatusItemResponse`, `DirectReportResponse`, `EmployeeAssignmentRepository`, `EmployeeRepository`, `HrAssessmentKpiResponse`, `PerformanceResultItem`, `AuthController`, `DevPasswordResetController`, `HrAssessmentService`, `ManagerAssessmentService`, and `PerformanceScoreCalculator`.
- Backend tests: `DashboardIntegrationTest`, `HrAssessmentIntegrationTest`, `ManagerAssessmentIntegrationTest`, `PasswordManagementDevIntegrationTest`, `PasswordManagementIntegrationTest`, `PerformanceResultPublicationIntegrationTest`, `HrAssessmentConcurrencyIntegrationTest`, `HrAssessmentDataIntegrityIntegrationTest`, `PerformanceResultGenerationIntegrationTest`, and `PerformanceScoreCalculatorTest`.
- Frontend routing, forms, dashboards, status pages, and API: `App.jsx`, `DashboardLayout.jsx`, `PasswordField.jsx`, `ForgotPasswordPage.jsx`, `ResetPasswordPage.jsx`, `HrDashboardPage.jsx`, `ManagerDashboardPage.jsx`, `HrAssessmentPage.jsx`, `ManagerAssessmentPage.jsx`, `HrResultBrowsePage.jsx`, `KpiFormPage.jsx`, and `assessmentStatusApi.js` with the new `assessment-status/AssessmentStatusListPage.jsx` and `ManagerDirectReportsPage.jsx`.
- Frontend tests: `authApi.test.js`, `PasswordField.test.jsx`, `PasswordRecovery.test.jsx`, `DashboardNavigation.test.jsx`, `AssessmentStatusListPage.test.jsx`, `HrAssessment.test.jsx`, `ManagerAssessment.test.jsx`, `ManagerKpiType.test.jsx`, `HrResults.test.jsx`, and `HrResultBrowse.test.jsx`.
- This report.

## 16. Backend tests

`mvn test` full suite: **534 tests passed, 0 failures, 0 errors, 0 skipped**. This includes manager and HR assessment rules, scoring, authorization/isolation, result immutability, and migration-related tests.

## 17. Frontend tests

`npm test -- --run`: **27 test files passed, 307 tests passed**.

## 18. Lint and production build

`npm run lint`: passed.

`npm run build`: passed.

## 19. Live database and API verification

Local PostgreSQL 18.2 was checked read-only first: Flyway history showed successful migrations V1–V5, and `manager_rating` and `hr_rating` are both nullable. Backend startup then validated all five migrations, reported schema version 5 up to date with no migration required, and passed Hibernate schema validation. API startup succeeded on an ephemeral port; `/v3/api-docs` returned HTTP 200. `/actuator/health` required authentication and returned 401 without credentials. No live assessment endpoints were exercised because no test account/token was used against the local data.

## 20. Manual browser testing and remaining issues

Manual browser testing was not performed. No known product issue remains from this pass. The only verification limit is that protected live assessment endpoints were not called against local user data; their status and authorization behaviors are covered by backend integration tests.
