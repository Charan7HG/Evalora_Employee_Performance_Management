# Evalora — GitHub Preparation Audit

**Scope:** Read-only audit of the workspace before publishing to GitHub and before adding Docker, Jenkins, and Render deployment configuration.

**Date:** 2026-11-05
**Workspace:** `C:\Users\DELL\OneDrive\Documents\PMS`

**What was not done:** No source file, configuration file, dependency, or database record was modified. Git was not initialized, and nothing was committed or pushed. No secret value is reproduced in this report. Live PostgreSQL connectivity and a live application startup were not exercised; build and test verification used the repository's own build tooling only.

---

## 1. Files inspected

### Root
- `.gitignore`
- `.env` (inspected for key names and value *lengths* only; values deliberately not recorded)
- `.env.example`
- `.freebuff/.gitignore`, `.freebuff/project-id`
- `.github/modernize/java-upgrade/.gitignore`
- `.vscode/launch.json`, `.vscode/settings.json`

### Backend
- `backend/pom.xml`
- `backend/.gitignore`, `backend/.gitattributes`
- `backend/HELP.md`
- `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`
- `backend/src/main/resources/application.properties`
- `backend/src/test/resources/application.properties`
- `backend/src/main/java/com/evalora/backend/security/SecurityConfig.java`
- `backend/src/main/java/com/evalora/backend/security/JwtProperties.java`
- `backend/src/main/java/com/evalora/backend/security/JwtService.java`
- `backend/src/main/java/com/evalora/backend/config/BootstrapHrSeeder.java`
- `backend/src/main/java/com/evalora/backend/controller/DevPasswordResetController.java`
- `backend/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (stray file — see findings)

### Frontend
- `frontend/package.json`, `frontend/vite.config.js`
- `frontend/.gitignore`, `frontend/.env.example`
- `frontend/README.md`
- `frontend/src/api/axiosInstance.js`
- Generated output: `frontend/dist/`, `frontend/node_modules/` (presence only)

### Database and docs
- `database/migrations/` — `V1__init_schema.sql` … `V5__hr_kpi_dual_rating.sql` (5 migrations)
- `docs/SETUP_GUIDE.md`
- `docs/SECURITY_DESIGN.md` (secret-related references)
- `docs/` — 16 documents total, including the Phase 5E/5F reports

### Toolchain present on this machine
- Java runtime **21.0.12.1** (note: system `JAVA_HOME` points at JDK 17; builds used JDK 21 explicitly)
- Node **v24.15.0**, npm **11.12.1**
- Git **2.53.0**, but **no `.git` repository** in the workspace
- No `Dockerfile`, `.dockerignore`, `docker-compose*.yml`, `Jenkinsfile`, `render.yaml`, or `render.yml` anywhere

---

## 2. GitHub readiness

**Status: READY WITH REQUIRED FIXES BEFORE FIRST PUSH.**

Positives:
- Clear repo layout: `backend/`, `frontend/`, `database/`, `docs/`.
- Build output and dependencies are ignored (`target/`, `node_modules/`, `dist/`).
- The real `.env` is ignored, and a placeholder `.env.example` exists at the root and for the frontend.
- Backend configuration contains **no default credentials or default JWT secret**.
- Backend and frontend both build successfully from the current state.
- Flyway migrations are versioned and outside the jar, which keeps schema changes reviewable.
- Repository documentation is extensive.

Gaps before publishing:
- No root `README.md`. A GitHub visitor sees no project overview, quick start, or architecture at the top level.
- No `LICENSE`, `CONTRIBUTING.md`, or `CODE_OF_CONDUCT.md` (optional, but expected for a public repo).
- No CI workflow. `.github/` currently only contains a `modernize/java-upgrade` scratch folder whose own `.gitignore` ignores everything.
- `.gitignore` does not cover all `.env.*` variants (see §4).
- `docs/SETUP_GUIDE.md` is stale: it lists only migrations V1–V4, older test counts, and pre-Phase-5 CORS/role behavior.
- `frontend/README.md` still says the backend only allows CORS from `http://localhost:5173` and does not mention the change-password screen or the new manager/employee status pages.
- A stray generated directory exists at `backend/META-INF/` (outside `target/` and not ignored).

---

## 3. Secret-handling findings

### 3.1 What is committed vs. local

| Item | State | Risk |
|---|---|---|
| Root `.env` | Present locally, contains real local DB credentials, a real JWT secret, and a bootstrap HR password | **Must never be committed.** It is covered by `.gitignore` (`.env`). |
| Root `.env.example` | Placeholders only | Safe to commit |
| `frontend/.env.example` | `VITE_API_BASE_URL` placeholder only | Safe to commit; it is *not* ignored, so it will be committed |
| `backend/src/main/resources/application.properties` | Env-var placeholders only (`${DB_URL}`, `${JWT_SECRET:}`, etc.) | Safe to commit |
| `backend/src/test/resources/application.properties` | H2 in-memory DB + a clearly labelled **test-only** JWT secret | Safe to commit; it cannot authenticate against a real deployment |
| `docs/SETUP_GUIDE.md`, `docs/SECURITY_DESIGN.md` | Placeholder strings such as `"your-local-password"` | Safe, but should not be mistaken for real values |
| `.vscode/launch.json` | References `${workspaceFolder}/.env`; `.vscode/` is ignored | Safe (and excluded) |

A targeted scan for database URLs, private keys, GitHub tokens, AWS keys, and JWT-shaped strings found **no real secret outside `.env`**. The only matches were the local `.env`, placeholder templates, documentation examples, and a well-known example JWT inside a third-party `node_modules` test fixture (not committed, since `node_modules/` is ignored).

### 3.2 Local `.env` observations (values not recorded)
- Contains real local values for `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, and the dev-only bootstrap HR email/password.
- `JWT_SECRET` is 44 characters, i.e. strong enough for the backend's 32-byte minimum.
- `DB_URL` points at `localhost`, so the credentials are local-only in practice — but they are still real credentials and would be a disclosure if committed.
- `SPRING_PROFILES_ACTIVE` is set inside `.env`.
- **Duplicate keys:** `EVALORA_BOOTSTRAP_HR_EMAIL` and `EVALORA_BOOTSTRAP_HR_PASSWORD` each appear twice. This is not a leak, but it is error-prone; later values win, and it makes rotation ambiguous.
- Because `spring.config.import=optional:file:../.env[.properties]` loads this file, a value committed to `.env.example` would never override the local `.env` — but the reverse mistake (committing `.env`) would leak everything.

### 3.3 Reset tokens and personal data
- Password-reset tokens are generated at runtime and persisted in the database; nothing token-like is written to the repository.
- `DevPasswordResetController` is `@Profile("dev")` and uses a fixed server-side account, never a browser-supplied one.
- `BootstrapHrSeeder` is also `@Profile("dev")` and has no hardcoded default email or password.
- No real employee personal data was found in migrations, docs, or test resources. Seed/test data uses synthetic names and addresses.
- `evalora.email.enabled=false`, so no email provider credentials are needed or present.

**Verdict:** no real credential, JWT secret, database password, reset token, or personal data is positioned to enter a GitHub commit **provided the first commit is made with `.env` untracked and the `.gitignore` gaps in §4 are closed first.**

---

## 4. `.gitignore` findings

Root `.gitignore` currently ignores:
- `.env`, `.env.local`, `*.env`
- `target/`, `node_modules/`, `dist/`, `build/`
- `.vscode/`, `.idea/`, `*.iml`, Eclipse metadata
- Logs, `Thumbs.db`, `.DS_Store`

Nested files:
- `backend/.gitignore` — ignores `HELP.md`, `target/`, IDE folders, and `maven-wrapper.jar`.
- `frontend/.gitignore` — ignores `node_modules`, `dist`, `*.local`, logs, and editor files.
- `.freebuff/.gitignore` and `.github/modernize/.../.gitignore` are irrelevant to the app.
- `backend/.gitattributes` normalizes `mvnw` to LF and `*.cmd` to CRLF — good for cross-platform Jenkins/Linux containers.

Problems:
1. **`.env.*` variants are not ignored.** `*.env` matches names ending in `.env`; it does **not** match `.env.production`, `.env.staging`, `.env.development`, or a backup like `.env.bak`. Any of these created later would be committed by default. Add:
   ```gitignore
   .env.*
   !.env.example
   ```
2. **`.env.example` is safe today** but the negation above is still needed to keep it visible after adding `.env.*`.
3. **No `!.mvn/wrapper/maven-wrapper.jar` at the root effectively applies.** Root has a negation, but `backend/.gitignore` ignores the wrapper jar, so it would not be committed. The Maven wrapper still worked here, but a clean clone may have to re-download the wrapper — pin this deliberately.
4. **`backend/HELP.md` is ignored.** Acceptable (it is Spring Initializr boilerplate), but note it if you intend to keep backend docs.
5. **Stray `backend/META-INF/`** is not ignored and appears to be generated/garbage output at the wrong path. Delete it or ignore it before committing.
6. **No ignore rule for IDE caches beyond the basics** — e.g. `.vs/`, `*.tsbuildinfo`, `.eslintcache`, `coverage/`. Add as needed.
7. `git check-ignore` could not be run because there is no Git repository. Once initialized, run `git status --ignored` to confirm `.env` is excluded before the first commit.

---

## 5. Backend build status

**PASS.**

Command executed from `backend/` with JDK 21:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'
.\mvnw.cmd -q -DskipTests package
```

- Exit code: **0**
- Artifact: `backend/target/backend-0.0.1-SNAPSHOT.jar`, ~66.5 MB (fat/executable Spring Boot jar)
- Parent: Spring Boot **4.1.2**
- `java.version`: **21**
- JWT library: `jjwt` **0.13.0**
- Dependencies include PostgreSQL driver, Flyway, H2 (test), Actuator, Validation, Spring Web, Spring Security, Spring Data JPA.

Notes:
- Tests were skipped for this audit because the goal was a build gate; the repository's test suite is H2-based and independent of local PostgreSQL.
- `backend/target/` is generated output and is gitignored.
- The build uses the Maven wrapper; system `JAVA_HOME` is JDK 17, so Jenkins/Docker **must explicitly select JDK 21** (the wrapper alone will not fix this).

---

## 6. Frontend build status

**PASS.**

Executed from `frontend/`:

| Command | Result |
|---|---|
| `npm test` (Vitest) | **PASS** — 27 test files, 311 tests |
| `npm run lint` (ESLint) | **PASS** — no findings |
| `npm run build` (Vite) | **PASS** — 145 modules transformed |

Build output:
- `dist/index.html` — 0.58 kB
- `dist/assets/index-*.css` — 29.13 kB (5.47 kB gzip)
- `dist/assets/index-*.js` — 462.56 kB (130.41 kB gzip)

Notes:
- Node requirement in `frontend/README.md` is 20.19+/22.12+; this audit used Node 24.15.0.
- The JS bundle is a single chunk. It works, but adding route-level lazy loading would improve first load.
- `npm run build` does not run tests or lint, so Jenkins must call all three explicitly.
- `dist/` and `node_modules/` are gitignored.
- The production build embeds `VITE_API_BASE_URL` **at build time**. A build without that variable silently falls back to `http://localhost:8080/api`, which will break in production.

---

## 7. Database, Flyway, and profile configuration

### Database
- `spring.datasource.url=${DB_URL}`, `username=${DB_USERNAME}`, `password=${DB_PASSWORD}` — no defaults, no inline credentials.
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never creates or mutates the schema; Flyway owns it.
- `spring.jpa.open-in-view=false`, UTC time zone.

### Flyway
- Enabled; migrations live in `database/migrations` (outside the jar).
- `spring.flyway.locations=filesystem:${MIGRATIONS_LOCATION:../database/migrations}`
- `baseline-on-migrate=false`, `validate-on-migrate=true`, `fail-on-missing-locations=true`.
- Migrations present: **V1–V5**, including `V5__hr_kpi_dual_rating.sql`.

**Deployment consequence:** `filesystem:` does not read from inside a jar. Docker images, Render services, and Jenkins agents must either:
- run with the repository's `database/migrations` directory available at `../database/migrations` relative to the working directory, **or**
- set `MIGRATIONS_LOCATION` to an absolute path containing the SQL.

Because `fail-on-missing-locations=true`, a wrong path fails startup immediately rather than silently skipping migrations. That is desirable, but it makes migration packaging a hard deployment requirement.

### Profiles
- `spring.profiles.active=dev` is **hardcoded** in `application.properties`.
- `.env` also sets `SPRING_PROFILES_ACTIVE`, but the checked-in default is `dev`.
- `dev` activates:
  - `BootstrapHrSeeder` (creates/updates the first HR account from `EVALORA_BOOTSTRAP_HR_*`)
  - `DevPasswordResetController` (direct password reset endpoint)

**Deployment consequence:** production **must** set `SPRING_PROFILES_ACTIVE` to a non-`dev` value (e.g. `prod`). Otherwise the dev-only endpoint and bootstrap seeder remain active. No `application-prod.properties` exists yet, so either add one or override every setting via environment variables.

### JWT
- `evalora.jwt.secret=${JWT_SECRET:}` with **no default**.
- `JwtService` rejects a missing or sub-32-byte secret at startup — good fail-fast behavior.
- `JWT_EXPIRATION` defaults to 8 hours.
- Production must supply a fresh, high-entropy `JWT_SECRET` (never reuse the local one).

### CORS — production blocker
- `SecurityConfig.FRONTEND_ORIGIN = "http://localhost:5173"` is a compile-time constant.
- `CorsConfiguration.setAllowedOrigins(List.of(FRONTEND_ORIGIN))` allows **only** that origin.
- A deployed frontend on any other host will have every browser request blocked.
- CORS must become configurable (e.g. `EVALORA_CORS_ALLOWED_ORIGINS`), and the frontend origin must be set per environment. This is a code change and is therefore **out of scope for this read-only audit**.

### Dev-only password reset
- `DevPasswordResetController` is guarded by `@Profile("dev")`.
- The target account is fixed server-side (`evalora.dev-reset.email`, defaulting to the bootstrap HR email).
- It is not exposed under any other profile, provided the profile is actually changed in production.

---

## 8. Environment-variable contract

The backend reads everything from the environment; `spring.config.import=optional:file:../.env[.properties]` is **optional**, so the app starts without a local `.env` as long as the required variables are supplied.

| Variable | Required | Notes |
|---|---|---|
| `DB_URL` | **Yes** | No default. JDBC URL including host, port, database, and any TLS parameters. |
| `DB_USERNAME` | **Yes** | No default. |
| `DB_PASSWORD` | **Yes** | No default. |
| `JWT_SECRET` | **Yes** | No default; ≥32 bytes UTF-8. App refuses to start if missing/short. |
| `JWT_EXPIRATION` | No | Defaults to `28800000` ms (8 h). |
| `MIGRATIONS_LOCATION` | Deploy-dependent | Absolute path to the migrations directory when not running from `backend/`. |
| `SPRING_PROFILES_ACTIVE` | **Yes in production** | Must be non-`dev` to disable dev reset + bootstrap seeder. |
| `EVALORA_BOOTSTRAP_HR_*` | Dev only | Not needed in production; leave empty/unset. |
| `EVALORA_DEV_RESET_EMAIL` | Dev only | Not needed in production. |
| `SERVER_PORT` / `PORT` | Render | See §11. |
| `VITE_API_BASE_URL` | Frontend build | Backend API root, e.g. `https://api.example.com/api`. |

**Answer to the explicit question:** yes — the backend can run from environment variables without the local `.env`, because the import is optional and every credential is an env placeholder. It will still fail fast if `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`JWT_SECRET` are absent, which is intended.

---

## 9. Docker requirements

No Docker assets exist yet. To containerize:

1. **Base image:** Eclipse Temurin JDK/JRE **21** (e.g. `eclipse-temurin:21-jre`), not 17.
2. **Build stage:** Maven build with the wrapper or a Maven image; copy `backend/pom.xml` and `.mvn/` first for layer caching.
3. **Migration files:** copy `database/migrations` into the image (e.g. `/app/database/migrations`) and set `MIGRATIONS_LOCATION=/app/database/migrations`. Without this, `fail-on-missing-locations=true` aborts startup.
4. **Working directory:** set it deliberately; the default `../database/migrations` only works in the repo layout.
5. **Port:** expose `8080`; allow `SERVER_PORT` override.
6. **User:** run as a non-root user.
7. **Env injection:** pass `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE`, `MIGRATIONS_LOCATION` at runtime. Do **not** `COPY .env`.
8. **`.dockerignore`:** exclude `.env`, `**/.env*` (keep `.env.example` if desired), `.git`, `node_modules`, `target`, `dist`, `docs` (optional), `.vscode`.
9. **Health check:** use Actuator (already a dependency) or a TCP check on the HTTP port.
10. **Frontend:** separate build stage with Node 20+/22+/24, `npm ci`, `npm run build`, serve `dist/` via Nginx or a static host. `VITE_API_BASE_URL` must be a **build argument**, since Vite inlines it.
11. **Compose (optional):** a `docker-compose.yml` with PostgreSQL 16/17 + backend + frontend is useful for local parity; keep credentials in an untracked env file.

---

## 10. Jenkins requirements

No `Jenkinsfile` exists yet. A pipeline needs:

1. **Agents/tools:** JDK **21** (explicit `tools { jdk 'jdk-21' }` or `JAVA_HOME`), Node **20.19+/22.12+**, Git, Docker (if building images).
2. **Checkout:** full history is not required; shallow clone is fine.
3. **Backend stage:**
   - `cd backend`
   - `./mvnw -B clean verify` (or `package`) with JDK 21
   - Publish Surefire results and the jar artifact.
4. **Frontend stage:**
   - `cd frontend`
   - `npm ci`
   - `npm run lint`
   - `npm test`
   - `npm run build` with `VITE_API_BASE_URL` injected from Jenkins credentials/env
   - Archive `dist/` and JUnit results.
5. **Credentials:** store `DB_*`, `JWT_SECRET`, and deploy tokens in Jenkins Credentials; never echo them. Use `withCredentials`/`environment` blocks.
6. **Do not** run Flyway against production from a PR build. Migration execution should be part of the deploy stage, or handled by the app at startup with a controlled release.
7. **Docker stage (optional):** build/tag/push only on the main branch or tagged releases.
8. **Post actions:** archive artifacts, publish HTML/JUnit reports, notify on failure.
9. **Workspace hygiene:** ensure `.env` is not copied into the workspace from a credential file unless intentionally mounted; prefer environment variables.

Suggested stage order: **Checkout → Backend build/test → Frontend install/lint/test/build → Package (jar + dist) → Docker build/push → Deploy → Smoke test.**

---

## 11. Render deployment requirements

No `render.yaml` exists. For a Render web service:

1. **Runtime:** Docker (recommended, since the app needs JDK 21) or a native environment with Java 21.
2. **Port binding — blocker:** Render injects `PORT` and expects the process to bind to it. The app hardcodes `server.port=8080`. You must either:
   - start with `--server.port=$PORT`, or
   - set `SERVER_PORT=$PORT`, or
   - add `server.port=${PORT:${SERVER_PORT:8080}}` to configuration.
   A service that binds only to 8080 will fail Render's port detection.
3. **PostgreSQL:** provision a Render PostgreSQL instance and set `DB_URL` to its **internal** connection string (with `sslmode=require` where applicable), plus `DB_USERNAME`/`DB_PASSWORD`. Do not reuse local credentials.
4. **JWT:** generate a new production secret and set `JWT_SECRET` in the Render dashboard (secret env var). Never reuse the local value.
5. **Profiles:** set `SPRING_PROFILES_ACTIVE=prod` so the dev reset endpoint and HR bootstrap seeder are absent.
6. **Migrations:** include `database/migrations` in the image and set `MIGRATIONS_LOCATION`; or run migrations as a pre-deploy/release step. Startup will fail if the location is missing.
7. **Frontend:** deploy `frontend/dist` as a Render Static Site (or serve via Nginx). Set `VITE_API_BASE_URL` to the backend's public URL **before building**.
8. **CORS:** update the backend to allow the Render static-site origin. This requires the configurable-CORS code change noted in §7.
9. **Health checks:** point Render's health check at an Actuator endpoint (e.g. `/actuator/health`) and ensure it is permitted without authentication.
10. **Build command:** `cd backend && ./mvnw -B -DskipTests package` (or the Docker build). Note that `mvnw` is LF-normalized via `.gitattributes`.
11. **Env var checklist for Render:** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE=prod`, `MIGRATIONS_LOCATION`, `SERVER_PORT=$PORT` (or the equivalent).

---

## 12. Blocking issues

Ordered by severity.

| # | Issue | Impact | Type |
|---|---|---|---|
| 1 | **Real secrets exist in local `.env`** | If `.env` is committed, DB credentials, JWT secret, and bootstrap password leak permanently | Process / pre-commit |
| 2 | **`.gitignore` misses `.env.*` variants** (`.env.production`, `.env.staging`, …) | A future env file is committed by default | Config |
| 3 | **CORS hardcoded to `http://localhost:5173`** | Deployed frontend cannot call the backend | Code |
| 4 | **`spring.profiles.active=dev` hardcoded** | Dev password-reset endpoint and HR bootstrap stay active in production unless overridden | Config |
| 5 | **Flyway `filesystem:` location + `fail-on-missing-locations=true`** | Container/Render startup fails unless migrations are copied and `MIGRATIONS_LOCATION` is set | Deployment |
| 6 | **Server port hardcoded to 8080** | Render cannot detect the bound port unless `PORT` is mapped | Deployment |
| 7 | **No Docker/Jenkins/Render assets** | No reproducible deploy path yet | Missing config |
| 8 | **No root `README.md`** | Poor GitHub landing experience; setup knowledge is buried in `docs/` | Docs |
| 9 | **Stale `docs/SETUP_GUIDE.md` and `frontend/README.md`** | New contributors follow outdated migration/CORS/test instructions | Docs |
| 10 | **Stray `backend/META-INF/` directory** | Looks like generated debris; may be committed accidentally | Cleanup |
| 11 | **`VITE_API_BASE_URL` fallback silently points to localhost** | A production build without the variable produces a broken frontend that still "builds successfully" | Build config |
| 12 | **No `.git` repository** | Cannot verify ignore rules or make a first commit from this state | Process |

**Non-blocking but worth fixing:** duplicate `EVALORA_BOOTSTRAP_HR_*` keys in `.env`; missing root license/contributing docs; single large frontend bundle.

---

## 13. Recommended next steps

### Before the first commit (must-do)
1. Add to root `.gitignore`:
   ```gitignore
   .env.*
   !.env.example
   ```
2. Delete or ignore `backend/META-INF/`.
3. Create a root `README.md` (overview, architecture, prerequisites, quick start, env var table, links to `docs/`).
4. Refresh `docs/SETUP_GUIDE.md` to include V1–V5, current CORS/role behavior, and current test counts.
5. Update `frontend/README.md` for the change-password screen, manager/employee status pages, and configurable API URL.
6. Decide whether to commit the Maven wrapper jar; if yes, remove the `maven-wrapper.jar` ignore from `backend/.gitignore`.
7. Initialize Git **only when authorized**, then run:
   ```powershell
   git add -A --dry-run
   git status --ignored
   ```
   Confirm `.env` and all build output are absent before the first commit.

### Configuration/code changes (separate task — out of this audit's scope)
8. Make CORS origins configurable via an environment variable with a safe local default.
9. Add `application-prod.properties` (or document the full env-var override set) and ensure `SPRING_PROFILES_ACTIVE=prod` in production.
10. Support `PORT`/`SERVER_PORT` for Render: `server.port=${PORT:${SERVER_PORT:8080}}`.
11. Decide the migration packaging strategy: bake `database/migrations` into the image and set `MIGRATIONS_LOCATION`, or run Flyway as a release step.

### Deployment assets (separate task)
12. Add `Dockerfile`, `.dockerignore`, and an optional `docker-compose.yml` (PostgreSQL + backend + frontend).
13. Add a `Jenkinsfile` with the stages in §10.
14. Add `render.yaml` (or document dashboard settings) covering the backend service, PostgreSQL, static site, health check, and the env var list in §11.
15. Configure GitHub repository secrets/environments for CI (never commit them).
16. Add a CI workflow that runs backend tests and frontend lint/test/build on pull requests.

### Verification after deployment config exists
17. Build the production frontend with an explicit `VITE_API_BASE_URL` and confirm no request targets `localhost`.
18. Start the backend with only environment variables (no `.env`) and confirm successful startup and Flyway migration.
19. Confirm the production profile removes `/api/dev/**` and the bootstrap seeder.
20. Smoke-test login, CORS preflight from the deployed frontend origin, and one authenticated API call per role.

---

## Appendix A — Commands used for verification

```powershell
# Backend (JDK 21, tests skipped for the build gate)
cd backend
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'
.\mvnw.cmd -q -DskipTests package      # exit 0

# Frontend
cd frontend
npm test                               # 27 files / 311 tests passed
npm run lint                           # clean
npm run build                          # 145 modules transformed

# Inventory / scans
Get-ChildItem -Recurse -File -Force ... # structure, excluding node_modules/target/dist
Test-Path .git                          # False
# grep for jdbc URLs, private keys, GitHub/AWS tokens, JWT-shaped strings
# grep for JWT_SECRET=, DB_PASSWORD=, bootstrap credentials
```

## Appendix B — Result summary

| Area | Result |
|---|---|
| Backend Maven build (JDK 21) | **PASS** |
| Backend tests | Not run in this audit (build gate only) |
| Frontend tests | **PASS** (311) |
| Frontend lint | **PASS** |
| Frontend production build | **PASS** |
| Secrets outside `.env` | **None found** |
| `.env` gitignored | **Yes** (`.env.*` variants still exposed) |
| Backend runs from env vars without `.env` | **Yes**, with required vars set |
| Frontend API URL configurable | **Yes** (`VITE_API_BASE_URL`), but has a localhost fallback |
| Git repository present | **No** |
| Docker / Jenkins / Render assets | **None** |
| Ready for first GitHub push | **Conditional** — fix §13 items 1–7 first |
