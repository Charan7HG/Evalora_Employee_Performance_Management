# Phase 5F: Mandatory Reporting Manager and Safe UI Improvements

## 1. Employee creation behavior

HR must choose a reporting manager when creating an EMPLOYEE account. The employee form labels the field as required, only presents eligible active managers, and disables submission while managers are loading, when none are available, or when loading fails. If no manager is available, HR can go to manager administration; a failed fetch can be retried. The selected manager ID is included in the employee creation request.

Manager creation remains independent and does not request a reporting manager. Employee edit behavior is unchanged.

## 2. Backend validation

The employee creation service rejects a missing or null manager ID. It continues to lock and validate the selected profile as a MANAGER with ACTIVE staff status and an enabled account. The role-specific check lives in the service because the creation DTO is shared with manager creation. Existing authorization remains in place.

## 3. Manager list source and UI states

The form loads managers from the existing active manager API and filters the returned list for eligible manager records. Loading, request failure, no eligible manager, and retry states are visible. Duplicate or ineligible entries are not offered for selection.

## 4. Existing records and database

No schema change or data backfill was required. The existing database was inspected read-only: it contains 6 employees, all 6 have a reporting manager and an open manager assignment, and there are 3 active eligible managers. Existing legacy employee records without a manager remain supported and are not modified by this requirement.

## 5. Direct-report workflow and cycle assignment

Reporting-manager assignment remains separate from performance-cycle assignment. Employee creation does not add the employee to a cycle. The new integration test checks that an HR-created employee appears in the selected manager's direct-report list and does not appear under another manager. Existing dashboard integration coverage continues to check manager-scoped assessment status and direct-report lists.

## 6. Safe UI improvements

The employee form marks the manager selection as required, communicates manager-list loading and failure states, provides a route to manager administration when the list is empty, and prevents duplicate create submissions while the request is pending. No broad UI redesign or additional dependency was introduced.

Keyboard note: standard browser Tab and Shift+Tab navigation remains the supported way to move between ordinary form fields. Arrow keys are not globally repurposed to move between text inputs because they must continue to edit text and operate native controls normally.

## 7. Files changed

- `backend/src/main/java/com/evalora/backend/service/EmployeeManagementService.java` — require and validate a manager for employee creation.
- `frontend/src/pages/staff/StaffFormPage.jsx` — required manager selection, eligibility filtering, manager list states, and duplicate-submit protection.
- `backend/src/test/java/com/evalora/backend/controller/HrEmployeeManagementIntegrationTest.java` — required manager validation, preservation of legacy unassigned records, manager role behavior, and direct-report isolation.
- `frontend/src/pages/staff/StaffManagement.test.jsx` — required manager selection and manager list state/create behavior.
- `docs/API_DOCUMENTATION.md` — document required manager ID for employee creation.
- `docs/SETUP_GUIDE.md` — document manager selection during employee creation.
- `docs/PHASE_5F_EMPLOYEE_MANAGER_REQUIREMENT_REPORT.md` — this report.

## 8. Backend tests

- Full Maven backend suite: **passed**, 538 tests, 0 failures, 0 errors, 0 skipped.
- Focused HR employee-management integration checks: **passed**, including manager assignment history, reactivation behavior, and direct-report isolation.
- Employee-code migration integration suite: **passed**, 6 tests.
- The full suite includes the project's assessment, scoring, authorization, and publication tests.

## 9. Frontend tests

- Full Vitest suite: **passed**, 27 test files and 311 tests.
- Focused staff management suite: **passed**, 1 test file and 37 tests.

## 10. Lint and production build

- Frontend ESLint: **passed**.
- Frontend production build: **passed**.

## 11. Live database and API verification

The database inspection was read-only and local. No database rows were changed. No live API startup or endpoint verification was performed as part of this pass.

## 12. Manual browser testing

Manual browser testing was not performed.

## 13. Remaining issues

No known implementation issues remain for this requirement. No migration is needed. No database reset or historical data change was performed. Manual browser testing and live API startup verification were not performed.
