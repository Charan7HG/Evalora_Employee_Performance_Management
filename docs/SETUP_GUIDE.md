# Evalora — Setup Guide (local development)

Everything runs on your machine. No remote database, no cloud service, no email provider.

---

## 1. Project layout

```
PMS/
├── backend/                     Spring Boot 4.1.2 (Java 21) — Maven project
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/evalora/backend/
│       │   ├── BackendApplication.java
│       │   ├── config/          BootstrapHrSeeder (dev-only HR bootstrap)
│       │   ├── controller/      Auth, HR management/assessment/results, employee and manager result APIs
│       │   ├── dto/             API request/response DTOs
│       │   ├── entity/          JPA entities
│       │   ├── entity/enums/    Role, CycleStatus, AssignmentStatus, ...
│       │   ├── exception/       HrApiException, HrErrorType
│       │   ├── repository/      Spring Data repositories
│       │   ├── security/        SecurityFilterChain, JWT, error handlers
│       │   ├── service/         Auth, management, assessment, scoring and result services
│       │   └── util/            EmailNormalizer, PasswordPolicy, PasswordResetTokenUtil
│       ├── main/resources/application.properties
│       └── test/                H2-backed persistence, security, auth, assessment and result tests
├── database/
│   └── migrations/
│       ├── V1__init_schema.sql                  the original 13-table schema (never edited)
│       ├── V2__employee_manager_assignments.sql append-only reporting history (Phase 3B4)
│       └── V3__case_insensitive_employee_code_uniqueness.sql  LOWER(employee_code) unique index (Phase 3B4)
├── docs/
│   ├── DATABASE_DESIGN.md
│   ├── ER_DIAGRAM.md
│   ├── API_DOCUMENTATION.md
│   ├── PERFORMANCE_WORKFLOW.md
│   ├── SCORING_LOGIC.md
│   ├── SECURITY_DESIGN.md
│   ├── SETUP_GUIDE.md
│   └── TESTING.md
├── frontend/                    React app (not part of Phase 3A/3B1)
└── .env.example
```

---

## 2. Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | not needed — use `mvnw` | `.\mvnw.cmd -v` |
| PostgreSQL | 16 or newer | service running on `localhost:5432` |
| Node.js | 20+ (frontend only, later) | `node -v` |

### Java 21 must be the active JDK

On this machine the default `JAVA_HOME` points at JDK 17, which will break the build. For the current PowerShell
session:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version     # must print 21.x
```

In IntelliJ IDEA: **File → Project Structure → Project SDK → 21**, and set
**Settings → Build Tools → Maven → Runner → JRE → 21**.

---

## 3. Create the local database

Open `psql` as the PostgreSQL superuser (`postgres`, with the password you chose when installing PostgreSQL):

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres
```

Then run:

```sql
CREATE ROLE evalora_app WITH LOGIN PASSWORD 'choose-a-strong-local-password';
CREATE DATABASE evalora OWNER evalora_app;
```

Connect to the new database and give the owner schema rights:

```sql
\c evalora
GRANT ALL ON SCHEMA public TO evalora_app;
```

Verify:

```sql
\conninfo
\du
\l
```

> The password you type here is the value of `DB_PASSWORD`. It never goes into `application.properties`,
> into git, or into any chat.

---

## 4. Environment variables

Copy the example file and fill in your own values:

```powershell
Copy-Item .env.example .env
notepad .env
```

| Variable | Example | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/evalora` | Local database only |
| `DB_USERNAME` | `evalora_app` | Role created above |
| `DB_PASSWORD` | *(your local password)* | Never committed |
| `JWT_SECRET` | *(random 64 hex chars)* | **Required.** HS256 key, at least 32 bytes. The backend refuses to start without it |
| `JWT_EXPIRATION` | `28800000` | Optional. Token lifetime in ms; defaults to 8 hours |
| `EVALORA_BOOTSTRAP_HR_EMAIL` | `hr@your-domain.local` | Optional, `dev` only. Initial HR email |
| `EVALORA_BOOTSTRAP_HR_PASSWORD` | *(your chosen password)* | Optional, `dev` only. Must satisfy the password policy |
| `EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE` | `HR-001` | Optional, `dev` only. Defaults to `HR-001` |
| `EVALORA_BOOTSTRAP_HR_FULL_NAME` | `HR Administrator` | Optional, `dev` only. Defaults to `HR Administrator` |

`.env` is listed in `.gitignore`. Only `.env.example` (placeholders) is tracked.

`application.properties` reads `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` and `${JWT_SECRET}` — there are
deliberately **no fallback values**. A missing variable must stop the application, not silently connect somewhere
unexpected or fall back to a weak key.

The four `EVALORA_BOOTSTRAP_HR_*` variables are different: they have **no default value** and the application starts
without them. They are read only by the `dev`-profile seeder described in section 11. Never put real credentials in
`.env.example` or in any documentation file.

### Generating `JWT_SECRET`

Use a random value of at least 32 bytes. Never reuse a secret from another project, and never commit it.

```powershell
# Option 1 - OpenSSL (Git for Windows / WSL)
openssl rand -hex 32

# Option 2 - PowerShell only
[Convert]::ToHexString((1..32 | ForEach-Object { Get-Random -Maximum 256 })).ToLower()
```

### Setting the variables in PowerShell

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/evalora"
$env:DB_USERNAME = "evalora_app"
$env:DB_PASSWORD = "your-local-password"
$env:JWT_SECRET  = "paste-the-64-hex-characters-here"
```

Or, for a persistent per-user setting:

```powershell
[Environment]::SetEnvironmentVariable("DB_URL", "jdbc:postgresql://localhost:5432/evalora", "User")
```

Restart the terminal afterwards. In IntelliJ IDEA you can instead put them in the run configuration's
**Environment variables** field.

---

## 5. Schema migrations (Flyway)

The schema is created and versioned by Flyway. Hibernate never creates or alters tables.

```
database/migrations/V1__init_schema.sql                                   the 13 original tables, constraints and indexes
database/migrations/V2__employee_manager_assignments.sql                  append-only reporting-line history (Phase 3B4)
database/migrations/V3__case_insensitive_employee_code_uniqueness.sql     LOWER(employee_code) unique index (Phase 3B4)
database/migrations/V4__kpi_type_classification.sql                       kpi_type on kpi_master / employee_kpis (Phase 5C)
database/migrations/V5__hr_kpi_dual_rating.sql                            separate HR and manager KPI ratings (Phase 5E)
```

`V1` is frozen — it must never be edited or renamed, because Flyway validates its checksum on every startup. Phase 3B4
needed reporting history, which V1 could not represent (`employees.manager_id` is a single mutable pointer), so the
change was added as a new `V2` migration instead. V3 adds a functional unique index so employee codes that differ
only by letter case cannot coexist; V1's case-sensitive constraint is kept. If existing rows already contain such
codes, V3 aborts with the conflicting normalized codes and a hint, and changes nothing — rename or remove the wrong
code manually, then start the application again.

`application.properties`:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.locations=${MIGRATIONS_LOCATION:filesystem:../database/migrations}
spring.flyway.baseline-on-migrate=false
spring.flyway.fail-on-missing-locations=true
```

- Local development reads the migrations **outside** the jar so they stay reviewable.
- The Maven build also copies `database/migrations/*.sql` into the jar at `db/migration` (see `pom.xml`), so a container
  can run self-contained with `MIGRATIONS_LOCATION=classpath:db/migration`.
- **Hibernate `validate`** then checks every entity against the real table and **fails fast** on a mismatch.
- Flyway records what it applied in `flyway_schema_history`. Never edit an applied migration — add `V6__...`.

### Where the backend finds the migrations

`spring.flyway.locations` is resolved against the **process working directory**, not the project folder. The default
`filesystem:../database/migrations` therefore only works when the working directory is `backend/`.

**Option A — run from `backend/` (the normal way). Nothing to configure.**

```powershell
cd "C:\Users\DELL\OneDrive\Documents\PMS\backend"
.\mvnw.cmd spring-boot:run
```

Working directory = `backend/` → `../database/migrations` = `PMS\database\migrations`. ✅

**Option B — run from the project root.** The working directory is now `PMS/`, so `../database/migrations` would
point *above* the project and Flyway would fail with "Unable to resolve location". Point `MIGRATIONS_LOCATION` at the
absolute folder instead:

```powershell
cd "C:\Users\DELL\OneDrive\Documents\PMS"
$env:MIGRATIONS_LOCATION = "filesystem:C:/Users/DELL/OneDrive/Documents/PMS/database/migrations"
.\backend\mvnw.cmd -f backend\pom.xml spring-boot:run
```

Use forward slashes (or double backslashes) and an absolute path. `MIGRATIONS_LOCATION` is a **full Flyway location**,
so it needs a prefix such as `filesystem:` or `classpath:`.

**Option C — run the packaged jar from the project root.**

```powershell
cd "C:\Users\DELL\OneDrive\Documents\PMS"
$env:MIGRATIONS_LOCATION = "filesystem:C:/Users/DELL/OneDrive/Documents/PMS/database/migrations"
java -jar backend\target\backend-0.0.1-SNAPSHOT.jar
```

Or run the jar's packaged copy from anywhere:

```powershell
$env:MIGRATIONS_LOCATION = "classpath:db/migration"
java -jar backend\target\backend-0.0.1-SNAPSHOT.jar
```

Or run the jar from `backend/`, where the default path is already correct:

```powershell
cd backend
java -jar target\backend-0.0.1-SNAPSHOT.jar
```

**Making it permanent (optional).** To avoid setting the variable every time:

```powershell
[Environment]::SetEnvironmentVariable(
    "MIGRATIONS_LOCATION",
    "filesystem:C:/Users/DELL/OneDrive/Documents/PMS/database/migrations",
    "User")
```

`spring.flyway.fail-on-missing-locations=true` is what makes that promise true. Without it Flyway only logs
`Skipping location: … (not found)` at ERROR level, keeps going, and the first symptom becomes a Hibernate
`schema validation: missing column` error against a stale schema — which is exactly how the Phase 5C V4 migration
appeared to "fail". With it, a wrong path fails immediately with a "location not found" error.

**VS Code.** `.vscode/launch.json` runs `BackendApplication` with `cwd` = `${workspaceFolder}/backend`, so the default
relative path resolves inside the project. Do not set it back to the workspace root without also setting
`MIGRATIONS_LOCATION`. `mvnw spring-boot:run` copies `database/migrations` into the build output before it forks the
JVM, so both the filesystem default and `classpath:db/migration` work.

### Adding a migration later

1. Create `database/migrations/V6__describe_change.sql` (V5 is `V5__hr_kpi_dual_rating.sql`).
2. Write only additive, non-destructive SQL (`ALTER TABLE ... ADD COLUMN`, `CREATE INDEX`, ...).
3. Start the backend; Flyway applies it automatically.
4. Update `docs/DATABASE_DESIGN.md` and `docs/ER_DIAGRAM.md`.

Never use `ddl-auto=update` or `create`. Never drop a table that holds history.

---

## 6. Start the backend

```powershell
cd backend
$env:JAVA_HOME   = "C:\Program Files\Java\jdk-21.0.12.1"
$env:Path        = "$env:JAVA_HOME\bin;$env:Path"
$env:JWT_SECRET  = "paste-the-64-hex-characters-here"

.\mvnw.cmd spring-boot:run
```

The database variables from section 4 must be set in the same terminal (or in the IntelliJ run configuration).
`server.port` defaults to `8080`; set the `PORT` environment variable to bind elsewhere (Render does this automatically).
No profile is active unless you select one. The untracked `.env` file sets `SPRING_PROFILES_ACTIVE=dev` for local work;
production must set a non-dev value such as `prod` to keep the development bootstrap and reset beans disabled.

Expected startup sequence:

```
Flyway Community Edition ... Migrating schema "public" to version "1 - init schema"
Flyway Community Edition ... Migrating schema "public" to version "2 - employee manager assignments"
Flyway Community Edition ... Migrating schema "public" to version "3 - case insensitive employee code uniqueness"
Flyway Community Edition ... Migrating schema "public" to version "4 - kpi type classification"
Flyway Community Edition ... Migrating schema "public" to version "5 - hr kpi dual rating"
Successfully applied 5 migrations
... Hibernate: (no DDL statements — validation only)
Tomcat started on port 8080
```

You should **not** see a `Using generated security password` line: there is no default in-memory user.

Check it is alive:

```powershell
Invoke-WebRequest http://localhost:8080/ -UseBasicParsing
```

A JSON `401` is the expected answer for `/`: it is protected and no token was sent. `POST /api/auth/login` is the
public endpoint, but a fresh database has no accounts until you start with the `dev` profile and configure the HR
bootstrap (section 11). A `404` is also fine for unknown paths. What matters is that the process started without a
schema-validation or JWT-secret error.

### Starting with the `dev` profile

The dev profile enables the HR bootstrap and the development password-reset token. Start it from `backend/`:

```powershell
$env:EVALORA_BOOTSTRAP_HR_EMAIL    = "hr@your-domain.local"
$env:EVALORA_BOOTSTRAP_HR_PASSWORD = "choose-a-policy-compliant-password"

.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

With the packaged jar instead:

```powershell
java -jar target\backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

If both bootstrap variables are absent, the application still starts and logs a warning telling you which variables
to set — it never invents a default account.

### If you prefer a jar

```powershell
cd backend
.\mvnw.cmd -DskipTests package
java -jar target\backend-0.0.1-SNAPSHOT.jar
```

Run the jar from `backend/` so the relative Flyway path resolves. From the project root, set `MIGRATIONS_LOCATION`
first (section 5, option C), and keep `JWT_SECRET` set.

---

## 7. Run the tests

```powershell
cd backend
.\mvnw.cmd test
```

- 543 tests, **no PostgreSQL needed**. The suite covers persistence, security, auth, password management, the
  HR/bootstrap/concurrency scenarios, KPI and cycle management, employee/manager/HR assessments, scoring,
  result generation and publication, dashboards, and the deployment-configuration checks.
- Deployment-configuration coverage: the CORS allow-list keeps the localhost default, accepts configured origins,
  and rejects wildcards or an empty list; the dev-profile tests prove the seeder and dev-only reset bean are absent
  outside `dev`.
- Security coverage: BCrypt strength 12, JWT generation/validation/expiry/signature, 401 and 403 responses, CORS,
  disabled and locked accounts, role resolution from the database rather than the token, login success/failure
  responses, email normalisation, failed-attempt counting, the five-attempt/five-minute lock, `/api/auth/me`,
  stateless logout, the password policy, SHA-256 reset-token storage with 15-minute expiry, one-time and
  other-token invalidation, dev-only token exposure, and concurrent double-use of a reset token.
- Phase 3B4 coverage: HR-only authorization (401/403/200), employee and manager creation, case-insensitive
  email/code uniqueness, password-policy rejection, BCrypt storage, transactional rollback of a failed creation,
  listings and `status` filter, wrong-role 404, profile updates that ignore role/status/security fields, manager
  assignment and reassignment with append-only history, inactive/self/HR targets rejected, employee
  deactivate/reactivate with login checks, manager deactivation blocked with a 409 while reports remain assigned,
  reactivation without replaying assignments, creation and manager deactivation serialising on the same manager row
  lock, case-insensitive employee-code uniqueness (service pre-check plus the V3 duplicate guard), and the dev-only
  idempotent HR bootstrap.
- Phase 3C coverage: HR-only KPI create/list/get/update/deactivate/reactivate, name trimming and length, optional
  description/category, BigDecimal weightage bounds and scale, case-insensitive duplicate rejection, creator taken
  from the authenticated principal, ignored `id`/`active`/`createdBy` fields, newest-first listing with an optional
  `active` filter, 404 for unknown ids, repeated state-change conflicts, 401/403 for non-HR callers, database
  uniqueness failures mapped to 409, and two concurrent duplicate creations yielding exactly one KPI.
- Phase 3D coverage: cycle create/list/get/update, name trimming and case-insensitive duplicate rejection, date and
  deadline ordering, protected `id`/`status`/creator/published/completed fields ignored, DRAFT-only updates, KPI
  full replacement with average or weighted mode, weight bounds/scale/total rules, inactive/unknown/duplicate KPI
  rejection, employee roster replacement with active EMPLOYEE and MANAGER validation, current reporting-manager
  fallback, explicit-manager snapshot without rewriting history, publish validation, assignment and KPI-snapshot
  creation, double-publish rejection, published-cycle immutability, snapshot immutability after master KPI edits,
  transactional rollback on snapshot failure, concurrent double publish yielding one winner, manager deactivation
  racing publication, and 401/403 for non-HR callers.
- Phase 3E coverage: exactly-`PUBLISHED` listing for the authenticated employee only (`IN_PROGRESS`/`COMPLETED`/`DRAFT`
  all hidden on every endpoint), frozen KPI snapshots, draft save/update/retrieve including partial and empty drafts,
  integer-only 1-5 rating validation, duplicate and foreign-KPI rejection, validate-before-write atomicity, submission
  completeness and timestamp, one-way `SELF_ASSESSMENT_PENDING` -> `MANAGER_REVIEW_PENDING` transition, double-submit
  and post-submit edit rejection, snapshot stability after master-KPI edits, untouched manager/HR assessment tables,
  concurrent double submit yielding one winner, concurrent draft saves keeping one row per KPI, two latch-based tests
  proving that a write blocks while another transaction holds the assignment row lock and that a committed submission
  wins over a waiting draft, and 401/403 for HR, managers and anonymous callers.
- Phase 3F coverage: manager-only access, assignment-snapshot ownership despite current-manager changes, exactly-
  `PUBLISHED` visibility after employee submission, frozen KPI and self-assessment detail, partial/empty/repeated
  drafts, rating and KPI-id validation, validate-before-write behavior, immutable HR-review submission, untouched
  employee/HR/result data, concurrent submit single-winner behavior, concurrent upserts, a latch-coordinated draft
  blocked behind submission, and two deterministic proofs that draft writes and submissions wait on the assignment
  row lock.
- Phase 3G coverage: HR-only access (401 for anonymous, 403 for EMPLOYEE and MANAGER), review of every eligible
  employee, queue containing only `HR_REVIEW_PENDING` assignments in exactly-`PUBLISHED` cycles with both employee
  and manager submissions, exclusion of DRAFT/other-status cycles, manager-pending and already-submitted rows,
  three-perspective detail with frozen KPI snapshots, partial/empty/repeated drafts, integer-only 1-5 validation,
  duplicate and foreign KPI rejection, validate-before-write, blank-comment normalisation, draft leaving status and
  timestamps unchanged, completeness-checked submission, the one-way `HR_REVIEW_PENDING` -> `COMPLETED` transition
  with `hr_finalized_at`, double-submit and post-submit edit rejection, untouched self/manager assessment data,
  concurrent submit single-winner behaviour, concurrent draft upserts, and deterministic latch-based proofs that
  draft writes and submissions wait on the assignment row lock. These tests use H2 in PostgreSQL mode;
  PostgreSQL-specific runtime locking is not independently verified here.
- Phase 3H coverage: pure `BigDecimal` KPI scoring (4.25, 3.25, 1.00, 5.00), weighted and average overall scores,
  the 3.95 and 3.92 documented examples, repeating-decimal and HALF_UP rounding, grade thresholds, invalid
  weight combinations (partial, total not 100, zero, negative, over 100, empty), missing manager/HR ratings,
  one result header plus one item per KPI, frozen snapshot and master-KPI immutability, self-rating contributing
  0 %, unpublished DRAFT persistence, existing-result and double-submit conflicts without duplicates, atomic
  rollback of the header/assignment when item persistence fails, and concurrent submissions creating exactly one
  header and one item per KPI. These tests use H2 in PostgreSQL mode; PostgreSQL-specific constraints and row
  locks are not independently verified here.
- Phase 3I coverage: HR-only individual and cycle-wide result publication, eligible-only publication with ineligible
  assignments skipped and missing results left uncreated, repeated publication, result/assignment status and
  equal publication timestamps, immutable scores/items/assessment snapshots, publication rollback, employee ownership,
  manager historical-team access from the assignment snapshot, hidden and inconsistent publication states, and a
  latch-coordinated race between individual and bulk publication. These tests use H2 in PostgreSQL mode;
  PostgreSQL-specific locking is not independently verified.
- They use an in-memory H2 database in `MODE=PostgreSQL` with `DATABASE_TO_LOWER=TRUE`, defined in
  `backend/src/test/resources/application.properties`. A test-only JWT secret lives there too; it is never used by
  a running application.
- Flyway is disabled in tests (`spring.flyway.enabled=false`) because H2 cannot execute the PostgreSQL-specific
  parts of the migrations (functional and partial indexes). The test schema is generated by Hibernate from the
  entities, which is exactly what makes these tests a useful check on the mappings. The `V2` partial unique index is
  therefore not exercised by H2; the service's row lock and close-then-insert order enforce the same rule.
- Tests never read `.env`, never touch your real database, and never connect to the network.

To run one class:

```powershell
.\mvnw.cmd test -Dtest=PerformanceWorkflowPersistenceTest
```

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Schema-validation: missing table [users]` | Flyway did not run | Check the backend was started from `backend/`, or set `MIGRATIONS_LOCATION` |
| `Schema-validation: wrong column type ...` | Migration and entity disagree | Fix the entity/migration in a new `V2` file — do not edit `V1` |
| `Unable to obtain connection ... password authentication failed` | Wrong `DB_PASSWORD` | Re-set the variable and restart the terminal |
| `FATAL: database "evalora" does not exist` | Database not created | Re-run section 3 |
| `release version 17 not supported` | JDK 17 is active | Set `JAVA_HOME` to JDK 21 (section 2) |
| `Port 8080 was already in use` | Another process | Stop it, or set `server.port` temporarily |
| Flyway cannot resolve the migration location | Started from the project root, where `../database/migrations` points above the repo | Run from `backend/`, or set `MIGRATIONS_LOCATION` to the absolute folder (section 5) |
| `JWT secret is not configured` at startup | `JWT_SECRET` is not set in this terminal | Generate one with `openssl rand -hex 32` and set it (section 4) |
| `JWT_SECRET is too short (n bytes)` at startup | The value is under 32 bytes | Generate a 64-hex-character secret (section 4) |
| Protected paths return JSON `401 UNAUTHORIZED` | No token, or an invalid/expired token | Send `Authorization: Bearer <token>`; only `POST /api/auth/login` is public |
| Enum column validation error | New enum field without the VARCHAR mapping | Add `@JdbcTypeCode(SqlTypes.VARCHAR)` — see `DATABASE_DESIGN.md` §17 |

---

## 9. Email

Email is **disabled** and no email dependency exists in the project.

- No SMTP configuration.
- No provider SDK (Brevo, SendGrid, Mailgun, Gmail, ...).
- No welcome, reset or notification messages.
- Creating an account writes to the database only.

Password resets never send email. In local development the `dev` profile returns the raw reset token in the
forgot-password response instead (section 10); outside `dev` the token is only stored as a hash and there is
currently no delivery mechanism. This is covered in `EMAIL_INTEGRATION.md` when that document is added in a later
phase.

---

## 10. Security foundation (Phase 3B2–3B3)

The security layer is active as soon as the application starts:

- Stateless JWT authentication (`Authorization: Bearer <token>`, HS256, 8-hour expiry).
- BCrypt strength 12 for password hashing and verification.
- CORS allows the local Vite origin by default. Set `EVALORA_CORS_ALLOWED_ORIGINS` to a comma-separated list of
  deployed frontend origins in production; wildcards are rejected.
- Every endpoint requires authentication except `POST /api/auth/login`, `POST /api/auth/forgot-password`,
  `POST /api/auth/reset-password`, Swagger/OpenAPI paths, `/error` and CORS preflight.
- Role and account status are always read from the database, never from the token.
- `/api/hr/**` requires `ROLE_HR`; every other protected path still requires only authentication.
- Login failures are tracked: five wrong passwords lock the account for five minutes (`423 ACCOUNT_LOCKED`).
- `GET /api/auth/me` returns the caller's id, email and current database role.
- `POST /api/auth/logout` is stateless: it returns a confirmation and the client discards its token. There is no
  session, blacklist or revocation table.

Example login once an account exists:

```powershell
$body = '{"email":"employee@evalora.local","password":"<password>"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType "application/json" -Body $body
```

The response contains `token`, `tokenType`, `expiresIn` and a `user` object. Use it as
`-Headers @{ Authorization = "Bearer $token" }` for `/api/auth/me` and every other protected endpoint.

### Password management (Phase 3B3)

| Endpoint | Auth | Purpose |
|---|---|---|
| `PUT /api/auth/change-password` | Bearer token | `currentPassword`, `newPassword`, `confirmPassword` |
| `POST /api/auth/forgot-password` | public | `email`; always the same generic 200 response |
| `POST /api/auth/reset-password` | public | `token`, `newPassword`, `confirmPassword` |

Password rules: at least 8 characters, at least one letter, one number and one special character. Changing or
resetting a password does **not** revoke JWTs that were already issued — those stay valid until they expire.

To complete a reset locally, start the backend with the `dev` profile so forgot-password returns the raw token:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

```powershell
$forgot = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/forgot-password `
  -ContentType "application/json" -Body '{"email":"employee@evalora.local"}'
$forgot.resetToken   # only present in the dev profile

$reset = '{"token":"' + $forgot.resetToken + '","newPassword":"NewEmployee@2026","confirmPassword":"NewEmployee@2026"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/reset-password `
  -ContentType "application/json" -Body $reset
```

The token is valid for 15 minutes and can be used once; issuing a new one invalidates the previous one. Only its
SHA-256 hash is stored, and the raw value is never logged. Without the `dev` profile the response has no token field
at all, and no email is sent.

No account exists on a fresh database until the dev HR bootstrap in section 11 creates one. The full design, error
format, claims and test coverage are in `docs/SECURITY_DESIGN.md`.

---

## 11. HR bootstrap and staff management (Phase 3B4)

### Development-only HR account

HR is the only role that can manage staff, and there is no public registration, no HR-creation endpoint and no
`ADMIN` role. On a fresh local database the first HR account comes from a `dev`-only seeder:

1. Set `EVALORA_BOOTSTRAP_HR_EMAIL` and `EVALORA_BOOTSTRAP_HR_PASSWORD` in the same terminal (section 4).
2. Start the backend with `--spring.profiles.active=dev` (section 6).
3. The seeder creates one `User` with role `HR` and its `Employee` profile in a single transaction.

Behaviour:

- **No defaults.** If both variables are missing the application still starts and logs a warning naming the two
  variables; no account is created.
- **Half-configured fails loudly.** Supplying only one of the two aborts startup with a message naming the missing
  variable, rather than creating an incomplete account.
- The password must satisfy the Phase 3B3 policy and is stored as a BCrypt strength-12 hash. It is never logged.
- The email is normalised with `EmailNormalizer` before saving.
- **Idempotent.** If an HR account already exists the seeder does nothing, so repeated restarts never duplicate it.
- **`dev` only.** Outside `dev` the seeder bean does not exist, so production cannot create an account this way.
- Optional profile values: `EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE` (default `HR-001`) and
  `EVALORA_BOOTSTRAP_HR_FULL_NAME` (default `HR Administrator`).

Log in as that HR user, then use the token for every `/api/hr/**` call:

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType "application/json" `
  -Body '{"email":"<the address you configured>","password":"<the password you configured>"}'
$headers = @{ Authorization = "Bearer $($login.token)" }
```

### HR-only endpoints

All of these require `ROLE_HR`; unauthenticated callers get 401, and EMPLOYEE/MANAGER callers get 403.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/employees` | Create an `EMPLOYEE` account + profile with a required reporting manager |
| `POST` | `/api/hr/managers` | Create a `MANAGER` account + profile |
| `GET` | `/api/hr/employees` | List employees; `?status=ACTIVE` (default) or `INACTIVE` |
| `GET` | `/api/hr/employees/{id}` | One employee; 404 if missing or not an employee |
| `GET` | `/api/hr/managers` | List managers; same `status` filter |
| `GET` | `/api/hr/managers/{id}` | One manager; 404 if missing or not a manager |
| `PUT` | `/api/hr/employees/{id}` | Update profile fields |
| `PUT` | `/api/hr/managers/{id}` | Update profile fields |
| `PUT` | `/api/hr/employees/{id}/manager` | Assign or reassign the reporting manager |
| `PATCH` | `/api/hr/employees/{id}/deactivate` | Soft-deactivate and disable login |
| `PATCH` | `/api/hr/employees/{id}/reactivate` | Re-enable the account |
| `PATCH` | `/api/hr/managers/{id}/deactivate` | Soft-deactivate; 409 while reports remain |
| `PATCH` | `/api/hr/managers/{id}/reactivate` | Re-enable the account |

### Creating accounts

The role is chosen by the endpoint, never by the request body — `POST /api/hr/employees` always creates an
`EMPLOYEE` and `POST /api/hr/managers` always creates a `MANAGER`. A create request carries first name, last name,
email, employee code, department, designation, joining date, initial password and (employees only) a required
`managerId`.

- Email is normalised and must be unique case-insensitively; employee code is unique too. Either conflict is 409.
- The initial password is policy-checked, then BCrypt-hashed. A weak password is 400.
- The `User`, its `Employee` profile and any initial manager assignment are written in one transaction; if anything
  fails, nothing is left behind.
- A manager request that includes `managerId` is rejected: managers have no manager in v1.
- An employee must select an existing, enabled, active `MANAGER`; missing or null manager ids are rejected. The
  employee's reporting relationship is saved in `employees.manager_id` and the append-only assignment history.
- If no eligible manager is available, create or activate a manager first. HR and manager account creation do not
  require a reporting manager.
- Creating an employee does not assign the employee to a performance cycle. Cycle assignment remains a separate HR
  action.
- This is a creation-time rule. Existing employee records are preserved without backfill or reassignment.

Profile updates accept the same profile fields (including email) but never role, status, password, enabled flag,
lockout state or manager. Unknown JSON fields are ignored. Role cannot be changed by an update, and no historical
assessment, result or reporting row is touched.

### Manager assignment and history

`PUT /api/hr/employees/{id}/manager` with `{"managerId": 42}` validates that the employee exists, is an `EMPLOYEE`
and is active; that the manager exists, is a `MANAGER` and is active; and that the two ids differ. HR and manager
accounts cannot be assigned as employees.

Reporting history is append-only (`employee_manager_assignments`, added in V2). A reassignment closes the current
row by setting `effective_to` and inserts a new open row; the previous row is never overwritten or deleted.
`employees.manager_id` is kept in sync as the fast current-manager pointer. Only one open row per employee is
allowed.

### Deactivation and reactivation

- **Employee deactivate:** profile becomes `INACTIVE`, the user account is disabled (so login fails on the next
  request), the open reporting row is closed and `manager_id` cleared. Assessments and results are untouched.
- **Employee reactivate:** profile becomes `ACTIVE` and the account is enabled. The old manager assignment is **not**
  restored — assign a manager separately.
- **Manager deactivate:** returns **409** while any active employee still reports to that manager, with a message
  saying those employees must be reassigned first. Once they are, deactivation succeeds.
- **Manager reactivate:** re-enables the profile and account and does not recreate any employee assignments.
- Reactivation does not clear `failed_login_attempts` or `locked_until`.
- Nothing is ever hard-deleted, and repeating a deactivate/reactivate returns 409.

### Error summary

| Status | When |
|---|---|
| 201 | Account created |
| 200 | Read, update, assignment, state change |
| 400 | Invalid body, weak password, invalid manager role, self-assignment, unknown field value |
| 401 | Missing or invalid token |
| 403 | Caller is not HR |
| 404 | Unknown id, or the id belongs to the other role |
| 409 | Duplicate email/code, inactive target, manager with active reports, repeated state change |

Errors use the same JSON shape as the rest of the API and never expose stack traces, SQL or hashes.

---

## 12. KPI management (Phase 3C)

HR maintains the reusable KPI catalogue used by future performance cycles. Only `ROLE_HR` may call these endpoints;
EMPLOYEE and MANAGER callers get 403 and unauthenticated callers get 401. No schema change was needed — the existing
`kpi_master` table and its `LOWER(name)` unique index are reused.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/kpis` | Create a KPI (always active) |
| `GET` | `/api/hr/kpis` | List KPIs, newest first; optional `?active=true\|false` |
| `GET` | `/api/hr/kpis/{id}` | One KPI |
| `PUT` | `/api/hr/kpis/{id}` | Update the editable catalogue fields |
| `PATCH` | `/api/hr/kpis/{id}/deactivate` | Soft-deactivate (sets `active = false`) |
| `PATCH` | `/api/hr/kpis/{id}/reactivate` | Set `active = true` |

### Create and update

```powershell
$headers = @{ Authorization = "Bearer $($login.token)" }

$kpi = '{"name":"Delivery Quality","description":"Meets agreed deadlines.","category":"Delivery","defaultWeightage":25.00}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/hr/kpis `
  -Headers $headers -ContentType "application/json" -Body $kpi
```

The create response is HTTP 201; list, get, update and state changes return 200:

```json
{
  "id": 1,
  "name": "Delivery Quality",
  "description": "Meets agreed deadlines.",
  "category": "Delivery",
  "defaultWeightage": 25.00,
  "active": true,
  "createdById": 1,
  "createdAt": "2026-10-31T10:00:00Z",
  "updatedAt": "2026-10-31T10:00:00Z"
}
```

`PUT` accepts the same `name`, `description`, `category` and `defaultWeightage` fields; omitted optional fields clear
the stored value. `active`, `createdBy`, `id` and audit fields are never read from the body, so a client cannot change
state or ownership through create/update.

### Validation rules

| Field | Rule |
|---|---|
| `name` | Required, trimmed, not blank, at most 150 characters, unique ignoring case → 409 on duplicate |
| `description` | Optional; trimmed; empty becomes `null`; the column is `TEXT` (no application width limit) |
| `category` | Optional; trimmed; at most 80 characters |
| `defaultWeightage` | Optional BigDecimal; strictly greater than 0 and at most 100; at most 2 decimal places |
| `active` | Not writable; new KPIs are active |
| `createdBy` | Not writable; taken from the authenticated HR principal |

### Listing and deactivation

- No filter returns every KPI, active and inactive, ordered by id descending (newest first). There is no pagination in
  this phase.
- `PATCH .../deactivate` only flips `active`; the row is never deleted and its name, description, category, weight,
  creator and timestamps survive. `PATCH .../reactivate` flips it back.
- Repeating the same state change returns 409 (`KPI is already inactive.` / `KPI is already active.`).
- Updates and deactivation never touch `cycle_kpis`, `employee_kpis` or any assessment/result snapshot; those copies are
  frozen at publish time.
- The service checks name uniqueness first, and the database functional unique index is the final guard. A concurrent
  duplicate insert is translated to 409 rather than a raw database error.

### Error summary

| Status | When |
|---|---|
| 201 | KPI created |
| 200 | Read, update, deactivate, reactivate |
| 400 | Blank/oversized name, category over 80, invalid weightage, malformed body or `active` filter |
| 401 | Missing or invalid token |
| 403 | Caller is not HR |
| 404 | Unknown KPI id |
| 409 | Duplicate name (including case differences), repeated state change, database uniqueness race |

These KPI endpoints do not create performance cycles, assign KPIs to employees, collect assessments or calculate scores
— those belong to later phases.

---

## 13. Performance cycle management (Phase 3D)

HR creates a performance cycle, chooses KPIs and their weights, assigns employees and reviewing managers, then
publishes the cycle. Every endpoint requires `ROLE_HR`. **No schema change was needed:** Phase 3D reuses the V1
`performance_cycles`, `cycle_kpis`, `employee_assignments` and `employee_kpis` tables and the V1/V2 constraints.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/hr/cycles` | Create a DRAFT cycle |
| `GET` | `/api/hr/cycles` | List cycles, newest first |
| `GET` | `/api/hr/cycles/{id}` | One cycle |
| `PUT` | `/api/hr/cycles/{id}` | Update a DRAFT cycle |
| `POST` | `/api/hr/cycles/{id}/kpis` | Replace the cycle's KPI selection and weights |
| `GET` | `/api/hr/cycles/{id}/kpis` | View the configured KPIs |
| `PUT` | `/api/hr/cycles/{id}/employees` | Replace the DRAFT employee roster |
| `GET` | `/api/hr/cycles/{id}/employees` | View the roster and reviewing managers |
| `POST` | `/api/hr/cycles/{id}/publish` | Validate, snapshot and publish |

### Create and update

```powershell
$cycle = @'
{
  "name": "Annual 2026",
  "description": "Annual performance review.",
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "selfAssessmentDeadline": "2026-11-30",
  "managerReviewDeadline": "2026-12-15",
  "hrReviewDeadline": "2026-12-31"
}
'@
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/hr/cycles `
  -Headers $headers -ContentType "application/json" -Body $cycle
```

The response is HTTP 201 and includes the generated `id`, `status: "DRAFT"`, `createdById`, audit timestamps and null
`publishedAt`/`completedAt`. `PUT` accepts the same editable fields and is allowed only while the cycle is `DRAFT`.
`id`, `status`, `createdBy`, `publishedAt`, `completedAt` and audit timestamps are never read from the request body.

| Field | Rule |
|---|---|
| `name` | Required, trimmed, not blank, at most 150 characters, unique ignoring case → 409 on duplicate |
| `description` | Optional, trimmed, empty becomes `null` |
| `startDate` / `endDate` | Required; start must be before end |
| `selfAssessmentDeadline` | Required; must be between the start and end dates, inclusive |
| `managerReviewDeadline` | Required; on or after the self-assessment deadline |
| `hrReviewDeadline` | Required; on or after the manager review deadline |

### KPI selection and weights

`POST /api/hr/cycles/{id}/kpis` **fully replaces** the DRAFT cycle's selection; an empty list clears it. Validation
happens before any row is deleted or inserted, so a rejected request leaves the previous configuration untouched.

```json
{
  "kpis": [
    { "kpiId": 1, "weightage": 60.00 },
    { "kpiId": 2, "weightage": 40.00 }
  ]
}
```

- Only active KPIs from `kpi_master` may be selected; unknown ids return 404 and inactive KPIs return 400.
- A KPI may appear only once per cycle.
- Use `null` for every weight to select **average mode** (the stored `cycle_kpis.weightage` is null).
- Otherwise every weight must be non-null, `0 < w <= 100`, with at most 2 decimal places, and the total must be
  exactly `100.00`. Partial configuration is rejected.
- Weights use `BigDecimal` and are stored with scale 2.
- Configuring a cycle weight never changes `kpi_master.default_weightage`.
- KPIs, employees and the cycle itself are immutable after publication. Later changes to `kpi_master` never rewrite
  an existing `employee_kpis` snapshot.

### Employee roster and reviewing managers

`PUT /api/hr/cycles/{id}/employees` **fully replaces** the DRAFT roster:

```json
{
  "employees": [
    { "employeeId": 10, "managerId": 4 },
    { "employeeId": 11 }
  ]
}
```

- Only active `EMPLOYEE` profiles may be appraised; active `MANAGER` profiles may review. HR and MANAGER accounts
  cannot be appraised, and nobody can review themselves.
- Each employee may appear once per cycle.
- When `managerId` is omitted, the employee's current reporting manager is used (the open
  `employee_manager_assignments` row, falling back to `employees.manager_id`).
- An explicit `managerId` sets **only the reviewing-manager snapshot of this cycle**. It does not change the
  employee's current reporting line or the append-only `employee_manager_assignments` history.
- Employee and manager rows are locked while the roster is validated, in ascending id order, so a concurrent
  reassignment or deactivation cannot slip in unnoticed.
- The draft roster is stored in `employee_assignments` with status `SELF_ASSESSMENT_PENDING`; publication reuses
  those rows and freezes their manager as the reviewing-manager snapshot.

### Publishing

`POST /api/hr/cycles/{id}/publish` runs in one transaction and requires:

1. An existing DRAFT cycle.
2. At least one configured KPI, with a valid average or weighted configuration.
3. At least one employee in the roster.
4. Every appraised employee still active with the `EMPLOYEE` role.
5. Every reviewing manager still active with the `MANAGER` role.
6. No duplicate assignment and no self-review.
7. Every selected master KPI still active.

It then copies each cycle KPI into `employee_kpis` for every assignment (`kpi_name`, `kpi_description`,
`kpi_category`, `weightage`, plus the original `kpi_id` for traceability), sets the cycle to `PUBLISHED` and sets
`publishedAt`. It never sets `completedAt`. The operation rolls back entirely if any snapshot write fails. A second
publish returns 409, and no KPI/employee/cycle update is accepted after publication.

Deadlines remain **informational**. Nothing in this phase blocks an action because a date has passed.

### Error summary

| Status | When |
|---|---|
| 201 | Cycle created |
| 200 | Read, update, KPI/employee configuration, publish |
| 400 | Missing/blank/oversized name, invalid dates or deadlines, invalid KPI/employee/manager choice, invalid weights |
| 401 | Missing or invalid token |
| 403 | Caller is not HR |
| 404 | Unknown cycle, KPI, employee or manager id |
| 409 | Duplicate cycle name, non-DRAFT mutation, inactive staff or KPI, duplicate assignment, database conflict |

This phase does not implement self-assessment, manager/HR assessment, scoring, results or performance history.

---

## 14. Employee self-assessment (Phase 3E)

An assigned employee sees their published cycles, rates each frozen KPI, saves a draft as often as they like, then
submits once. Every endpoint requires `ROLE_EMPLOYEE`, and the employee is always resolved from the authenticated
principal - no endpoint accepts an employee id. **No schema change was needed:** Phase 3E uses the V1
`self_assessments` table and the existing `employee_assignments` workflow columns.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/employee/self-assessments/cycles` | The employee's `PUBLISHED` assignments |
| `GET` | `/api/employee/self-assessments/{assignmentId}` | Cycle header, frozen KPIs and saved draft |
| `PUT` | `/api/employee/self-assessments/{assignmentId}/draft` | Create or update the draft |
| `POST` | `/api/employee/self-assessments/{assignmentId}/submit` | Submit the completed self-assessment |
| `GET` | `/api/employee/self-assessments/{assignmentId}/status` | Compact draft/submission status |

`assignmentId` is the `employee_assignments.id` returned by the listing, **not** a cycle id or employee id.
An assignment that belongs to another employee, does not exist, or whose cycle is not exactly `PUBLISHED` returns the
same `404 Self-assessment not found.` so the API never reveals that another employee's assignment exists. A `DRAFT`
cycle is of course hidden, and so is any later status (`IN_PROGRESS`, `COMPLETED`, or a status added in a future
phase) because this phase defines no employee workflow for them.

### Listing and detail

```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/employee/self-assessments/cycles -Headers $headers
```

```json
[
  {
    "assignmentId": 12,
    "cycleId": 3,
    "cycleName": "Annual 2026",
    "description": "Annual performance review.",
    "startDate": "2026-01-01",
    "endDate": "2026-12-31",
    "selfAssessmentDeadline": "2026-11-30",
    "managerReviewDeadline": "2026-12-15",
    "hrReviewDeadline": "2026-12-31",
    "cycleStatus": "PUBLISHED",
    "publishedAt": "2026-10-31T16:20:11.123456Z",
    "assignmentStatus": "SELF_ASSESSMENT_PENDING",
    "selfSubmittedAt": null
  }
]
```

`GET /{assignmentId}` returns the same header plus every frozen KPI snapshot and the saved rating/comment:

```json
{
  "assignmentId": 12,
  "cycleId": 3,
  "cycleName": "Annual 2026",
  "cycleStatus": "PUBLISHED",
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "selfAssessmentDeadline": "2026-11-30",
  "assignmentStatus": "SELF_ASSESSMENT_PENDING",
  "selfSubmittedAt": null,
  "editable": true,
  "kpis": [
    {
      "employeeKpiId": 40,
      "kpiId": 1,
      "name": "Delivery Quality",
      "description": "Meets agreed deadlines.",
      "category": "Delivery",
      "weightage": 60.00,
      "rating": 4,
      "comment": "Delivered every milestone on time."
    }
  ]
}
```

KPI name, description, category and weightage come from `employee_kpis` (the Phase 3D snapshot), never from
`kpi_master`, so later catalogue edits cannot rewrite what the employee is assessing. Weightage is **informational
only**: the self-rating contributes 0 % to the final score and no score is computed in this phase.

### Rating scale

| Rating | Meaning |
|---|---|
| `1` | Lowest |
| `2` | |
| `3` | |
| `4` | |
| `5` | Highest |

Ratings are whole numbers from 1 to 5. Decimal values such as `3.5` are rejected with 400 rather than truncated.
Comments are optional, trimmed, and blank comments become `null`.

### Saving a draft

```powershell
$body = @'
{
  "kpis": [
    { "employeeKpiId": 40, "rating": 4, "comment": "Delivered every milestone." },
    { "employeeKpiId": 41, "rating": 5 }
  ]
}
'@
Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/employee/self-assessments/12/draft `
  -Headers $headers -ContentType "application/json" -Body $body
```

- A draft may rate some or all assigned KPIs, or send `{"kpis": []}` for an empty no-op draft.
- Each entry **upserts** the row for that `employeeKpiId`; KPIs omitted from the payload keep their saved values.
  Repeated saves therefore never create a second row - `uq_self_assessment` is the database backstop.
- `employeeKpiId` must belong to this assignment. Ids from another assignment/KPI are rejected with 400.
- The same `employeeKpiId` twice in one request is rejected with 400.
- The complete payload is validated before anything is written, so an invalid request never partially updates a
  saved draft.
- Saving a draft does not change `employee_assignments.status` and does not set `self_submitted_at`.
- Once submitted, a draft save returns `409 The self-assessment has already been submitted.`

### Submitting

`POST /api/employee/self-assessments/{assignmentId}/submit` takes **no body**; it submits the saved draft.

1. Ownership, visibility and `SELF_ASSESSMENT_PENDING` are re-checked while the assignment row is locked.
2. Every assigned KPI must have a saved rating from 1 to 5; otherwise `409 Every KPI must be rated from 1 to 5
   before submitting.`
3. The assignment moves to `MANAGER_REVIEW_PENDING` and `self_submitted_at` is set to the current UTC time.
4. The cycle, KPI snapshots, manager assessments and HR assessments are not touched.

The whole operation is one transaction. A second submit returns the same `409` and keeps the original timestamp.
After submission the employee can still read the assignment, but `editable` is `false` and the draft cannot change.
The manager/HR assessment tables remain empty - manager review is a later phase.

### Status

```json
{
  "assignmentId": 12,
  "cycleId": 3,
  "cycleStatus": "PUBLISHED",
  "assignmentStatus": "SELF_ASSESSMENT_PENDING",
  "submitted": false,
  "selfSubmittedAt": null,
  "totalKpis": 2,
  "ratedKpis": 1
}
```

### Deadlines and errors

The self-assessment deadline is informational. No draft save or submission is rejected because a date has passed.

| Status | When |
|---|---|
| 200 | Listing, detail, status, draft save, submit |
| 400 | Missing `kpis`, missing `employeeKpiId`, rating not an integer 1-5, duplicate or foreign `employeeKpiId` |
| 401 | Missing or invalid token |
| 403 | Caller is not an EMPLOYEE (HR and MANAGER included) |
| 404 | Unknown assignment, another employee's assignment, or a cycle that is not exactly `PUBLISHED` |
| 409 | Submission with missing ratings, repeated submission, draft edit after submission |

This phase does not implement manager assessment, HR assessment, scoring, results or performance history.

## 15. Manager assessment (Phase 3F)

The manager reviews an employee after the employee submits their self-assessment. The reviewer is resolved from the
authenticated principal, and ownership comes from the manager snapshot on that employee's cycle assignment. A later
change to the employee's current reporting manager does not change the reviewer for an existing cycle. Only
assignments in exactly `PUBLISHED` cycles with a submitted self-assessment are visible. No schema migration was
needed: the workflow reuses the V1 `manager_assessments` table, its unique `employee_kpi_id` constraint, and the
assignment's existing `manager_submitted_at` timestamp.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/manager/assessments` | List this manager's pending reviews |
| `GET` | `/api/manager/assessments/{assignmentId}` | Read employee, cycle, frozen KPI and assessment data |
| `PUT` | `/api/manager/assessments/{assignmentId}/draft` | Save or update manager ratings/comments |
| `POST` | `/api/manager/assessments/{assignmentId}/submit` | Submit the completed manager review |
| `GET` | `/api/manager/assessments/{assignmentId}/status` | Read KPI progress and submission status |

Every route requires `ROLE_MANAGER`. Unknown assignments, another manager's assignment, an unsubmitted employee
self-assessment, and a cycle that is not exactly `PUBLISHED` return the same 404. The review response exposes only
basic employee profile fields and includes the frozen KPI snapshot, submitted employee ratings/comments, and any
manager draft. It does not expose account credentials or other sensitive account data. Previously submitted reviews
remain readable while their cycle is published, but are not editable and do not appear in the pending queue.

### Draft ratings

```json
{
  "kpis": [
    { "employeeKpiId": 101, "rating": 4, "comment": "Good performance" }
  ]
}
```

Ratings must be whole numbers from 1 to 5; fractional values are rejected with 400. Comments are optional, trimmed,
and blank comments become `null`. KPI ids must belong to this assignment and cannot be duplicated in one request.
Validation completes before writes begin. Drafts may be partial or empty (`{"kpis": []}`); omitted KPIs retain
their earlier values. Repeated saves update existing rows, while a draft leaves assignment status and submission
timestamps unchanged. Client-supplied employee, manager, or assignment identity fields do not establish ownership.

### Submission and concurrency

`POST /api/manager/assessments/{assignmentId}/submit` takes no body. Every assigned KPI must have a saved rating;
otherwise the response is 409. A successful submission records `manager_submitted_at` and changes the assignment
from `MANAGER_REVIEW_PENDING` to `HR_REVIEW_PENDING` in one transaction. Duplicate submissions and later draft edits
return 409. The submission is immutable; there is no reopen or withdrawal operation.

Draft saves and submissions first take the same ownership-scoped pessimistic write lock on the assignment row. This
serializes writes: a draft waiting behind a committed submission receives 409, and concurrent submissions have one
winner. The existing unique constraint on `manager_assessments.employee_kpi_id` is the duplicate-row backstop. The
workflow stores only manager ratings and comments; it does not calculate scores, write HR assessments, or update
performance results. The 75% manager / 25% HR weights are not applied in this phase; employee self-ratings remain
reference-only at 0%.

The manager HTTP, data-integrity and concurrency integration tests cover role and ownership boundaries, current-manager
changes, published-cycle visibility, payload validation, immutability, exactly-one concurrent submission, concurrent
draft upserts, a draft blocked behind submission, and two deterministic latch-based proofs that both draft writes and
submissions wait on the assignment row lock. These tests use H2 in PostgreSQL mode; PostgreSQL-specific runtime
locking is not independently verified here. Deadlines are informational, and the queue is currently unpaginated.

---

## 16. HR assessment and final scoring (Phases 3G-3H)

HR performs the last review of an assignment after both the employee and the manager have submitted. Unlike the
employee and manager modules there is no ownership dimension: HR reviews every eligible employee, so the lookups are
role-scoped only and the caller is always the authenticated `EvaloraPrincipal`. An assignment is visible only while
its cycle is exactly `PUBLISHED` and both `self_submitted_at` and `manager_submitted_at` are set. A DRAFT cycle, any
other cycle status, a roster row still waiting for the employee or manager, an unknown id and a row that is no longer
in an HR-review status all return the same 404. No schema migration was needed: the workflow reuses the V1
`hr_assessments` table (including `uq_hr_assessment` on `employee_kpi_id` and `ck_hr_rating`), and the assignment's
existing `hr_finalized_at` timestamp. `COMPLETED` is the existing finalization status.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/hr/assessments` | List assignments waiting for HR review |
| `GET` | `/api/hr/assessments/{assignmentId}` | Read the three-perspective review screen |
| `PUT` | `/api/hr/assessments/{assignmentId}/draft` | Save or update HR ratings/comments |
| `POST` | `/api/hr/assessments/{assignmentId}/submit` | Submit the completed HR review |
| `GET` | `/api/hr/assessments/{assignmentId}/status` | Read KPI progress and submission status |

Every route inherits the existing `/api/hr/**` -> `ROLE_HR` rule; no security matcher was changed. The queue returns
only cycle-`PUBLISHED`, `HR_REVIEW_PENDING` assignments with both submissions present, ordered by manager submission
time descending (id descending as a tie-break). It exposes the employee's id, code, name, department and designation,
the cycle id and name, the manager submission timestamp and the assignment status - nothing about the employee's
account. Previously submitted HR reviews remain readable while the cycle is published, but are marked non-editable and
no longer appear in the queue.

### Three perspectives; scoring inputs

The detail response keeps the three opinions separate and never merges them:

1. **Employee self-assessment** - `selfRating` / `selfComment`, reference only (0%).
2. **Manager assessment** - `managerRating` / `managerComment` (75% of the final score).
3. **HR assessment** - `hrRating` / `hrComment` (25% of the final score).

Frozen `employee_kpis` snapshot fields (name, description, category, weightage) are returned for context. Phase 3G
draft review does not calculate scores or write results. Phase 3H applies the scoring rules at submission, using the
frozen snapshot weightages and the manager and HR ratings only; the response still contains no score or grade field.

### Draft ratings

```json
{
  "kpis": [
    {
      "employeeKpiId": 101,
      "rating": 4,
      "comment": "Consistently meets expectations"
    }
  ]
}
```

Ratings must be whole numbers from 1 to 5; fractions such as `3.5` are rejected with 400 rather than truncated.
Comments are optional, trimmed, and blank comments become `null`. Each `employeeKpiId` must belong to the requested
assignment, and the same KPI cannot appear twice in one request. The entire payload is validated before any write, so
a rejected request leaves the previous draft untouched. Drafts may be partial or empty (`{"kpis": []}`); omitted KPIs
keep their saved values and an entry with neither rating nor comment creates no row. Repeated saves update the
existing row per KPI - `hr_assessments.hr_id` follows the last HR editor, but rows are never duplicated. A draft save
never changes assignment status or any submission timestamp, and never touches `self_assessments` or
`manager_assessments`.

### Submission and concurrency

`POST /api/hr/assessments/{assignmentId}/submit` takes no body. Every assigned KPI must already have an HR rating from
1 to 5 and a manager rating from 1 to 5; otherwise the response is 409. The successful call records
`hr_finalized_at`, changes the assignment from `HR_REVIEW_PENDING` to `COMPLETED`, and generates the official result
header plus one item per KPI - all in one transaction. Any validation, calculation or persistence failure rolls the
whole operation back.

The result is computed with `BigDecimal` only:

- Final KPI score = `manager_rating * 0.75 + hr_rating * 0.25`. The self-rating contributes 0%.
- Weighted mode applies only when every frozen KPI weight is present and totals exactly 100.00:
  `sum(final KPI score * weightage) / 100`.
- Average mode applies only when every weight is `NULL`: `sum(final KPI scores) / number of KPIs`.
- The overall score is rounded once, `HALF_UP`, to exactly two decimals. Mixed, partial, zero, negative, over-100 or
  non-100 weights are rejected; there is no silent fallback.

The result is stored as `DRAFT` with `published_at` and `published_by` null. `COMPLETED` is therefore not result
publication: `results_published_at` stays `null`, and the result remains hidden from employee and manager endpoints.
A second submission or a later draft edit returns 409; there is no reopen or withdrawal operation. If a result already
exists while the assignment is still pending, the submission returns 409 and leaves that result untouched. Phase 3I
publication rules and employee/manager visibility are documented in
[API documentation](./API_DOCUMENTATION.md) and [Performance workflow](./PERFORMANCE_WORKFLOW.md).

Draft saves and submission both take the same pessimistic write lock on the assignment row before reading its status.
This serializes writes per assignment: a draft waiting behind a committed submission returns 409, concurrent
submissions produce exactly one success, and concurrent draft saves cannot create duplicate rows. The V1 unique
constraint on `hr_assessments.employee_kpi_id` is the database backstop.

The HR HTTP, data-integrity and concurrency integration tests cover role boundaries, queue eligibility and ordering,
published-cycle visibility, three-perspective detail, payload validation, draft semantics, submission and
immutability, untouched employee and manager assessments, exactly-one concurrent submission, concurrent draft
upserts, a draft blocked behind submission, and two deterministic latch-based proofs that both draft writes and
submissions wait on the assignment row lock. The Phase 3H suites add pure scoring tests and service-level result
tests: weighted and average modes, frozen snapshot use, invalid weights and missing ratings, one header plus one item
per KPI, self-rating exclusion, unpublished DRAFT persistence, duplicate handling, atomic rollback on item failure,
and one result under concurrency. These tests use H2 in PostgreSQL mode; PostgreSQL-specific runtime locking and
constraint behaviour are not independently verified here. Deadlines remain informational and the queue is
unpaginated.

For the detailed lifecycle, API contract, scoring/grade thresholds and test limitations, see
[Performance workflow](./PERFORMANCE_WORKFLOW.md), [API documentation](./API_DOCUMENTATION.md),
[Scoring logic](./SCORING_LOGIC.md) and [Testing](./TESTING.md).

## 17. Role dashboards (Phase 3J)

The backend exposes three read-only dashboard endpoints:

| Method | Path | Role |
|---|---|---|
| `GET` | `/api/hr/dashboard` | `HR` |
| `GET` | `/api/manager/dashboard` | `MANAGER` |
| `GET` | `/api/employee/dashboard` | `EMPLOYEE` |

Use the authenticated bearer token. No employee or manager identity is
accepted in the request. HR receives organization-wide active staff, cycle,
workflow and publication counts. Manager team size uses the current reporting
relationship, while cycle-specific workload uses the manager snapshot on each
assignment. Employees receive their newest assignment in a `PUBLISHED` cycle
and their latest result only when it is published and both publication
timestamps agree. Missing current-cycle or latest-result data is returned as
`null`. The dashboard counters are defined in
[API documentation](./API_DOCUMENTATION.md).

Dashboard data reuses existing tables and adds no migration or configuration.
Run its database-backed API tests with:

```powershell
.\mvnw.cmd test "-Dtest=DashboardIntegrationTest"
```
