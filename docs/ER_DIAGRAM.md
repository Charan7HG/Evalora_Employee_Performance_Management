# Evalora — ER Diagram

This diagram mirrors the implemented schema in `database/migrations/V1__init_schema.sql` plus the Phase 3B4
`V2__employee_manager_assignments.sql` and `V3__case_insensitive_employee_code_uniqueness.sql`, and the JPA entities in
`com.evalora.backend.entity`. Every table, column and relationship shown here exists in the database.

```mermaid
erDiagram
    USERS {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar role
        boolean enabled
        int failed_login_attempts
        timestamptz locked_until
        timestamptz last_login_at
        timestamptz created_at
        timestamptz updated_at
    }

    EMPLOYEES {
        bigint id PK
        bigint user_id FK,UK
        varchar employee_code UK
        varchar full_name
        varchar department
        varchar designation
        bigint manager_id FK
        date joining_date
        varchar status
        timestamptz created_at
        timestamptz updated_at
    }

    PASSWORD_RESET_TOKENS {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        timestamptz expires_at
        timestamptz used_at
        timestamptz created_at
        timestamptz updated_at
    }

    EMPLOYEE_MANAGER_ASSIGNMENTS {
        bigint id PK
        bigint employee_id FK
        bigint manager_id FK
        bigint assigned_by FK
        timestamptz effective_from
        timestamptz effective_to
        timestamptz created_at
        timestamptz updated_at
    }

    KPI_MASTER {
        bigint id PK
        varchar name UK
        text description
        varchar category
        numeric default_weightage
        boolean active
        bigint created_by FK
        timestamptz created_at
        timestamptz updated_at
    }

    PERFORMANCE_CYCLES {
        bigint id PK
        varchar name UK
        text description
        date start_date
        date end_date
        date self_assessment_deadline
        date manager_review_deadline
        date hr_review_deadline
        varchar status
        bigint created_by FK
        timestamptz published_at
        timestamptz completed_at
        timestamptz created_at
        timestamptz updated_at
    }

    CYCLE_KPIS {
        bigint id PK
        bigint cycle_id FK
        bigint kpi_id FK
        numeric weightage
        timestamptz created_at
        timestamptz updated_at
    }

    EMPLOYEE_ASSIGNMENTS {
        bigint id PK
        bigint cycle_id FK
        bigint employee_id FK
        bigint manager_id FK
        varchar status
        timestamptz self_submitted_at
        timestamptz manager_submitted_at
        timestamptz hr_finalized_at
        timestamptz results_published_at
        timestamptz created_at
        timestamptz updated_at
    }

    EMPLOYEE_KPIS {
        bigint id PK
        bigint assignment_id FK
        bigint kpi_id FK
        varchar kpi_name
        text kpi_description
        varchar kpi_category
        numeric weightage
        timestamptz created_at
        timestamptz updated_at
    }

    SELF_ASSESSMENTS {
        bigint id PK
        bigint employee_kpi_id FK,UK
        smallint rating
        text comment
        timestamptz created_at
        timestamptz updated_at
    }

    MANAGER_ASSESSMENTS {
        bigint id PK
        bigint employee_kpi_id FK,UK
        bigint manager_id FK
        smallint rating
        text comment
        timestamptz created_at
        timestamptz updated_at
    }

    HR_ASSESSMENTS {
        bigint id PK
        bigint employee_kpi_id FK,UK
        bigint hr_id FK
        smallint rating
        text comment
        timestamptz created_at
        timestamptz updated_at
    }

    PERFORMANCE_RESULTS {
        bigint id PK
        bigint assignment_id FK,UK
        numeric overall_score
        varchar grade
        varchar calculation_mode
        varchar status
        timestamptz computed_at
        timestamptz published_at
        bigint published_by FK
        timestamptz created_at
        timestamptz updated_at
    }

    PERFORMANCE_RESULT_ITEMS {
        bigint id PK
        bigint result_id FK
        bigint employee_kpi_id FK
        varchar kpi_name
        numeric weightage
        smallint self_rating
        text self_comment
        smallint manager_rating
        text manager_comment
        smallint hr_rating
        text hr_comment
        numeric final_score
        timestamptz created_at
        timestamptz updated_at
    }

    %% Identity and profile
    USERS ||--|| EMPLOYEES : "has one staff profile"
    USERS ||--o{ PASSWORD_RESET_TOKENS : "requests"
    USERS ||--o{ KPI_MASTER : "creates (optional)"
    USERS ||--o{ PERFORMANCE_CYCLES : "creates"
    USERS ||--o{ HR_ASSESSMENTS : "rates as HR"
    USERS ||--o{ PERFORMANCE_RESULTS : "publishes (optional)"

    %% Reporting line
    EMPLOYEES ||--o{ EMPLOYEES : "manages"
    EMPLOYEES ||--o{ EMPLOYEE_MANAGER_ASSIGNMENTS : "reports in"
    EMPLOYEES ||--o{ EMPLOYEE_MANAGER_ASSIGNMENTS : "manages in"
    USERS ||--o{ EMPLOYEE_MANAGER_ASSIGNMENTS : "assigns (optional)"

    %% Cycle configuration
    PERFORMANCE_CYCLES ||--o{ CYCLE_KPIS : "contains"
    KPI_MASTER ||--o{ CYCLE_KPIS : "is selected as"

    %% Appraisal workflow
    PERFORMANCE_CYCLES ||--o{ EMPLOYEE_ASSIGNMENTS : "has"
    EMPLOYEES ||--o{ EMPLOYEE_ASSIGNMENTS : "is appraised in"
    EMPLOYEES ||--o{ EMPLOYEE_ASSIGNMENTS : "reviews as manager"
    EMPLOYEE_ASSIGNMENTS ||--o{ EMPLOYEE_KPIS : "snapshots"
    KPI_MASTER ||--o{ EMPLOYEE_KPIS : "is the source of"

    %% Ratings
    EMPLOYEE_KPIS ||--o| SELF_ASSESSMENTS : "has self rating"
    EMPLOYEE_KPIS ||--o| MANAGER_ASSESSMENTS : "has manager rating"
    EMPLOYEE_KPIS ||--o| HR_ASSESSMENTS : "has HR rating"
    EMPLOYEES ||--o{ MANAGER_ASSESSMENTS : "writes"

    %% Final result
    EMPLOYEE_ASSIGNMENTS ||--|| PERFORMANCE_RESULTS : "produces"
    PERFORMANCE_RESULTS ||--o{ PERFORMANCE_RESULT_ITEMS : "freezes"
    EMPLOYEE_KPIS ||--o{ PERFORMANCE_RESULT_ITEMS : "is frozen in"
```

## How to read it

- `||--||` one-to-exactly-one, `||--o{` one-to-many (zero or more), `||--o|` one-to-zero-or-one.
- `PK` primary key, `FK` foreign key, `UK` unique key.
- `timestamptz` columns hold UTC instants.

## Relationship walkthrough

**Users and profiles.** A `users` row is created for every HR, manager and employee account. Each one gets exactly
one `employees` row holding the staff data. The role is stored only in `users`.

**Reporting line.** `employees.manager_id` points back to `employees.id`. This is a unidirectional self-reference:
a manager has no `subordinates` collection, which avoids lazy-loading loops and infinite JSON. The database forbids
`manager_id = id`.

`employee_manager_assignments` (V2) records each reporting period with `effective_from`/`effective_to`. A null
`effective_to` is the current assignment, and a partial unique index allows only one open row per employee. Reassigning
closes the old row and appends a new one, so reporting history is never overwritten; `employees.manager_id` is kept in
sync as the current pointer.

**Cycle setup.** HR creates a `performance_cycles` row, selects KPIs into `cycle_kpis`, and adds employees. Publishing
creates one `employee_assignments` row per employee, plus one `employee_kpis` snapshot per configured KPI.

**Ratings.** Each `employee_kpis` row accepts at most one row in each of `self_assessments`, `manager_assessments`
and `hr_assessments`. The unique constraints are what make a second submission impossible, not just service code.

**Result.** Finalising creates exactly one `performance_results` row (unique on `assignment_id`) and one
`performance_result_items` row per KPI, copying the name, weight, ratings, comments and computed score. Publishing
only changes `status`/`published_at`; the numbers never change afterwards.

## Where the arithmetic lives

No score is computed by the database. `performance_result_items.final_score` and
`performance_results.overall_score` are written by `PerformanceScoreCalculator` during HR submission in Phase 3H,
using `BigDecimal` and `RoundingMode.HALF_UP` for the overall score. The database only enforces that the stored values are inside the valid range (1.00–5.00).
