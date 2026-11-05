# Evalora — Employee Performance Management System

Evalora is a full-stack employee performance management system. It gives HR teams the tools to manage staff and
reporting lines, define KPIs and performance cycles, and run the complete assessment workflow, while managers and
employees get focused dashboards and self-service workflows for their own reviews and results.

The backend is a stateless, JWT-secured Spring Boot REST API. The frontend is a React single-page application.
PostgreSQL stores all data, and Flyway owns the schema so application code never creates or alters tables.

---

## Features

### Roles

| Role | Capabilities |
|---|---|
| **HR** | Manage employees and managers (including reporting lines), maintain the KPI catalogue, create and configure performance cycles, assign KPIs and employees, monitor workflow status, perform HR assessment, and publish results |
| **MANAGER** | View direct reports and team dashboards, review employee self-assessments, submit manager ratings and comments, and view results for their team |
| **EMPLOYEE** | View the current performance cycle, complete and submit self-assessments with KPI ratings and comments, and view published results |

### Main capabilities

- Stateless JWT authentication with database-backed role and account-status checks.
- HR staff management: create, update, deactivate, and reactivate employees and managers, with append-only reporting-line history.
- KPI catalogue with KPI types and assignment to performance cycles.
- Performance cycle lifecycle: draft, publish, in progress, completed, and results published.
- Employee self-assessment, manager assessment, HR assessment, and final scoring.
- Role-specific dashboards with workflow counters and current-cycle information.
- Published results with frozen KPI snapshots and persisted scores.
- Swagger / OpenAPI documentation for the REST API.
- Versioned PostgreSQL schema migrations (Flyway `V1`–`V5`).
- Configurable CORS allow-list, environment-driven configuration, and separate local/production Spring profiles.

---

## Technology stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1.2, Spring Web MVC, Spring Security, Spring Data JPA |
| Authentication | Stateless HS256 JWT (`jjwt` 0.13.0), BCrypt password hashing (strength 12) |
| Database | PostgreSQL 16+, Flyway migrations, Hibernate schema validation only |
| Backend build | Maven Wrapper 3.3.4 (`only-script`), no local Maven installation required |
| API docs | springdoc-openapi 3.2.0 (Swagger UI + OpenAPI JSON) |
| Backend testing | JUnit, Spring Boot Test, MockMvc, AssertJ, H2 in PostgreSQL mode |
| Frontend | React 19, React Router 7, Axios, Vite 8 |
| Frontend testing | Vitest 5, Testing Library, jsdom, ESLint 10 |
| Runtime requirements | JDK 21, Node.js 20.19+ / 22.12+, PostgreSQL 16+ |

---

## Architecture overview

```
Browser (React SPA)
        │  HTTPS + JSON, Authorization: Bearer <JWT>
        ▼
Spring Boot REST API
        │  Security filter chain → controllers → services
        │  Role and account status read from the database on every request
        ▼
Spring Data JPA / Hibernate (validate only)
        ▼
PostgreSQL  ── schema created and versioned by Flyway (database/migrations)
```

- Authentication is stateless: no server session, no cookies. The JWT travels in the `Authorization` header.
- Authorization is enforced by path prefix: `/api/hr/**` → `ROLE_HR`, `/api/manager/**` → `ROLE_MANAGER`,
  `/api/employee/**` → `ROLE_EMPLOYEE`.
- Flyway migrations are applied at startup. Hibernate runs with `ddl-auto=validate` and fails fast on a schema mismatch.
- Configuration is supplied through environment variables. Real values live in an untracked `.env` file locally or in
  the platform's secret manager in production.
- CORS is an explicit allow-list. Wildcards are rejected, and credentials are disabled because the token is sent in a
  header rather than a cookie.

---

## Project directory structure

```
PMS/
├── backend/                      Spring Boot REST API (Java 21, Maven)
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd           Maven Wrapper (only-script mode)
│   ├── .mvn/wrapper/             Wrapper configuration
│   └── src/
│       ├── main/java/com/evalora/backend/
│       │   ├── config/           OpenAPI, dev HR bootstrap
│       │   ├── controller/       Auth, HR, manager and employee APIs
│       │   ├── dto/              Request/response models
│       │   ├── entity/           JPA entities and enums
│       │   ├── repository/       Spring Data repositories
│       │   ├── security/         JWT, security filter chain, CORS, error handlers
│       │   ├── service/          Authentication, management, assessment, scoring, results
│       │   └── util/             Email normalization, password policy, reset tokens
│       ├── main/resources/application.properties
│       └── test/                 H2-backed unit and integration tests
├── frontend/                     React single-page application (Vite)
│   ├── package.json
│   ├── .env.example
│   └── src/                      Pages, components, API clients, tests
├── database/
│   └── migrations/               Flyway V1–V5 SQL (canonical schema)
├── docs/                         Setup, API, security, database, scoring and deployment docs
├── .env.example                  Local environment template (placeholders only)
├── .gitignore
└── README.md
```

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | `java -version` must report 21.x |
| PostgreSQL | **16 or newer** | Running locally on port 5432 for development |
| Node.js | **20.19+**, **22.12+**, or newer | Required for the frontend |
| npm | Included with Node.js | Used for install, test, lint and build |
| Maven | Not required | Use the included Maven Wrapper |
| Git | Optional | Only needed to clone/publish the repository |

> Set `JAVA_HOME` to JDK 21 for the session before running backend commands:
>
> ```powershell
> $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
> $env:Path = "$env:JAVA_HOME\bin;$env:Path"
> ```

---

## 1. Local PostgreSQL setup

Create a local role and database. Choose your own password; it must never be committed.

Using `psql` as the PostgreSQL superuser:

```sql
CREATE ROLE evalora_app WITH LOGIN PASSWORD 'choose-a-strong-local-password';
CREATE DATABASE evalora OWNER evalora_app;
```

Then connect to the new database and grant schema rights:

```sql
\c evalora
GRANT ALL ON SCHEMA public TO evalora_app;
```

The database name, user, and password are supplied to the backend through environment variables; they are never
hardcoded in `application.properties`.

---

## 2. Backend environment configuration

From the repository root, copy the template and fill in local values:

```powershell
Copy-Item .env.example .env
```

The backend loads the repository-root `.env` through
`spring.config.import=optional:file:../.env[.properties]` when it runs from `backend/`.

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | Yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/evalora` |
| `DB_USERNAME` | Yes | PostgreSQL user (`evalora_app` in the example) |
| `DB_PASSWORD` | Yes | Local database password |
| `JWT_SECRET` | Yes | HS256 signing key, at least 32 bytes. The backend refuses to start without it |
| `JWT_EXPIRATION` | No | Token lifetime in milliseconds; defaults to 8 hours (`28800000`) |
| `SPRING_PROFILES_ACTIVE` | Local: `dev` | Enables the dev-only HR bootstrap and password-reset helper. Production must use a non-`dev` value |
| `EVALORA_BOOTSTRAP_HR_EMAIL` | Dev only | Initial HR account email created by the `dev` seeder |
| `EVALORA_BOOTSTRAP_HR_PASSWORD` | Dev only | Initial HR password; must satisfy the password policy |
| `EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE` | No | Defaults to `HR-001` |
| `EVALORA_BOOTSTRAP_HR_FULL_NAME` | No | Defaults to `HR Administrator` |
| `EVALORA_CORS_ALLOWED_ORIGINS` | No | Comma-separated browser origins; defaults to `http://localhost:5173` |
| `MIGRATIONS_LOCATION` | No | Full Flyway location override, e.g. `classpath:db/migration` |
| `PORT` | No | HTTP port; defaults to `8080` |

Generate a local JWT secret (do not reuse it anywhere else):

```powershell
openssl rand -hex 32
```

The application starts even when the optional dev bootstrap values are empty; it never invents a default account.

### Start the backend

```powershell
cd backend
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
.\mvnw.cmd spring-boot:run
```

On Linux/macOS:

```bash
cd backend
./mvnw spring-boot:run
```

At startup:

- Flyway applies any pending migration under `database/migrations`.
- Hibernate validates the schema but does not change it.
- The API binds to `8080` unless `PORT` is set.
- The dev profile creates the bootstrap HR account when the two `EVALORA_BOOTSTRAP_HR_*` values are configured.

### Build and run the packaged jar

```powershell
cd backend
.\mvnw.cmd -DskipTests package
java -jar target\backend-0.0.1-SNAPSHOT.jar
```

The jar contains a copy of the migrations at `classpath:db/migration`, so a container can set
`MIGRATIONS_LOCATION=classpath:db/migration` and run without an external migrations folder.

---

## 3. Frontend environment configuration

```powershell
cd frontend
npm install
Copy-Item .env.example .env.local
```

`frontend/.env.example` contains:

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api
```

Set `VITE_API_BASE_URL` to the backend API root. Vite inlines this value at build time, so a production build must set it
to the deployed backend URL before `npm run build`.

### Start the frontend

```powershell
cd frontend
npm run dev
```

Open the URL printed by Vite (normally `http://localhost:5173`). The backend allows that origin by default through
`EVALORA_CORS_ALLOWED_ORIGINS`. Sign-in requires a real account; no demo credentials are provided.

---

## 4. Test and build commands

### Backend

```powershell
cd backend
.\mvnw.cmd test                 # unit + integration tests on H2 (no PostgreSQL required)
.\mvnw.cmd -DskipTests package  # build the executable jar
```

### Frontend

```powershell
cd frontend
npm test                        # Vitest suite
npm run lint                    # ESLint
npm run build                   # production bundle in dist/
```

The frontend build does not run tests or lint, so CI should run all three commands explicitly.

---

## 5. API documentation (Swagger / OpenAPI)

The backend exposes springdoc-openapi endpoints (public, no token required for the documentation UI):

- Swagger UI: `http://localhost:8080/swagger-ui.html` (also `/swagger-ui/index.html`)
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health check: `http://localhost:8080/actuator/health`

To call protected endpoints from Swagger UI:

1. `POST /api/auth/login` with a valid email and password.
2. Copy the returned token.
3. Use the **Authorize** dialog with `Bearer <token>`.

Detailed endpoint documentation also lives in [`docs/API_DOCUMENTATION.md`](docs/API_DOCUMENTATION.md).

---

## 6. Deployment configuration overview

Evalora is environment-driven and has no committed production secrets. A deployment must provide:

| Setting | Production value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | A non-`dev` value such as `prod` (disables the dev bootstrap and dev reset endpoint) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The deployed PostgreSQL connection details |
| `JWT_SECRET` | A fresh per-environment secret of at least 32 bytes |
| `EVALORA_CORS_ALLOWED_ORIGINS` | The deployed frontend origin(s), comma-separated |
| `MIGRATIONS_LOCATION` | `classpath:db/migration`, or an absolute `filesystem:` path |
| `PORT` | Supplied by the hosting platform when required; defaults to `8080` |
| `VITE_API_BASE_URL` | Frontend build-time API root |

The jar packages the migrations, so the default container setup can use `classpath:db/migration`. Startup fails clearly
if a configured Flyway location is missing.

See [`docs/DEPLOYMENT_CONFIGURATION.md`](docs/DEPLOYMENT_CONFIGURATION.md) for the complete contract and the requirements
for Docker, Jenkins/CI, and Render. Deployment files (Dockerfile, Jenkinsfile, Render manifest) are not yet part of the
repository.

---

## 7. Security notes

- **Never commit `.env`, `.env.*`, or any file containing real credentials.** The local `.env` is ignored by
  `.gitignore`; only `.env.example` with placeholders belongs in the repository.
- The repository includes no real database password, JWT secret, bootstrap password, reset token, private key, or
  personal data. Test configuration uses an H2 in-memory database and a clearly labelled test-only JWT secret.
- Generate a separate `JWT_SECRET` for every environment. The backend refuses to start when it is missing or shorter
  than 32 bytes.
- The CORS allow-list is explicit; wildcard origins are rejected at startup.
- Dev-only features (`BootstrapHrSeeder` and the direct reset endpoint) exist only under the `dev` profile. Production
  must set a non-`dev` profile.
- Roles and account status are always read from the database at request time; the JWT `role` claim is informational only.
- Passwords are hashed with BCrypt strength 12. Reset tokens are stored as SHA-256 hashes.
- Before the first commit, verify with `git status --ignored` that `.env` and build output are not staged, and never use
  `git add -f` on secret or generated files.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/SETUP_GUIDE.md`](docs/SETUP_GUIDE.md) | Detailed local setup, migrations, profiles, troubleshooting, and workflow walkthroughs |
| [`docs/API_DOCUMENTATION.md`](docs/API_DOCUMENTATION.md) | Endpoint reference |
| [`docs/DEPLOYMENT_CONFIGURATION.md`](docs/DEPLOYMENT_CONFIGURATION.md) | Environment variables and Docker/Jenkins/Render requirements |
| [`docs/DATABASE_DESIGN.md`](docs/DATABASE_DESIGN.md) | Schema and data model |
| [`docs/ER_DIAGRAM.md`](docs/ER_DIAGRAM.md) | Entity-relationship diagram |
| [`docs/SECURITY_DESIGN.md`](docs/SECURITY_DESIGN.md) | Authentication and authorization design |
| [`docs/SCORING_LOGIC.md`](docs/SCORING_LOGIC.md) | Assessment scoring rules |
| [`docs/PERFORMANCE_WORKFLOW.md`](docs/PERFORMANCE_WORKFLOW.md) | Cycle and assessment lifecycle |
| [`docs/TESTING.md`](docs/TESTING.md) | Test strategy and coverage |
| [`frontend/README.md`](frontend/README.md) | Frontend setup and screen-by-screen behavior |
