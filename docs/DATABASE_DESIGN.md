# Evalora — Database Design

PostgreSQL 16+ · schema version `V4` · migration files: `database/migrations/V1__init_schema.sql`,
`database/migrations/V2__employee_manager_assignments.sql`,
`database/migrations/V3__case_insensitive_employee_code_uniqueness.sql`,
`database/migrations/V4__kpi_type_classification.sql`

This document describes the 13 tables that make up the Evalora schema, why each one exists, and which rules
are enforced by the database rather than by Java code.

---

## 1. Design principles

| # | Principle | Why |
|---|---|---|
| 1 | **One identity, one profile** | `users` holds login data and the role. `employees` holds staff data. No duplicated role or email. |
| 2 | **The role lives in exactly one place** | `users.role` is authoritative. `employees` does not repeat it. |
| 3 | **Snapshot at publish** | KPI text and weights are copied into `employee_kpis` when a cycle is published, so editing a KPI later cannot rewrite history. |
| 4 | **Freeze the result** | `performance_results` + `performance_result_items` are written once at finalisation and never recalculated. |
| 5 | **Soft delete** | Employees and KPIs are deactivated, never deleted. Historical records keep working references. |
| 6 | **No floating point for scores** | Every score and weight is `NUMERIC` in PostgreSQL and `BigDecimal` in Java. |
| 7 | **The database enforces the obvious rules** | Uniqueness, rating range, status/timestamp consistency and "not your own manager" are constraints, not just service checks. |
| 8 | **Timestamps in UTC** | All `TIMESTAMPTZ` columns are handled as UTC (`hibernate.jdbc.time_zone=UTC`). |

---

## 2. Table overview

| # | Table | One row = | Purpose |
|---|---|---|---|
| 1 | `users` | one login account | Email, BCrypt hash, role, lockout state |
| 2 | `employees` | one staff profile | Name, code, department, manager link, active flag |
| 3 | `password_reset_tokens` | one reset request | Hashed one-time token, expiry |
| 4 | `kpi_master` | one reusable KPI | Name, description, category, default weight |
| 5 | `performance_cycles` | one review period | Dates, deadlines, lifecycle status |
| 6 | `cycle_kpis` | one KPI in one cycle | Weight for that cycle |
| 7 | `employee_assignments` | one employee in one cycle | Reviewing manager, workflow status |
| 8 | `employee_kpis` | one KPI of one assignment | Frozen KPI copy + weight |
| 9 | `self_assessments` | one self rating | Employee's own rating and comment |
| 10 | `manager_assessments` | one manager rating | 75 % of the final score |
| 11 | `hr_assessments` | one HR rating | 25 % of the final score |
| 12 | `performance_results` | one official result | Overall score, grade, publication |
| 13 | `performance_result_items` | one KPI inside a result | Permanent per-KPI breakdown |
| 14 | `employee_manager_assignments` | one reporting-line period | Append-only reporting history (V2) |

---

## 3. `users`

Login identity and the single source of truth for the role.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `email` | `VARCHAR(255)` | no | Normalised (trimmed + lowercased) before it reaches the database |
| `password_hash` | `VARCHAR(100)` | no | BCrypt hash only — never the plain password |
| `role` | `VARCHAR(20)` | no | `HR`, `MANAGER`, `EMPLOYEE` |
| `enabled` | `BOOLEAN` | no | Default `TRUE` |
| `failed_login_attempts` | `INTEGER` | no | Default `0`, `>= 0` |
| `locked_until` | `TIMESTAMPTZ` | yes | Set on the 5th failed attempt (5-minute lock) |
| `last_login_at` | `TIMESTAMPTZ` | yes | Updated on successful login |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | Managed by Hibernate |

**Constraints / indexes**
- `ck_users_role` — the role must be one of the three allowed values.
- `ck_users_failed_attempts` — the counter can never go negative.
- `ux_users_email_lower` - unique index on `LOWER(email)`, so `HR@evalora.local` and `hr@evalora.local` cannot both exist.
- `ix_users_role_enabled` - supports "list all active managers" style queries.

**Note on the JPA mapping.** `User` deliberately declares **no** `@UniqueConstraint` for the email column.
The real rule is the functional index above, and JPA cannot express an index over `LOWER(email)`. Declaring a plain
unique constraint on the entity would describe a rule that does not exist in the database. The entity is validated
against the schema; it does not define it.

**Email normalisation contract (required in Phase 3B).** Every entry point that accepts an email - login, HR
account creation, password reset - must `trim()` and `toLowerCase(Locale.ROOT)` the value once, in the service
layer, before it is inserted or looked up. All lookups use `findByEmailIgnoreCase` as a second safety net. Because the
stored value is always lowercase, the functional index behaves exactly like a plain unique index, and a client cannot
create `Evan@Evalora.local` next to `evan@evalora.local`.

**Why no `username` column?** Login is email only, as specified. There is no second identifier to keep in sync.

---

## 4. `employees`

Staff profile for **all** roles. Only profiles whose user has role `EMPLOYEE` are appraised.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `user_id` | `BIGINT` | no | FK → `users.id`, unique (one profile per user) |
| `employee_code` | `VARCHAR(30)` | no | Unique HR code, e.g. `EMP-001`; unique case-insensitively |
| `full_name` | `VARCHAR(150)` | no | Display name |
| `department` | `VARCHAR(100)` | yes | Free text in v1 |
| `designation` | `VARCHAR(100)` | yes | Free text in v1 |
| `manager_id` | `BIGINT` | yes | FK → `employees.id` (self reference) |
| `joining_date` | `DATE` | yes | |
| `status` | `VARCHAR(20)` | no | `ACTIVE` / `INACTIVE` — the soft delete |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints / indexes**
- `uq_employees_user` — a user cannot have two profiles.
- `uq_employees_code` — employee codes are unique (case-sensitive, from V1).
- `ux_employees_code_lower` — functional unique index on `LOWER(employee_code)`, so `EMP001` and `emp001` cannot
  coexist (V3). It supplements, not replaces, the V1 constraint. The V3 migration checks for pre-existing
  case-insensitive duplicates and aborts with their values instead of changing any data.
- `fk_employees_user`, `fk_employees_manager`.
- `ck_employees_not_own_manager` — `manager_id <> id`.
- `ix_employees_manager`, `ix_employees_status`, `ix_employees_name_lower`.

**Manager rules enforced in the service layer (Phase 3B)**
- Only a user with role `MANAGER` and status `ACTIVE` may be a manager.
- Managers have no manager of their own in v1.
- An employee may not report to themselves or create a manager cycle.

The database guarantees "not yourself"; the role/active rules need application logic because they span two tables.

### 4.1 `employee_manager_assignments` (V2)

Added by `V2__employee_manager_assignments.sql`. V1 has only `employees.manager_id`, a single mutable pointer, so a
reassignment would overwrite the previous reporting line. This table makes the history append-only.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `employee_id` | `BIGINT` | no | FK → `employees.id`; the reporting employee |
| `manager_id` | `BIGINT` | no | FK → `employees.id`; the manager for this period |
| `assigned_by` | `BIGINT` | yes | FK → `users.id`, `ON DELETE SET NULL`; the HR user |
| `effective_from` | `TIMESTAMPTZ` | no | Start of the period (UTC) |
| `effective_to` | `TIMESTAMPTZ` | yes | `NULL` = current assignment |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints / indexes**
- `uq_ema_active_employee` — partial unique index on `employee_id` where `effective_to IS NULL`; at most one current
  manager per employee.
- `ck_ema_not_own_manager` — `employee_id <> manager_id`.
- `ck_ema_effective_period` — a closed period must satisfy `effective_to > effective_from`.
- `ix_ema_employee_history`, `ix_ema_manager`.

**Semantics**
- Assigning a manager closes the current open row and inserts a new open row. Rows are never updated except to set
  `effective_to` once, and never deleted.
- `employees.manager_id` stays in sync with the open row so existing reads ("who is this employee's manager?") keep
  working without a join.
- This is not the appraisal assignment. `employee_assignments` remains the per-cycle reviewer snapshot.
- Deactivating an employee closes the open row and clears the pointer. Reactivating does not reopen it.

---

## 5. `password_reset_tokens`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `user_id` | `BIGINT` | no | FK → `users.id`, `ON DELETE CASCADE` |
| `token_hash` | `VARCHAR(64)` | no | SHA-256 hex of the raw token; unique |
| `expires_at` | `TIMESTAMPTZ` | no | 15 minutes after creation |
| `used_at` | `TIMESTAMPTZ` | yes | Set when the token is consumed or invalidated (single use) |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Why a hash?** A stolen database must not allow password resets. The raw token is returned to the caller only when
the `dev` Spring profile is active; otherwise it is never persisted, logged or returned. Issuing a new token marks the
user's previous unused tokens as used, and a successful reset does the same for any others still outstanding.

`ON DELETE CASCADE` is safe here: tokens are not historical performance data.

---

## 6. `kpi_master`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `name` | `VARCHAR(150)` | no | Unique, case-insensitive |
| `description` | `TEXT` | yes | |
| `category` | `VARCHAR(80)` | yes | Delivery, Quality, Collaboration, … |
| `default_weightage` | `NUMERIC(5,2)` | yes | Suggestion only; `0 < w <= 100` |
| `kpi_type` | `VARCHAR(20)` | no | `MANAGER` (default, V4) or `HR` — who assesses it |
| `active` | `BOOLEAN` | no | Default `TRUE`; `false` = soft deleted |
| `created_by` | `BIGINT` | yes | FK → `users.id`, `ON DELETE SET NULL` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints / indexes**
- `ux_kpi_master_name_lower` — unique index on `LOWER(name)`.
- `ck_kpi_default_weight`.
- `ck_kpi_type` — `kpi_type IN ('MANAGER', 'HR')`.
- `ix_kpi_master_active`, `ix_kpi_master_type`.

**No DELETE endpoint.** Deactivating (`active = false`) keeps every historical reference valid, and HR can
reactivate later. `ON DELETE SET NULL` on `created_by` means removing a user never destroys KPI history.

**HR API (Phase 3C).** `/api/hr/kpis` reuses this table with no schema change. `POST` trims the name and always creates
an active row; `PUT` updates only name/description/category/default weightage; `PATCH .../deactivate` and
`.../reactivate` only flip `active`. Names are unique ignoring case: the service pre-checks with a case-insensitive
query, and `ux_kpi_master_name_lower` is the final guard, with a uniqueness violation translated to HTTP 409. Editing
or deactivating a master KPI never writes `cycle_kpis`, `employee_kpis`, assessments or result rows — those are frozen
snapshots.

---

## 7. `performance_cycles`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `name` | `VARCHAR(150)` | no | Unique, case-insensitive |
| `description` | `TEXT` | yes | |
| `start_date` / `end_date` | `DATE` | no | Review period |
| `self_assessment_deadline` | `DATE` | no | Informational (soft) in v1 |
| `manager_review_deadline` | `DATE` | no | Must be ≥ self deadline |
| `hr_review_deadline` | `DATE` | no | Must be ≥ manager deadline |
| `status` | `VARCHAR(20)` | no | `DRAFT` → `PUBLISHED` → `IN_PROGRESS` → `COMPLETED` |
| `created_by` | `BIGINT` | no | FK → `users.id` (an HR user) |
| `published_at` | `TIMESTAMPTZ` | yes | Set when published |
| `completed_at` | `TIMESTAMPTZ` | yes | Set when every result is published |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints**
- `ck_cycle_status` — only the four statuses.
- `ck_cycle_dates` — the period must be valid and deadlines must be in order.
- `ck_cycle_timestamps` — a `DRAFT` cycle cannot have `published_at`, a `COMPLETED` cycle must have both timestamps, etc.

**Deadlines are soft.** They drive "overdue" badges only. Blocking by date would deadlock HR when someone is late,
so the workflow order (not the calendar) is what is enforced.

**HR API (Phase 3D).** `/api/hr/cycles` reuses this table with no schema change. A new cycle is always `DRAFT`;
create and update accept only name, description and the five dates, and `created_by` comes from the authenticated HR
principal. Every mutation takes a `PESSIMISTIC_WRITE` lock on the cycle row. Publication sets `status = 'PUBLISHED'`
and `published_at` in the same transaction that creates the assignment snapshots. It never sets `completed_at`, and
only `DRAFT` cycles can be updated, configured or published. The existing `ck_cycle_dates`, `ck_cycle_status` and
`ck_cycle_timestamps` constraints remain the database backstop; the service validates the same rules first.

---

## 8. `cycle_kpis`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `cycle_id` | `BIGINT` | no | FK → `performance_cycles.id` |
| `kpi_id` | `BIGINT` | no | FK → `kpi_master.id` |
| `weightage` | `NUMERIC(5,2)` | yes | `NULL` = average mode |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints / indexes**
- `uq_cycle_kpi (cycle_id, kpi_id)` — the same KPI cannot be added twice to a cycle.
- `ck_cycle_kpi_weight` — `0 < weightage <= 100`.
- `ix_cycle_kpis_cycle`, `ix_cycle_kpis_kpi`.

**Why no KPI text copy here?** The cycle is still editable while `DRAFT`; the permanent copy is created at publish
in `employee_kpis`. Duplicating the text in `cycle_kpis` would create a second source of truth for no benefit.

**HR API (Phase 3D).** `POST /api/hr/cycles/{id}/kpis` is a full replacement of the DRAFT selection. Only active
`kpi_master` rows may be selected, each at most once. `weightage` is either `NULL` for every row (average mode) or set
for every row, `0 < w <= 100`, at most 2 decimals, totalling exactly `100.00`. The service validates the whole request
before deleting/inserting, and `ck_cycle_kpi_weight` remains the database guard. The cycle weight is independent of
`kpi_master.default_weightage`, which is never changed by cycle configuration.

---

## 9. `employee_assignments`

The workflow header: one employee, one cycle, one reviewing manager.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `cycle_id` | `BIGINT` | no | FK → `performance_cycles.id` |
| `employee_id` | `BIGINT` | no | FK → `employees.id` (appraised employee) |
| `manager_id` | `BIGINT` | no | FK → `employees.id` (reviewer, snapshot) |
| `status` | `VARCHAR(40)` | no | See workflow statuses below |
| `self_submitted_at` | `TIMESTAMPTZ` | yes | |
| `manager_submitted_at` | `TIMESTAMPTZ` | yes | |
| `hr_finalized_at` | `TIMESTAMPTZ` | yes | |
| `results_published_at` | `TIMESTAMPTZ` | yes | |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Statuses:** `SELF_ASSESSMENT_PENDING`, `MANAGER_REVIEW_PENDING`, `HR_REVIEW_PENDING`, `COMPLETED`, `RESULTS_PUBLISHED`.

**Constraints / indexes**
- `uq_assignment_cycle_employee (cycle_id, employee_id)` — no duplicate appraisal.
- `ck_assignment_not_own_manager` — `manager_id <> employee_id`.
- `ck_assignment_status`.
- `ck_assignment_timestamps` — the status and the four stage timestamps can never disagree. For example, a row
  cannot be `HR_REVIEW_PENDING` without both `self_submitted_at` and `manager_submitted_at`.

**Why `manager_id` is a snapshot:** if the employee changes manager next month, the appraisal they already
completed must still belong to the manager who reviewed it.

**HR API (Phase 3D).** The draft roster is stored here while the cycle is `DRAFT`, with status
`SELF_ASSESSMENT_PENDING` and the selected reviewing manager in `manager_id`. Publication reuses these rows rather
than inserting a second copy. An explicit `managerId` in the roster request sets only this cycle's reviewer snapshot;
it never rewrites `employees.manager_id` or the append-only `employee_manager_assignments` history. When omitted,
the current reporting manager is used. Employee and manager rows are locked while the roster is validated, and the
existing `uq_assignment_cycle_employee` and `ck_assignment_not_own_manager` constraints remain the database
backstop.

`ix_assignments_cycle_status`, `ix_assignments_manager_status`, `ix_assignments_employee` support the three
read-only dashboards (HR by cycle, manager by historical team snapshot, employee by self). Dashboard counts use
database aggregation rather than loading assignment tables into application memory. Manager active direct-report
counts use the current `employees.manager_id`; manager cycle workload uses `employee_assignments.manager_id`.

**Employee API (Phase 3E).** `GET /api/employee/self-assessments/cycles` reads this table scoped by the authenticated
employee and filtered to cycles whose status is exactly `PUBLISHED`; `DRAFT` roster rows and later statuses are
therefore invisible to the employee API. A draft
save changes only `self_assessments`; submission updates this row to `MANAGER_REVIEW_PENDING` and sets
`self_submitted_at`. `manager_id` remains the review snapshot and never needs to be resolved again.

---

## 10. `employee_kpis`

The frozen KPI assignment, created once when the cycle is published.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `assignment_id` | `BIGINT` | no | FK → `employee_assignments.id` |
| `kpi_id` | `BIGINT` | no | FK → `kpi_master.id` (traceability only) |
| `kpi_name` | `VARCHAR(150)` | no | **Copy** of the master name at publish |
| `kpi_description` | `TEXT` | yes | **Copy** |
| `kpi_category` | `VARCHAR(80)` | yes | **Copy** |
| `kpi_type` | `VARCHAR(20)` | no | **Copy** of the master classification (`MANAGER` / `HR`, V4) |
| `weightage` | `NUMERIC(5,2)` | yes | Copy of the cycle weight |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints / indexes**
- `uq_employee_kpi (assignment_id, kpi_id)` — one row per KPI per assignment.
- `ck_employee_kpi_weight`.
- `ck_employee_kpi_type` — `kpi_type IN ('MANAGER', 'HR')`.
- `ix_employee_kpis_assignment`.

The `kpi_id` column is kept so you can trace where the snapshot came from, but **nothing is displayed from
`kpi_master`** — all three assessment stages and every report read the copied columns.

**Phase 3D publication.** `POST /api/hr/cycles/{id}/publish` copies every configured `cycle_kpis` row into one
`employee_kpis` row per assignment in a single transaction: `kpi_name`, `kpi_description`, `kpi_category` and
`weightage` come from the master/cycle at that moment, and `kpi_id` is kept only for traceability. If any snapshot
write fails, the whole publication rolls back. Later edits to `kpi_master` never change these rows, and a published
cycle cannot be reconfigured.

**Phase 3D and 3E needed no migration.** They fit entirely in the V1 tables (`performance_cycles`, `cycle_kpis`,
`employee_assignments`, `employee_kpis`, `self_assessments`) plus the V2 reporting history. V1, V2 and V3 are
unchanged by any later phase.

**V4 adds `kpi_type`.** Phase 5C classifies each KPI as `MANAGER`-assessed or `HR`-assessed. The classification is
copied into the `employee_kpis` snapshot at publication, so a later change to the master never reinterprets an
already-published cycle. Existing rows default to `MANAGER`, which is exactly the behaviour they had before V4.

---

## 11. `self_assessments`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `employee_kpi_id` | `BIGINT` | no | FK → `employee_kpis.id`, **unique** |
| `rating` | `SMALLINT` | yes | 1–5; `NULL` only while saving a draft |
| `comment` | `TEXT` | yes | |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

`uq_self_assessment` on `employee_kpi_id` makes double submission structurally impossible.

**Employee API (Phase 3E).** `PUT /api/employee/self-assessments/{assignmentId}/draft` upserts one row per
`employee_kpi_id`; the assigned KPI set is read from `employee_kpis`, so an id from another assignment is rejected
before it can ever be written. `rating` stays nullable while drafting (the V1 `ck_self_rating` allows `NULL` or
1-5) and is required for every assigned KPI at submission. Submission flips the assignment to
`MANAGER_REVIEW_PENDING` and sets `self_submitted_at`; rows in this table are never edited afterwards. The unique key
and the validate-then-write transaction are what make "one row per KPI" hold under repeated and concurrent saves.

**Self ratings contribute 0 %** to the final score. The scoring code never reads this table.

---

## 12. `manager_assessments`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `employee_kpi_id` | `BIGINT` | no | FK → `employee_kpis.id`, **unique** |
| `manager_id` | `BIGINT` | no | FK → `employees.id` — who actually rated |
| `rating` | `SMALLINT` | yes | 1–5; `NULL` only in a draft |
| `comment` | `TEXT` | yes | |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

Contributes **75 %**. `ck_manager_rating` allows `NULL` for drafts and 1–5 otherwise; submission requires a value
(checked by the service before the status changes).

Phase 3F writes this table through an upsert keyed on `employee_kpi_id`: a draft inserts a row or updates the
existing one, and the unique constraint is the database backstop against duplicate ratings for one KPI. `manager_id`
is copied from the `employee_assignments.manager_id` snapshot, not from the employee's current reporting manager, so
a later reporting-line change never rewrites who reviewed the appraisal. Submission only flips the owning
assignment's `status` to `HR_REVIEW_PENDING` and fills its `manager_submitted_at`; it does not touch this table's
rows again, and there is no schema change for Phase 3F.

---

## 13. `hr_assessments`

Identical shape, but the rater is a user:

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `employee_kpi_id` | `BIGINT` | no | FK → `employee_kpis.id`, **unique** |
| `hr_id` | `BIGINT` | no | FK → `users.id` |
| `rating` | `SMALLINT` | yes | 1–5 |
| `comment` | `TEXT` | yes | |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

Contributes **25 %**.

### Phase 3G/3H usage - no schema change

Phase 3G and Phase 3H reuse this table exactly as migrated in V1, so no new Flyway migration was created and V1-V3
are untouched.

- One row per `employee_kpi_id` while HR is drafting; `uq_hr_assessment` prevents duplicates. The service also
  upserts by that key, so repeated saves update the same row.
- `rating` is nullable during drafting and required for every assigned KPI at submission. `ck_hr_rating` (1-5 or
  null) remains the database guard; the application additionally rejects non-integral values before writing.
- `comment` is optional; trimmed and blank values are stored as `NULL`.
- `hr_id` records the HR user from the authenticated principal. Because HR can edit an existing row, it reflects the
  last HR editor; the row itself is never duplicated or reassigned to another KPI.
- Submission writes `employee_assignments.hr_finalized_at` and moves status to `COMPLETED` in the same transaction
  as result generation, satisfying the existing `ck_assignment_timestamps` rule that `COMPLETED` requires
  `hr_finalized_at` and forbids `results_published_at`.
- The submitted HR ratings are the 25 % scoring input. The manager rating supplies 75 % and the employee self-rating
  supplies 0 %. The HR rows themselves are never recalculated or modified after submission.

---

## 14. `performance_results`

The one official result of an assignment.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `assignment_id` | `BIGINT` | no | FK → `employee_assignments.id`, **unique** |
| `overall_score` | `NUMERIC(4,2)` | no | Rounded `HALF_UP`, 1.00–5.00 |
| `grade` | `VARCHAR(30)` | no | `OUTSTANDING` … `UNSATISFACTORY` |
| `calculation_mode` | `VARCHAR(10)` | no | `WEIGHTED` or `AVERAGE` |
| `status` | `VARCHAR(20)` | no | `DRAFT` (finalised, hidden) / `PUBLISHED` |
| `computed_at` | `TIMESTAMPTZ` | no | When HR finalised |
| `published_at` | `TIMESTAMPTZ` | yes | |
| `published_by` | `BIGINT` | yes | FK → `users.id` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

**Constraints**
- `uq_result_assignment` — one result per assignment, always.
- `ck_result_score`, `ck_result_grade`, `ck_result_mode`, `ck_result_status`.
- `ck_result_publish_fields` — `DRAFT` cannot have publication fields, `PUBLISHED` must have both.

`DRAFT` exists so HR can finalise a batch and release it later. Employees and managers only ever see `PUBLISHED`.

### Phase 3H/3I usage - result generation and publication

Phase 3H writes this table exactly as migrated; no column was added or changed.

- HR submission inserts one header with `status = 'DRAFT'`, `computed_at` set to the submission time, and
  `published_at` / `published_by` left `NULL`. The `ck_result_publish_fields` constraint therefore holds.
- `overall_score` is the rounded final score: `NUMERIC(4,2)`, always between 1.00 and 5.00, rounded `HALF_UP` from
  the exact weighted or average value. `ck_result_score` is the database backstop.
- `calculation_mode` is `WEIGHTED` only when every assigned KPI has a weight and their total is exactly 100.00;
  otherwise it is `AVERAGE` when every weight is `NULL`. Mixed or invalid configurations fail before insertion.
- `grade` is derived from the rounded overall score using the single scale documented in
  [Scoring logic](./SCORING_LOGIC.md):
  `OUTSTANDING` >= 4.50, `EXCELLENT` >= 4.00, `VERY_GOOD` >= 3.50, `GOOD` >= 3.00,
  `NEEDS_IMPROVEMENT` >= 2.00, otherwise `UNSATISFACTORY`.
- `uq_result_assignment` guarantees one result per assignment. The service also checks for an existing result while
  holding the assignment lock and returns 409 rather than overwriting or duplicating it.
- At finalization the result stays `DRAFT` and `results_published_at` on the assignment stays `NULL`; employee and
  manager APIs cannot read it.
- Phase 3I reuses the existing publication columns without a migration. HR single/bulk publication changes the
  result to `PUBLISHED`, writes `published_at` / `published_by`, changes the assignment to `RESULTS_PUBLISHED`, and
  writes `results_published_at` in one transaction. Repeated single publication conflicts; bulk publication skips
  ineligible assignments and never creates missing results.
- Employee and manager result queries require both publication timestamps plus the `PUBLISHED` result and
  `RESULTS_PUBLISHED` assignment states, and require the timestamps to be equal. Manager access uses the manager ID
  frozen on the assignment.
- V1 constrains each table's publication fields against its own status, but it does not enforce cross-table status or
  timestamp equality. The publication transaction writes both states/timestamps together; the read queries hide any
  inconsistent cross-table state.

---

## 15. `performance_result_items` — why this table is required

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGINT` identity | no | PK |
| `result_id` | `BIGINT` | no | FK → `performance_results.id` |
| `employee_kpi_id` | `BIGINT` | no | FK → `employee_kpis.id` |
| `kpi_name` | `VARCHAR(150)` | no | Frozen copy |
| `weightage` | `NUMERIC(5,2)` | yes | Frozen copy |
| `self_rating` | `SMALLINT` | yes | Frozen copy (0 % weight, shown for context) |
| `self_comment` | `TEXT` | yes | |
| `manager_rating` | `SMALLINT` | **yes** | Frozen copy; `NULL` only for an `HR`-assessed KPI (V4) |
| `manager_comment` | `TEXT` | yes | |
| `hr_rating` | `SMALLINT` | no | Frozen copy |
| `hr_comment` | `TEXT` | yes | |
| `final_score` | `NUMERIC(4,2)` | no | `manager × 0.75 + hr × 0.25`; for an `HR` KPI just `hr` |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | no | |

`uq_result_item (result_id, employee_kpi_id)`. `ck_result_item_stage_rating` requires at least one of
`manager_rating` / `hr_rating` to be present, so an `HR`-assessed item can never be written without a rating.

**Why it is not redundant.** Without it, a historical report would have to be rebuilt at read time by joining
`performance_results` → `employee_kpis` → three assessment tables. That means:

1. The report would change if any of those rows were ever edited.
2. Every report request would need a five-table join and a recalculation.
3. Comments and weights would be ambiguous, because a KPI's master text can change.

With it, a published appraisal is a self-contained record. Opening it is one query on one table, the numbers are
exactly what was calculated at finalisation, and nothing upstream can alter them. This is the difference between
"we can recompute it" and "this is what was officially agreed".

### Phase 3H usage - one item per assigned KPI

Phase 3H writes one item for every frozen `employee_kpis` row of the assignment, in the same transaction as the
header and the assignment status change.

- `kpi_name` and `weightage` are copied from the `employee_kpis` snapshot, never from `kpi_master`. In weighted mode
  every item carries its frozen weight; in average mode `weightage` is `NULL`.
- `manager_rating`, `hr_rating`, their comments, and `self_rating` / `self_comment` are copied from the assessment
  rows. Self fields are context only: `final_score` is calculated from manager and HR ratings alone.
- `final_score` is `(manager_rating * 0.75) + (hr_rating * 0.25)` for a `MANAGER`-assessed KPI. With integer ratings
  the value is always exact at two decimals (for example 4.25 or 5.00), so no premature rounding is needed.
- An `HR`-assessed KPI has no manager rating at all, so `manager_rating` stays `NULL` and `final_score` equals the HR
  rating. The 75/25 split is never applied to a rating that does not exist, and no score is invented.
- `uq_result_item (result_id, employee_kpi_id)` prevents a duplicate breakdown row. Items are written once; a later
  HR submission is rejected before any new row is created.
- The current schema has no description or category columns on this table. Those frozen fields remain available on
  `employee_kpis` and in the HR review DTO, but they are not duplicated into the permanent result item. No column was
  invented for them.
- Phase 3I publication does not recalculate or overwrite these items; it changes only the result header and assignment
  publication fields described above.

---

## 16. Relationships

```
users 1 ─── 1 employees                     (one login, one profile)
users 1 ─── * password_reset_tokens
users 1 ─── * kpi_master        (created_by, optional)
users 1 ─── * performance_cycles (created_by)
users 1 ─── * hr_assessments     (hr_id)
users 1 ─── * performance_results (published_by, optional)

employees 1 ─── * employees              (manager → subordinates)
employees 1 ─── * employee_assignments   (as appraised employee)
employees 1 ─── * employee_assignments   (as reviewing manager)
employees 1 ─── * manager_assessments

performance_cycles 1 ─── * cycle_kpis
performance_cycles 1 ─── * employee_assignments
kpi_master 1 ─── * cycle_kpis
kpi_master 1 ─── * employee_kpis

employee_assignments 1 ─── * employee_kpis
employee_assignments 1 ─── 1 performance_results

employee_kpis 1 ─── 0..1 self_assessments
employee_kpis 1 ─── 0..1 manager_assessments
employee_kpis 1 ─── 0..1 hr_assessments
employee_kpis 1 ─── * performance_result_items

performance_results 1 ─── * performance_result_items
```

### Deletion behaviour

| Relationship | Behaviour | Reason |
|---|---|---|
| `password_reset_tokens.user_id` | `ON DELETE CASCADE` | Tokens are disposable, not history |
| `kpi_master.created_by` | `ON DELETE SET NULL` | Deleting a user must not delete a KPI |
| everything else | `NO ACTION` (default) | Historical data must block deletion instead of disappearing |

Because of this, the application never hard-deletes users, employees, KPIs, cycles with data, results or results
items. Deactivation is the supported path.

---

## 17. Enum storage

All enums are stored as **`VARCHAR` with a `CHECK` constraint**, not as native PostgreSQL enum types.

To make that happen, every enum field is mapped like this:

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.VARCHAR)
@Column(name = "status", nullable = false, length = 20)
private CycleStatus status;
```

**If you add a new enum column, copy both annotations.** Without `@JdbcTypeCode(SqlTypes.VARCHAR)`, Hibernate 7
expects a native enum column and `ddl-auto=validate` will refuse to start against this schema.

Advantages: `VARCHAR` columns are easy to read in `psql`, easy to add a value to later, and safe to validate.

---

## 18. Index summary

| Index | Table | Purpose |
|---|---|---|
| `ux_users_email_lower` | users | Case-insensitive login lookup and uniqueness |
| `ix_users_role_enabled` | users | "all active managers" queries |
| `uq_employees_user`, `uq_employees_code` | employees | One profile per user, unique code |
| `ux_employees_code_lower` | employees | Case-insensitive unique employee code (functional, V3) |
| `ix_employees_manager` | employees | A manager's direct reports |
| `ix_employees_status` | employees | Active/inactive filters |
| `ix_employees_name_lower` | employees | Case-insensitive name search |
| `ix_password_reset_user`, `ix_password_reset_expiry` | password_reset_tokens | Lookup and cleanup |
| `ux_kpi_master_name_lower` | kpi_master | Case-insensitive uniqueness |
| `ix_kpi_master_active` | kpi_master | Active KPI list |
| `ux_cycles_name_lower`, `ix_cycles_status`, `ix_cycles_dates` | performance_cycles | Lists and filters |
| `ix_cycle_kpis_cycle`, `ix_cycle_kpis_kpi` | cycle_kpis | Cycle configuration and KPI usage |
| `ix_assignments_cycle_status`, `ix_assignments_manager_status`, `ix_assignments_employee` | employee_assignments | The three dashboards |
| `ix_employee_kpis_assignment` | employee_kpis | Loading one appraisal form |
| `ix_manager_assessments_manager` | manager_assessments | Manager workload |
| `ix_hr_assessments_user` | hr_assessments | HR workload |
| `ix_results_status`, `ix_results_published_at` | performance_results | Pending vs published |
| `ix_result_items_result` | performance_result_items | Opening a result |
| `uq_ema_active_employee` | employee_manager_assignments | At most one current manager per employee (partial unique) |
| `ix_ema_employee_history`, `ix_ema_manager` | employee_manager_assignments | Reporting history and a manager's reports |

Uniqueness constraints also create indexes, so they double as lookup paths.

### Dashboard data

Phase 3J introduces no table, column, index or migration. Dashboard values are derived from existing employee,
cycle, assignment and result records. Published-result counts and employee latest-result lookup require the same
published result status, `RESULTS_PUBLISHED` assignment status, and equal non-null publication timestamps used by
result visibility endpoints. Queue counts filter assignments by the published cycle state and exact workflow
status.

---

## 19. Mapping to JPA entities

| Table | Entity |
|---|---|
| `users` | `User` |
| `employees` | `Employee` |
| `password_reset_tokens` | `PasswordResetToken` |
| `kpi_master` | `KpiMaster` |
| `performance_cycles` | `PerformanceCycle` |
| `cycle_kpis` | `CycleKpi` |
| `employee_assignments` | `EmployeeAssignment` |
| `employee_kpis` | `EmployeeKpi` |
| `self_assessments` | `SelfAssessment` |
| `manager_assessments` | `ManagerAssessment` |
| `hr_assessments` | `HrAssessment` |
| `performance_results` | `PerformanceResult` |
| `performance_result_items` | `PerformanceResultItem` |
| `employee_manager_assignments` | `EmployeeManagerAssignment` |

All entities extend `BaseEntity` (`id`, `createdAt`, `updatedAt`). All associations are **unidirectional** and
`FetchType.LAZY`, so no entity graph can accidentally load the whole database or recurse during JSON serialisation.
