# Testing

The backend tests run from the `backend` directory with Java 21:

```powershell
.\mvnw.cmd clean test
```

Build the packaged application after tests pass:

```powershell
.\mvnw.cmd package
```

## Phase 3H coverage

Phase 3H tests cover:

- Exact per-KPI scores (`4.25`, `3.25`, `1.00`, `5.00`) and exclusion of self-rating.
- Weighted and average overall scores, repeating decimal averages, `HALF_UP`
  rounding, and inclusive grade thresholds.
- Empty, partial, mixed, zero, negative, over-100 and non-100 weight
  configurations, plus missing manager/HR ratings.
- One result header and one item per frozen KPI, unpublished `DRAFT` state,
  snapshot immutability, and duplicate-result conflicts.
- Transaction rollback when item persistence fails and concurrent
  submissions producing only one result.

The pure calculator tests run without Spring or a database. The result
generation tests exercise the service and HTTP workflow using H2 in
PostgreSQL compatibility mode. They do not prove PostgreSQL-specific
constraints or actual PostgreSQL row-lock behavior.

## Phase 3I coverage

Database-backed publication and visibility tests cover individual and bulk
publication, partial-success skip behavior, repeated requests, publication
rollback, publication metadata and score/item immutability, HR-only
authorization, employee ownership, manager access through the assignment's
historical manager snapshot, and hidden/unpublished/inconsistent results.
Both publication timestamps must be present and equal. Latch-coordinated tests
race individual and bulk publication. All these tests
use H2 in PostgreSQL compatibility mode; they do not prove PostgreSQL's
row-locking or migration behavior.

## Phase 3J dashboard coverage

`DashboardIntegrationTest` exercises all three authenticated HTTP endpoints
against persisted H2 data. It verifies active employee/manager counts and
inactive-account exclusion, all-cycle versus ever-published-cycle counts,
pending/completed/published workflow counters, current manager team size
versus historical assignment ownership, employee current-cycle status and
stage due date, persisted latest published score, and exclusion of DRAFT
results. The tests also check unauthenticated and wrong-role responses and
that query parameters containing other user/manager IDs cannot override the
authenticated principal. Counts and responses are database-backed rather
than mocked. As with the preceding suites, H2 PostgreSQL compatibility mode
does not verify PostgreSQL-specific query or locking behavior.

## Full-suite test environment

The full suite also covers the preceding authentication, staff, KPI, cycle,
employee self-assessment, manager-assessment and HR-assessment phases. Tests
use an in-memory H2 database and a test-only JWT secret; they do not read `.env`,
connect to the network, or use the configured PostgreSQL database. Flyway is
disabled in tests because the PostgreSQL migrations contain features H2 does
not support. Hibernate creates the test schema from the entity mappings.

Phase 3I baseline verification passed with 501 tests, 0 failures, 0 errors and
0 skipped. After Phase 3J, both `.\mvnw.cmd clean test` and
`.\mvnw.cmd package` passed with 505 tests, 0 failures, 0 errors and 0 skipped.
The backend was not verified against an actual PostgreSQL server; the
database-backed test evidence is H2-only.

Run a focused test class with:

```powershell
.\mvnw.cmd test -Dtest=PerformanceScoreCalculatorTest
.\mvnw.cmd test -Dtest=HrAssessmentIntegrationTest
```

See [setup guide](./SETUP_GUIDE.md) for the phase-by-phase suite inventory and
[performance workflow](./PERFORMANCE_WORKFLOW.md) for lifecycle behavior.
