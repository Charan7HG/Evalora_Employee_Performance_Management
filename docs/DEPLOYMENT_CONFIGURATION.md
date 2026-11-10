# Evalora — Deployment Configuration

This document lists the configuration contract Evalora expects from GitHub Actions/Jenkins, Docker, and Render. It
contains **no secret values**. Actual Dockerfiles, Jenkinsfiles, and Render manifests are intentionally not part of
this phase.

Related: `docs/SETUP_GUIDE.md` (local development) and `docs/GITHUB_PREPARATION_AUDIT.md` (preparation audit).

---

## 1. Environment variables

### Backend (required)

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | Yes | JDBC URL, e.g. `jdbc:postgresql://HOST:5432/DATABASE`. Use the provider's internal URL where available. |
| `DB_USERNAME` | Yes | Database user. |
| `DB_PASSWORD` | Yes | Database password. Store only in the platform's secret manager. |
| `JWT_SECRET` | Yes | HMAC signing secret, at least 32 bytes of UTF-8. Generate per environment; never reuse a local value. |
| `SPRING_PROFILES_ACTIVE` | Yes in production | Must be a non-`dev` value. Use `prod,gmail` for Gmail SMTP or `prod` alone for Brevo. `dev` enables the HR bootstrap and the development password reset. |

### Backend (optional)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port. Render injects this automatically; `server.port=${PORT:8080}` reads it. |
| `JWT_EXPIRATION` | `28800000` | Token lifetime in milliseconds (8 hours). |
| `MIGRATIONS_LOCATION` | `filesystem:../database/migrations` | Flyway location. Use `classpath:db/migration` for the copy packaged in the jar, or `filesystem:/abs/path` for an external folder. |
| `EVALORA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` in development | Comma-separated browser origins allowed to call the API. Wildcards are rejected. The backend checks that this list includes the origin in `EVALORA_FRONTEND_URL`. Production Docker Compose requires an explicit value. |
| `EVALORA_FRONTEND_URL` | `http://localhost:5173` in development | Base URL used to build emailed activation links (`.../activate?token=...`). It must be an absolute `http`/`https` URL reachable from the device opening the email. Production startup requires an explicit valid value; never use localhost in production. |
| `EVALORA_EMAIL_ENABLED` | `false` | Enables the configured email provider. |
| `EVALORA_SMTP_HOST` | `smtp.gmail.com` | Gmail SMTP host (used only with the `gmail` profile). |
| `EVALORA_SMTP_PORT` | `587` | Gmail SMTP port; STARTTLS is required. |
| `EVALORA_SMTP_USERNAME` | empty | Gmail account that sends notifications. |
| `EVALORA_SMTP_PASSWORD` | empty | Google App Password for that account. Store only in the platform secret manager. |
| `BREVO_API_KEY` | empty | Brevo API key, used only with `prod` and no `gmail` profile. |
| `EVALORA_MAIL_FROM_ADDRESS` | `evalora@evalora.local` | Sender email address. For Gmail it must match the authenticated account. |
| `EVALORA_MAIL_FROM_NAME` | `Evalora` | Sender display name. |
| `EVALORA_OTP_PEPPER` | empty | HMAC-SHA256 pepper for OTP hashing. Required outside `dev`; the backend refuses to start without it. |
| `EVALORA_OTP_MAX_ATTEMPTS` | `5` | Maximum OTP requests per email per 15-minute window. |
| `EVALORA_OTP_RESEND_COOLDOWN` | `60` | Resend cooldown in seconds. |
| `EVALORA_BOOTSTRAP_HR_*` | empty | **Dev profile only.** Not needed in production. |
| `EVALORA_DEV_RESET_EMAIL` | empty | **Dev profile only.** Not needed in production. |

### Frontend (build time)

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080/api` | Backend API root. Vite inlines this at build time, so set it before `npm run build`. |

> The frontend fallback exists only for local development. A production build without `VITE_API_BASE_URL` will point at
> localhost and fail in the browser even though the build succeeds.

---

## 2. Profiles

- `application.properties` no longer sets `spring.profiles.active`.
- Local development gets `SPRING_PROFILES_ACTIVE=dev` from the untracked `.env` file (see `.env.example`).
- Production **must** set `SPRING_PROFILES_ACTIVE=prod` (or another non-`dev` value).
- `BootstrapHrSeeder` and `DevPasswordResetController` are `@Profile("dev")`, so they do not exist under `prod`.
- The tests `BootstrapHrSeederNotDevIntegrationTest` and `BootstrapHrSeederDevIntegrationTest` prove this behavior.
- Email provider selection is mutually exclusive:
  - `prod,gmail` → `GmailEmailService` (SMTP with a Google App Password).
  - `prod` (without `gmail`) → `BrevoEmailService`.
  - `e2e-capture` → `E2eCaptureEmailService`, unchanged and never Gmail.
  - `dev` → the existing provider (Brevo unless `gmail` is added).

---

## 3. CORS

- Controlled by `evalora.cors.allowed-origins`, bound from `EVALORA_CORS_ALLOWED_ORIGINS`.
- Default: `http://localhost:5173` (local Vite).
- Multiple origins are comma-separated: `https://app.example.com,https://admin.example.com`.
- `*` and wildcard patterns such as `https://*.example.com` are rejected at startup.
- Credentials are disabled; the JWT travels in the `Authorization` header, not a cookie.

---

### 3.1 Activation links and testing from a phone (LAN)

Activation emails contain a link built from `EVALORA_FRONTEND_URL`, not from the
request host. The value must therefore be an origin the **recipient's device**
can reach:

- **Same laptop** — the default `http://localhost:5173` is fine.
- **Phone on the same Wi-Fi** — `localhost` resolves to the phone itself and will
  fail. Set the laptop's LAN IPv4 address instead, and allow the same origin in
  CORS:
  ```dotenv
  EVALORA_FRONTEND_URL=http://192.168.x.x:5173
  EVALORA_CORS_ALLOWED_ORIGINS=http://192.168.x.x:5173
  ```
  Find the address with `ipconfig` (the IPv4 address of the active Wi-Fi adapter).
  Start Vite with `npm run dev -- --host` so it listens on the LAN, not only on
  loopback. When using Vite rather than the Compose/Nginx frontend, configure
  `VITE_API_BASE_URL=http://<laptop-ipv4>:8080/api` before starting Vite, and
  ensure the backend port is reachable on the laptop. The Compose/Nginx
  frontend uses its same-origin `/api` proxy instead.
- **Windows Firewall** — the first time Vite/Node listens on a LAN address,
  Windows may block inbound connections. Allow Node.js on **Private networks**
  (Windows Defender Firewall → Allow an app through firewall), or add an inbound
  rule for TCP 5173. Never open the port to public networks for this.
- The CORS origin and the activation-link origin must be identical; otherwise the
  page loads but its API calls are rejected.
- Use the frontend at the origin root. The link builder preserves a path prefix
  in generated URLs, but the current React router and Nginx configuration are
  root-mounted and do not support hosting Evalora under a context path.

### 3.2 Public deployment

For real users, set both values to the public HTTPS frontend origin, for example
`https://app.example.com`. Never ship `localhost` or a LAN address to production,
because recipients on other networks cannot reach it. Keep the frontend and API
on HTTPS; the activation token travels in the query string and must not be sent
over plain HTTP.

### 3.3 Email deliverability (SPF, DKIM, DMARC)

Activation and password-reset emails are sent by the configured provider. To keep
them out of spam and make links trustworthy:

- **SPF** — publish a TXT record on the sending domain that authorizes the
  provider (e.g. `v=spf1 include:_spf.google.com ~all` for Gmail, or the Brevo
  include). Only one SPF record per domain.
- **DKIM** — enable signing in the provider and publish the DKIM TXT record it
  generates under `selector._domainkey`.
- **DMARC** — start with `v=DMARC1; p=none; rua=mailto:dmarc@example.com` to
  collect reports, then move to `quarantine`/`reject` once alignment is verified.
- The `EVALORA_MAIL_FROM_ADDRESS` domain must match the authenticated sender and
  the SPF/DKIM domain, or deliverability suffers.

---

## 4. Port binding

`server.port=${PORT:8080}`:

- Locally the app binds to `8080`.
- On Render (or any host that injects `PORT`) it binds to the injected value.
- Do not hardcode a platform port in a Dockerfile or manifest.

---

## 5. Flyway migrations

- Canonical SQL lives in `database/migrations` (`V1`–`V7`).
- The Maven build copies `database/migrations/*.sql` to `db/migration` inside the jar.
- Local default: `filesystem:../database/migrations` (run from `backend/`).
- Container/Render: set `MIGRATIONS_LOCATION=classpath:db/migration` to use the packaged copy, or mount an external
  directory and point at `filesystem:/abs/path`.
- `spring.flyway.fail-on-missing-locations=true` makes a missing location a startup failure instead of a silent skip.
- `spring.jpa.hibernate.ddl-auto=validate` ensures Hibernate never changes the schema.
- Do not edit or rename an applied migration; add a new version.

---

## 6. Docker requirements

The repository Dockerfiles follow these rules:

1. Use a Java **21** base image (`eclipse-temurin:21-jre`).
2. Build with the Maven wrapper and JDK 21.
3. Use the packaged migrations (`MIGRATIONS_LOCATION=classpath:db/migration`) or copy `database/migrations` into the
   image and set an absolute `filesystem:` location.
4. Expose the port from `PORT`/`8080`; do not hardcode Render's port.
5. Run as a non-root user.
6. Pass secrets at runtime (`DB_*`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE`, `EVALORA_CORS_ALLOWED_ORIGINS`).
7. Never `COPY .env`.
8. Add a `.dockerignore` for `.env`, `.env.*`, `.git`, `node_modules`, `target`, `dist`, and editor folders.
9. Add a health check (Actuator `/actuator/health` is available).

---

## 7. Jenkins / CI requirements

1. JDK **21** (`JAVA_HOME` must not point at 17).
2. Node **20.19+**, **22.12+**, or newer.
3. Backend: `cd backend && ./mvnw -B clean verify` (or `package`).
4. Frontend: `cd frontend && npm ci && npm run lint && npm test && npm run build`.
5. Inject `VITE_API_BASE_URL` at build time.
6. Store `DB_*`, `JWT_SECRET`, and deploy tokens in credentials; never echo them.
7. Do not run production migrations from pull-request builds.
8. Archive the backend jar, frontend `dist/`, and test reports.

---

## 8. Render requirements

1. Backend web service on Java 21 or the Docker image.
2. Bind to Render's `PORT` (already supported by `server.port=${PORT:8080}`).
3. Provision Render PostgreSQL and set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` from its connection details.
4. Set a fresh `JWT_SECRET`.
5. Set `SPRING_PROFILES_ACTIVE=prod`.
6. Set `MIGRATIONS_LOCATION=classpath:db/migration` (packaged copy) or point at a mounted migrations folder.
7. Set `EVALORA_CORS_ALLOWED_ORIGINS` to the deployed frontend URL.
8. Deploy `frontend/dist` as a static site; build it with `VITE_API_BASE_URL` set to the backend's public URL.
9. Health check: `/actuator/health`.

---

## 9. Secret protection

- `.gitignore` ignores `.env`, `.env.*`, and `*.env`, while explicitly keeping `.env.example` visible.
- The local `.env` contains real values and must never be committed.
- `.env.example` contains placeholders only.
- Test properties use an in-memory H2 database and a clearly labelled test-only JWT secret.
- Before the first commit, run `git status --ignored` and confirm `.env` is not staged.
