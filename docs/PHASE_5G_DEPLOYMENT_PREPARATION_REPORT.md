# Evalora — Phase 5G Deployment Preparation Report

**Date:** 2026-11-05
**Workspace:** `C:\Users\DELL\OneDrive\Documents\PMS`
**Scope:** Correct the confirmed deployment blockers identified in `docs/GITHUB_PREPARATION_AUDIT.md`.

**Constraints honored:** No Git repository was initialized, nothing was committed or pushed, no Dockerfile/Jenkinsfile/Render
manifest was created, no business logic, API contract, scoring rule, or database migration was changed, and no real secret
value was read into this report or modified.

**Not performed:** Live PostgreSQL connectivity, Docker image build, Render deployment, or a real production-profile
startup. Those must be verified separately when the deployment assets exist.

---

## 1. Summary of corrections

| Area | Before | After |
|---|---|---|
| `.env` protection | `.env` and `*.env` ignored; `.env.production` etc. exposed | `.env`, `.env.*`, `*.env` ignored; `.env.example` explicitly re-included |
| CORS | Hardcoded `http://localhost:5173` constant | Configurable via `EVALORA_CORS_ALLOWED_ORIGINS`, localhost default, wildcards rejected |
| Spring profile | `spring.profiles.active=dev` hardcoded | No active profile in `application.properties`; local `.env` selects `dev`, production selects `prod` |
| Server port | Hardcoded `8080` | `server.port=${PORT:8080}` |
| Flyway location | Filesystem only, environment override syntax coupled to `filesystem:` | Local filesystem default preserved; migrations also packaged at `classpath:db/migration`; full location override supported |
| Stray generated file | `backend/META-INF/` present but unused | Inspected, confirmed unreferenced/generated, removed |
| Documentation | Stale migration/profile/CORS/test details | Setup guide corrected; deployment configuration contract added |

---

## 2. Changed files

### 2.1 `.gitignore` — **modified**

Added:

```gitignore
.env
.env.*
*.env
!.env.example
```

Why: `.env.production`, `.env.staging`, and similar files were not matched by the previous `*.env` pattern, so a future
environment file could have been committed by default. `!.env.example` keeps the placeholder template visible to Git.

The user's local `.env` was not read for values, modified, or deleted.

### 2.2 `.env.example` — **modified**

- Uncommented `SPRING_PROFILES_ACTIVE=dev` with a clear warning that production must use a non-dev value.
- Documented `EVALORA_CORS_ALLOWED_ORIGINS`.
- Corrected the `MIGRATIONS_LOCATION` examples to full Flyway location strings
  (`classpath:db/migration` and `filesystem:...`).

No real credentials were added; all values remain placeholders.

### 2.3 `backend/src/main/resources/application.properties` — **modified**

- `server.port=8080` → `server.port=${PORT:8080}`.
- Removed `spring.profiles.active=dev` and replaced it with an explanatory comment.
- `spring.flyway.locations` changed from `filesystem:${MIGRATIONS_LOCATION:../database/migrations}` to
  `${MIGRATIONS_LOCATION:filesystem:../database/migrations}` so the override can be either
  `classpath:db/migration` or `filesystem:/absolute/path`.
- Added `evalora.cors.allowed-origins=${EVALORA_CORS_ALLOWED_ORIGINS:http://localhost:5173}`.
- Updated the Flyway comment to describe the packaged copy.

The local default still resolves `../database/migrations` when the backend runs from `backend/`.

### 2.4 `backend/src/main/java/com/evalora/backend/security/CorsProperties.java` — **new**

Typed configuration properties for `evalora.cors.allowed-origins`:

- Defaults to `http://localhost:5173`.
- Binds a comma-separated list from Spring's relaxed binding.
- Keeps the default constant in one place (`DEFAULT_FRONTEND_ORIGIN`).

### 2.5 `backend/src/main/java/com/evalora/backend/security/SecurityConfig.java` — **modified**

- `corsConfigurationSource(CorsProperties)` now reads the configured allow-list instead of the compile-time constant.
- Trims entries, drops blanks, and **fails startup** when the list is empty or contains `*` / a wildcard pattern.
- Keeps `allowCredentials=false`; the JWT remains in the `Authorization` header.
- `FRONTEND_ORIGIN` is retained as an alias for the default origin so existing tests and documentation stay valid.

### 2.6 `backend/pom.xml` — **modified**

Added a build resource entry that copies `../database/migrations/*.sql` to `db/migration` in the build output and jar.
The existing `src/main/resources` entry is declared explicitly because defining `<resources>` replaces Maven's defaults.

Result: a container can set `MIGRATIONS_LOCATION=classpath:db/migration` and run self-contained, while local development
still uses the reviewable filesystem copy by default.

### 2.7 `backend/src/test/java/com/evalora/backend/security/SecurityCorsConfigurationTest.java` — **new**

Five tests covering the changed configuration behavior:

1. Default configuration allows only `http://localhost:5173`.
2. Configured origins replace the default and are trimmed.
3. `*` is rejected.
4. A wildcard subdomain pattern (`https://*.example.com`) is rejected.
5. An empty/blank list is rejected.

### 2.8 `backend/META-INF/` — **removed**

Inspected content:

```
org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration
```

Findings that confirmed it was unnecessary:

- Located at `backend/META-INF/`, **not** under `src/main/resources`, so it was never on the classpath.
- Not referenced by `pom.xml` or `application.properties`.
- Not present in `target/classes`.
- The `springdoc-openapi-starter-webmvc-ui` dependency already ships the same `META-INF/spring/...AutoConfiguration.imports`
  metadata inside its own jar.

It was removed as generated/unreferenced debris. No application behavior changed.

### 2.9 `docs/SETUP_GUIDE.md` — **modified**

- Migration list now includes `V5__hr_kpi_dual_rating.sql`.
- Migration location section describes the packaged `classpath:db/migration` copy and the corrected
  `MIGRATIONS_LOCATION` syntax.
- Startup section documents `PORT`, the removed hardcoded profile, and the local `.env` / production `prod` split.
- Startup sequence now shows five migrations.
- Test count updated to **543** and deployment-configuration coverage described.
- CORS description updated to the configurable allow-list.

### 2.10 `frontend/README.md` — **modified**

Replaced the "CORS only from localhost:5173" note with the configurable `EVALORA_CORS_ALLOWED_ORIGINS` behavior.

### 2.11 `docs/DEPLOYMENT_CONFIGURATION.md` — **new**

Documents the deployment contract without secrets: required/optional environment variables, profile rules, CORS, port
binding, Flyway packaging, and Docker/Jenkins/Render requirements. It intentionally does not create those assets.

### 2.12 `docs/PHASE_5G_DEPLOYMENT_PREPARATION_REPORT.md` — **new**

This report.

---

## 3. Verification results

All commands were run from the stated directory with `JAVA_HOME` set to JDK 21.

### 3.1 Backend tests

```powershell
cd backend
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'
.\mvnw.cmd -B test
```

**Result: PASS**

```
Tests run: 543, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This includes the five new CORS tests and the existing profile/CORS tests:

- `BootstrapHrSeederNotDevIntegrationTest` — seeder absent outside `dev`.
- `BootstrapHrSeederDevIntegrationTest` — seeder present under `dev`.
- `PasswordManagementIntegrationTest` — `/api/auth/dev-reset-password` returns **404** outside `dev`.
- `SecurityIntegrationTest` — default frontend origin allowed, unapproved origin rejected.

### 3.2 Backend package/build

```powershell
cd backend
.\mvnw.cmd -q -DskipTests package
```

**Result: PASS** (exit code 0)

- Artifact: `target/backend-0.0.1-SNAPSHOT.jar` (66,502,159 bytes).
- Jar contains `BOOT-INF/classes/db/migration/V1…V5`.
- Packaged `application.properties` contains:
  - `server.port=${PORT:8080}`
  - no `spring.profiles.active`
  - `spring.flyway.locations=${MIGRATIONS_LOCATION:filesystem:../database/migrations}`
  - `evalora.cors.allowed-origins=${EVALORA_CORS_ALLOWED_ORIGINS:http://localhost:5173}`

### 3.3 Frontend tests

```powershell
cd frontend
npm test
```

**Result: PASS** — 27 test files, **311 tests passed**.

### 3.4 Frontend lint

```powershell
npm run lint
```

**Result: PASS** — no findings.

### 3.5 Frontend production build

```powershell
npm run build
```

**Result: PASS** — 145 modules transformed.

```
dist/index.html                 0.58 kB
dist/assets/index-*.css        29.13 kB (5.47 kB gzip)
dist/assets/index-*.js        462.56 kB (130.41 kB gzip)
```

### 3.6 Secret scan of changed files

Scanned `.gitignore`, `.env.example`, `pom.xml`, `application.properties`, `CorsProperties.java`, `SecurityConfig.java`,
and the new test for JDBC URLs, `JWT_SECRET=`, `DB_PASSWORD=`, private keys, and common token prefixes.

Only the intentional placeholders in `.env.example` matched. No real secret was added to any changed file.

### 3.7 Git state

- `.git` does **not** exist.
- No commit or push was performed.
- `git status --ignored` therefore cannot be run yet; it remains a required pre-commit check.

---

## 4. Configuration behavior verification

### CORS

- Default allow-list still permits the local Vite origin; the pre-existing CORS integration test passes.
- A configured list replaces the default (unit test).
- Wildcard and empty lists fail fast at startup (unit tests).
- `allowCredentials` remains `false`.

### Profile

- No profile is active by default from `application.properties`.
- Local development keeps `SPRING_PROFILES_ACTIVE=dev` through the untracked `.env` (and `.env.example` now documents it).
- `BootstrapHrSeeder` and `DevPasswordResetController` remain `@Profile("dev")`.
- Tests prove the seeder is absent outside `dev` and the dev reset route returns 404 outside `dev`.

### Port

- `server.port=${PORT:8080}` is present in the packaged jar.
- Local fallback remains `8080`.
- This was verified by inspecting the packaged configuration, **not** by binding a live Render port.

### Flyway

- Local default behavior is unchanged (`filesystem:../database/migrations` from `backend/`).
- The jar now contains all five migrations under `db/migration`.
- `fail-on-missing-locations=true` remains, so a bad `MIGRATIONS_LOCATION` fails startup clearly.
- No migration file was created, edited, renamed, or applied.

---

## 5. Remaining blockers and notes

These are intentionally **not** resolved in this phase:

1. **No Docker/Jenkins/Render assets yet.** `Dockerfile`, `.dockerignore`, `Jenkinsfile`, `docker-compose.yml`, and
   `render.yaml` are still absent, as required.
2. **Production environment values are operator-supplied.** Deployment must set at minimum `DB_URL`, `DB_USERNAME`,
   `DB_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE=prod`, and `EVALORA_CORS_ALLOWED_ORIGINS`. A container should
   also set `MIGRATIONS_LOCATION=classpath:db/migration` or mount an external migrations folder.
3. **No live verification.** PostgreSQL startup, Flyway against a real database, Docker image startup, and Render port
   binding were not executed.
4. **No Git repository.** `.gitignore` correctness is logically updated but cannot be confirmed with `git status` until
   Git is initialized (only when authorized).
5. **Local `.env` still holds real secrets.** It was not modified. It must stay untracked; the first commit must be
   checked with `git status --ignored`.
6. **Maven wrapper jar is ignored by `backend/.gitignore`.** The wrapper still worked here, but a clean clone may need
   to re-download it. Decide before the first commit whether to track `maven-wrapper.jar`.
7. **IDE resource handling for packaged migrations.** Local development still uses the filesystem default, so IDE runs
   are unaffected. The packaged copy is exercised by `mvn package`; a Docker build should set `classpath:db/migration`
   explicitly.
8. **CORS env-var parsing is covered by unit tests plus Spring's standard list binding**, not by a live deployment test.
9. **No root `README.md` yet** (tracked separately in the audit).

---

## 6. Recommended next steps

1. When authorized, initialize Git and run `git status --ignored` to confirm `.env` and build output are excluded.
2. Add the Docker assets, setting `MIGRATIONS_LOCATION=classpath:db/migration` and `SPRING_PROFILES_ACTIVE=prod`.
3. Add the Jenkins pipeline with JDK 21 and the frontend lint/test/build stages.
4. Add the Render manifest or document dashboard settings for `PORT`, PostgreSQL, CORS, profile, and Flyway.
5. Run a live staging startup to verify PostgreSQL connectivity, Flyway, CORS preflight from the deployed origin, and
   the absence of `/api/auth/dev-reset-password`.
