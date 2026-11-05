# Phase 5 — Evalora Live Backend & PostgreSQL Integration Test Report

Evalora PMS · Live Integration Testing · Spring Boot Backend + PostgreSQL + React Frontend
Status: BLOCKED — PostgreSQL authentication credentials unavailable

---

## 1. Executive summary

Phase 5 integration testing could not proceed beyond environment readiness inspection. The Spring Boot backend cannot be started, and the PostgreSQL database cannot be inspected, because the required credentials (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) are not configured in the environment and no `.pgpass` file exists. PostgreSQL 18 is running and accepts connections only via scram-sha-256 authentication. All database-backed and backend integration tests (sections 4–18) are BLOCKED. No source code, migration, schema, or dependency changes were made.

## 2. Environment and software versions

| Component | Version | Required | Status |
|-----------|---------|----------|--------|
| JDK | 21.0.12.1 LTS | Java 21 | PASS |
| Maven wrapper | `backend/mvnw.cmd` present | `mvnw` | PASS |
| Node.js | v24.15.0 | 20+ | PASS |
| npm | 11.12.1 | — | PASS |
| PostgreSQL server | 18 (service `postgresql-x64-18`, Running) | 16+ | RUNNING |
| PostgreSQL client (`psql`) | `C:\Program Files\PostgreSQL\18\bin\psql.exe` | — | Present |
| Backend jar | `backend/target/backend-0.0.1-SNAPSHOT.jar` (built) | — | Present |

## 3. Environment differences vs documentation

| Setting | Documented | Actual | Difference |
|-----------|-----------|--------|------------|
| Backend port | 8080 | 8080 (`application.properties:2`) | None |
| Frontend dev port | 5173 | Not started | N/A |
| API base URL | `http://localhost:8080/api` | `VITE_API_BASE_URL` or `http://localhost:8080/api` (`frontend/src/api/axiosInstance.js`) | None |
| Hibernate `ddl-auto` | `validate` | `validate` (`application.properties:14`) | None |
| Email disabled | `evalora.email.enabled=false` | `false` (`application.properties:34`) | None |
| CORS origin | `http://localhost:5173` | `http://localhost:5173` (`SecurityConfig.FRONTEND_ORIGIN`) | None |
| DB name | `evalora` | Unknown (cannot authenticate) | BLOCKED |
| DB role | `evalora_app` | Unknown (cannot authenticate) | BLOCKED |
| Migration files | V1, V2, V3 | Present in `database/migrations/` | None |
| `PROJECT_OVERVIEW.md` | Referenced | Does not exist | Missing doc |

## 4. PostgreSQL readiness

- PostgreSQL 18 service `postgresql-x64-18` is **Running**.
- `psql.exe` is available at `C:\Program Files\PostgreSQL\18\bin\psql.exe`.
- Authentication method: `scram-sha-256` for all local/host connections (`pg_hba.conf`).
- No `.pgpass` file exists at `$env:APPDATA\postgresql\.pgpass` or `C:\Users\DELL\AppData\Roaming\postgresql\.pgpass`.
- No `DB_PASSWORD` environment variable is set.
- `psql -h 127.0.0.1 -U postgres -d postgres -c "SELECT 1;"` hangs waiting for interactive password input (timed out at 10s).
- `psql -h 127.0.0.1 -d postgres -c "SELECT 1;"` (current OS user `dhoni\dell`) also hangs waiting for password.

**Blocker:** PostgreSQL cannot be inspected without superuser or `evalora_app` credentials. Per the safety rules, credentials were not guessed, reset, or overwritten. Database testing is blocked.

## 5. Database and migration verification

- Migration files V1, V2, V3 are present in `database/migrations/` and were read (read-only).
- V1: 13 initial tables (`users`, `employees`, `password_reset_tokens`, etc.), constraints, indexes including `ux_users_email_lower` (case-insensitive email) and `uq_employees_code` (case-sensitive code).
- V2: `employee_manager_assignments` append-only reporting history with partial unique index `uq_ema_active_employee`.
- V3: `DO $$` block checking for case-insensitive employee-code duplicates, then creates `ux_employees_code_lower` functional unique index.
- Flyway schema state: **UNVERIFIED** — cannot connect to the database to check `flyway_schema_history` or existing tables.
- Hibernate `ddl-auto=validate`: **UNVERIFIED** — backend cannot be started without DB credentials.
- **BLOCKED** — cannot verify database structure, migration history, or schema validity against a live PostgreSQL instance.

## 6. Backend startup results

Backend was **not started**. Preconditions unmet:

| Precondition | Status |
|--------------|--------|
| `DB_URL` env var | Not set (empty) |
| `DB_USERNAME` env var | Not set (empty) |
| `DB_PASSWORD` env var | Not set |
| `JWT_SECRET` env var | Not set (backend refuses to start without it) |
| `MIGRATIONS_LOCATION` | Defaults to `../database/migrations` relative to `backend/` working directory |
| PostgreSQL connectivity | Cannot authenticate |

`application.properties` reads `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`, `${JWT_SECRET}` with no fallback values (lines 8-10, 43-44). `JwtService` validates the secret at startup and refuses to start if missing or under 32 bytes. The backend cannot be launched without setting these variables, which would require obtaining or creating credentials — outside the scope of this read-only testing phase.

**BLOCKED** — backend startup, Flyway execution, Hibernate validation, security config loading, JWT config loading, CORS config loading, and Swagger UI verification are all blocked.

## 7. Authentication results

All authentication tests BLOCKED — backend not running, no database access.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5A-01 | Valid HR login | BLOCKED |
| 5A-02 | Valid manager login | BLOCKED |
| 5A-03 | Valid employee login | BLOCKED |
| 5A-04 | Invalid password | BLOCKED |
| 5A-05 | Unknown email | BLOCKED |
| 5A-06 | Disabled account | BLOCKED |
| 5A-07 | Locked account | BLOCKED |
| 5A-08 | Missing token | BLOCKED |
| 5A-09 | Malformed token | BLOCKED |
| 5A-10 | Expired token | BLOCKED |

## 8. Password management results

All password management tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5B-01 | Change password with valid credentials | BLOCKED |
| 5B-02 | Change password with incorrect current password | BLOCKED |
| 5B-03 | Forgot-password generic response | BLOCKED |
| 5B-04 | Reset-password (dev flow) | BLOCKED |
| 5B-05 | Expired reset token | BLOCKED |
| 5B-06 | Reuse of consumed reset token | BLOCKED |
| 5B-07 | Invalid reset token | BLOCKED |

## 9. Authorization results

All authorization tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5C-01 | HR accessing HR endpoints | BLOCKED |
| 5C-02 | Manager accessing manager endpoints | BLOCKED |
| 5C-03 | Employee accessing employee endpoints | BLOCKED |
| 5C-04 | Manager attempting HR endpoints | BLOCKED |
| 5C-05 | Employee attempting HR endpoints | BLOCKED |
| 5C-06 | HR attempting employee-only endpoints | BLOCKED |
| 5C-07 | Unauthenticated access to protected endpoints | BLOCKED |

## 10. HR staff management results

All staff management tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5D-01 | Create an employee | BLOCKED |
| 5D-02 | Create a manager | BLOCKED |
| 5D-03 | Retrieve employee list and details | BLOCKED |
| 5D-04 | Retrieve manager list and details | BLOCKED |
| 5D-05 | Update employee information | BLOCKED |
| 5D-06 | Update manager information | BLOCKED |
| 5D-07 | Deactivate and reactivate staff | BLOCKED |
| 5D-08 | Reset passwords | BLOCKED |
| 5D-09 | Assign an employee to a manager | BLOCKED |
| 5D-10 | Reassign an employee | BLOCKED |
| 5D-11 | Reject invalid manager assignments | BLOCKED |
| 5D-12 | Prevent self-assignment | BLOCKED |
| 5D-13 | Prevent unsupported manager removal | BLOCKED |
| 5D-14 | Verify historical manager assignment behavior | BLOCKED |

## 11. KPI management results

All KPI tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5E-01 | Create KPI | BLOCKED |
| 5E-02 | Retrieve KPI list | BLOCKED |
| 5E-03 | Retrieve KPI detail | BLOCKED |
| 5E-04 | Update KPI | BLOCKED |
| 5E-05 | Deactivate KPI | BLOCKED |
| 5E-06 | Reactivate KPI | BLOCKED |
| 5E-07 | Reject invalid rating or weight values | BLOCKED |
| 5E-08 | Verify active/inactive filtering | BLOCKED |
| 5E-09 | Verify authorization | BLOCKED |

## 12. Performance cycle results

All cycle tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5F-01 | Create a performance cycle | BLOCKED |
| 5F-02 | Retrieve cycle list and detail | BLOCKED |
| 5F-03 | Update a DRAFT cycle | BLOCKED |
| 5F-04 | Assign KPIs | BLOCKED |
| 5F-05 | Assign employees | BLOCKED |
| 5F-06 | Assign reviewing managers | BLOCKED |
| 5F-07 | Verify weighted KPI assignments | BLOCKED |
| 5F-08 | Verify unweighted KPI assignments | BLOCKED |
| 5F-09 | Reject invalid partial weights | BLOCKED |
| 5F-10 | Reject weighted totals other than 100% | BLOCKED |
| 5F-11 | Reject invalid employee or manager assignments | BLOCKED |
| 5F-12 | Publish a valid cycle | BLOCKED |

## 13. Employee self-assessment results

All self-assessment tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5G-01 | Employee retrieves assigned assessment | BLOCKED |
| 5G-02 | Employee retrieves assessment status | BLOCKED |
| 5G-03 | Employee saves a partial draft | BLOCKED |
| 5G-04 | Employee updates an existing draft | BLOCKED |
| 5G-05 | Employee saves an empty draft | BLOCKED |
| 5G-06 | Employee cannot submit with missing KPI ratings | BLOCKED |
| 5G-07 | Employee cannot submit ratings outside 1–5 | BLOCKED |
| 5G-08 | Employee submits a complete self-assessment | BLOCKED |
| 5G-09 | Employee cannot access another employee's assessment | BLOCKED |
| 5G-10 | Submitted self-assessment becomes read-only | BLOCKED |

## 14. Manager assessment results

All manager assessment tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5H-01 | Manager retrieves assessment queue | BLOCKED |
| 5H-02 | Manager retrieves assigned employee's assessment | BLOCKED |
| 5H-03 | Manager sees employee self-assessment as reference | BLOCKED |
| 5H-04 | Manager saves partial drafts | BLOCKED |
| 5H-05 | Manager updates existing drafts | BLOCKED |
| 5H-06 | Manager cannot submit incomplete ratings | BLOCKED |
| 5H-07 | Manager submits a complete assessment | BLOCKED |
| 5H-08 | Manager cannot access employees outside ownership | BLOCKED |
| 5H-09 | Manager cannot access another manager's assignments | BLOCKED |
| 5H-10 | Submitted manager assessment becomes read-only | BLOCKED |

## 15. HR assessment and scoring results

All HR assessment and scoring tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5I-01 | HR retrieves HR assessment queue | BLOCKED |
| 5I-02 | HR retrieves eligible assessment | BLOCKED |
| 5I-03 | HR saves incomplete drafts | BLOCKED |
| 5I-04 | HR updates ratings | BLOCKED |
| 5I-05 | HR cannot finalize with missing ratings | BLOCKED |
| 5I-06 | HR finalizes a complete assessment | BLOCKED |
| 5I-07 | HR cannot modify finalized assessment data | BLOCKED |

## 16. Result publication and visibility results

All result publication tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5J-01 | HR retrieves results eligible for publication | BLOCKED |
| 5J-02 | HR retrieves individual result details | BLOCKED |
| 5J-03 | HR publishes one eligible result | BLOCKED |
| 5J-04 | Duplicate publication attempt | BLOCKED |
| 5J-05 | Publication of ineligible result | BLOCKED |
| 5J-06 | Bulk publish a cycle | BLOCKED |
| 5J-07 | Verify published and skipped counts | BLOCKED |
| 5J-08 | Verify behavior when some results already published | BLOCKED |
| 5J-09 | Verify transaction and partial-success semantics | BLOCKED |
| 5J-10 | Employee retrieves own published results | BLOCKED |
| 5J-11 | Employee retrieves own individual published result | BLOCKED |
| 5J-12 | Employee accesses another employee's result | BLOCKED |
| 5J-13 | Employee accesses an unpublished result | BLOCKED |
| 5J-14 | Manager retrieves published team results | BLOCKED |
| 5J-15 | Manager retrieves authorized employee results | BLOCKED |
| 5J-16 | Manager accesses another manager's employee result | BLOCKED |
| 5J-17 | Manager retrieves unpublished results | BLOCKED |

## 17. Frontend browser integration results

Frontend was **not started** against a running backend. All browser workflow tests BLOCKED.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5K-01 | HR browser workflow (login through bulk result publication) | BLOCKED |
| 5K-02 | Manager browser workflow (login through team results) | BLOCKED |
| 5K-03 | Employee browser workflow (login through published results) | BLOCKED |

Navigation, API requests, loading/empty states, error messages, refresh behavior, duplicate-submission guards, read-only behavior, browser console errors, and unexpected network requests are all unverified.

## 18. CORS results

CORS preflight from the actual frontend origin (`http://localhost:5173`) was **not tested** — neither backend nor frontend was running.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5L-01 | CORS preflight from frontend origin | BLOCKED |

## 19. Concurrency and transaction results

All concurrency tests BLOCKED — no backend running, no database access.

| Test ID | Scenario | Status |
|---------|----------|--------|
| 5M-01 | Two simultaneous login attempts near lock threshold | BLOCKED |
| 5M-02 | Concurrent password reset with same token | BLOCKED |
| 5M-03 | Concurrent assessment draft/finalization | BLOCKED |
| 5M-04 | Concurrent result publication | BLOCKED |
| 5M-05 | Concurrent bulk publication | BLOCKED |
| 5M-06 | Transaction rollback on mid-operation failure | BLOCKED |
| 5M-07 | Row-lock behavior for user and token operations | BLOCKED |

## 20. Failed and blocked tests

**Blocked (not failed):** 100% of Phase 5 live integration tests are blocked due to the environment blocker below. No tests were executed, so no failures were observed.

| Blocker ID | Description | Impact |
|------------|-------------|--------|
| BLK-01 | PostgreSQL 18 is running but requires scram-sha-256 authentication; no credentials (postgres superuser password or `evalora_app` password) are available; no `.pgpass` file exists; `DB_PASSWORD` env var is not set | Cannot inspect database, verify migrations, or confirm `evalora` database / `evalora_app` role existence |
| BLK-02 | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` environment variables are all unset; `application.properties` has no fallback values; backend refuses to start without them | Cannot start Spring Boot backend; cannot run any backend or end-to-end integration test |
| BLK-03 | `PROJECT_OVERVIEW.md` referenced in Phase 5 instructions does not exist | Documentation gap; does not block testing directly |

## 21. Test records created and cleanup status

No test records were created. No database connections were established. No backend was started. No frontend was started. Cleanup is not applicable.

| Item | Status |
|------|--------|
| Test users created | None |
| Test KPIs created | None |
| Test cycles created | None |
| Test assignments created | None |
| Published results | None |
| Remaining test records | None |

## 22. Remaining risks and limitations

1. **PostgreSQL credentials unavailable:** The `evalora` database and `evalora_app` role may or may not exist. Without superuser credentials, this cannot be verified. The backend cannot start until `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` are set in the environment.
2. **Flyway migration state unverified:** V1-V3 migration files exist and were read, but `flyway_schema_history` could not be inspected. It is unknown whether migrations have been applied to any database.
3. **Hibernate validation unverified:** `ddl-auto=validate` could not be exercised against a live PostgreSQL instance. Entity-to-table matching was not validated at runtime.
4. **PostgreSQL-specific behavior unverified:** Row locking, functional indexes, partial unique indexes, and scram-sha-256 authentication were not tested against a live PostgreSQL 18 instance. All backend test evidence to date is H2-based (505 tests, 0 failures).
5. **CORS preflight unverified:** CORS from `http://localhost:5173` against the running backend was not tested.
6. **Frontend browser integration unverified:** No React frontend was started against a live backend.

## 23. Overall readiness assessment

**INTEGRATION BLOCKED — ENVIRONMENT NOT READY**

The application code is complete and consistent (Phase 4E audit: 8/8 contract matches, zero defects, all frontend tests passing at 275/275, lint exit 0, build exit 0). However, live integration testing against the actual Spring Boot backend and PostgreSQL database cannot proceed because:

1. PostgreSQL 18 is running but authenticates via scram-sha-256 with no available credentials.
2. No `.pgpass` file exists and no database password environment variables are set.
3. The backend requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` with no fallback values, and none are configured.

To unblock Phase 5, an operator must:
- Provide PostgreSQL superuser or `evalora_app` credentials (or create the `evalora` database and `evalora_app` role per `docs/SETUP_GUIDE.md` section 3).
- Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` environment variables.
- Optionally set `SPRING_PROFILES_ACTIVE=dev` and `EVALORA_BOOTSTRAP_HR_EMAIL`/`EVALORA_BOOTSTRAP_HR_PASSWORD` to bootstrap an HR account.
- Start the backend from `backend/` with `.\mvnw.cmd spring-boot:run` (or the built jar).
- Then execute all Phase 5 test sections (4–18) against the running system.

No credentials were guessed, reset, or overwritten. No existing database was modified.

---

## 24. Scope confirmation

Confirmed:

- [x] No backend source files modified.
- [x] No frontend source files modified.
- [x] No database migration or schema files modified.
- [x] No scoring or workflow rules modified.
- [x] No dependencies installed or updated.
- [x] No existing database reset or destructive SQL executed.
- [x] No test data was created (no database connection established).
- [x] No test data was cleaned up (none created).
- [x] No records remain (none created).
- [x] No commit or push performed.

Phase 5 is stopped here. Phase 6 is not begun. No corrections are applied.
