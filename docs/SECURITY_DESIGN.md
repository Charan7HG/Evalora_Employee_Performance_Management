# Evalora — Security Design

**Phase 3J — HR Account Setup, Staff, KPI, Assessments, Result Publication & Dashboards** · Spring Boot 4.1.2 · Spring Security 7 · Java 21

This document describes the security layer that already exists: stateless JWT authentication, login, failed-login
tracking, account lockout, current-user retrieval, stateless logout, change / forgot / reset password, HR-only
staff, KPI and performance-cycle management, employee self-assessment, manager assessment, HR assessment and final
score generation, HR result publication, role-scoped published-result viewing, and read-only role-specific dashboards. It deliberately contains **no
public registration, no HR-creation API, no ADMIN role, or email sending**.

---

## 1. Scope of this phase

| Implemented | Not implemented (later phases) |
|---|---|
| Stateless `SecurityFilterChain` | Public registration |
| BCrypt strength 12 encoder | HR account creation through an API |
| JWT creation and parsing (HS256) | `ADMIN` role |
| JWT authentication filter | JWT refresh tokens |
| JSON 401 / 403 responses | Token blacklist / revocation table |
| CORS for `http://localhost:5173` | Frontend, email sending |
| Email normalisation utility | SMTP / provider SDK |
| `POST /api/auth/login` | |
| Five-attempt / five-minute account lockout | |
| `GET /api/auth/me` | |
| `POST /api/auth/logout` (stateless) | |
| `PUT /api/auth/change-password` | |
| `POST /api/auth/forgot-password` | |
| `POST /api/auth/reset-password` | |
| HR-only `/api/hr/**` staff management | Frontend integration |
| HR-only `/api/hr/kpis` KPI management | |
| HR-only `/api/hr/cycles` cycle, roster and publication management | |
| HR-only `/api/hr/assessments` HR review and finalization | |
| HR-only `/api/hr/results` queue and individual/bulk publication | |
| HR-only `/api/hr/dashboard` organization counts | |
| Final score/result generation at HR submission (DRAFT only) | |
| Employee-only `/api/employee/**` self-assessment | |
| Employee-only published own results | |
| Employee-only `/api/employee/dashboard` own cycle and published result summary | |
| Manager-only `/api/manager/**` manager assessment | |
| Manager-only published results for assignment-snapshot reports | |
| Manager-only `/api/manager/dashboard` current team and historical workload | |
| Dev-only HR bootstrap seeder | |

Every endpoint that is not explicitly public requires authentication. The public endpoints are
`POST /api/auth/login`, `POST /api/auth/forgot-password` and `POST /api/auth/reset-password`. `/api/hr/**` additionally
requires `ROLE_HR`, `/api/employee/**` requires `ROLE_EMPLOYEE`, and `/api/manager/**` requires `ROLE_MANAGER`. There
is no public registration and no in-memory or default user.

---

## 2. Components

All classes live in `com.evalora.backend.security` unless stated otherwise.

| Class | Responsibility |
|---|---|
| `SecurityConfig` | The `SecurityFilterChain`, `PasswordEncoder`, CORS and the two error handlers |
| `JwtProperties` | Binds `evalora.jwt.secret` and `evalora.jwt.expiration-ms` |
| `JwtService` | Creates tokens, verifies signature/expiry, reads claims; validates the secret at startup |
| `JwtAuthenticationFilter` | Turns a `Bearer` token into a database-backed `Authentication` |
| `EvaloraPrincipal` | The authenticated identity (`UserDetails`) built from the `users` row |
| `RestAuthenticationEntryPoint` | Writes the JSON 401 body |
| `RestAccessDeniedHandler` | Writes the JSON 403 body |
| `SecurityErrorWriter` | One place that serialises the error shape |
| `SecurityErrorResponse` | The error response record |
| `com.evalora.backend.controller.ManagerAssessmentController` | Manager assessment HTTP contract and error mapping |
| `com.evalora.backend.service.ManagerAssessmentService` | Ownership-scoped manager queue, details, drafts and submission |
| `com.evalora.backend.controller.HrAssessmentController` | HR assessment HTTP contract and error mapping |
| `com.evalora.backend.service.HrAssessmentService` | Role-scoped HR queue, details, drafts, finalization and result generation |
| `com.evalora.backend.controller.HrDashboardController`, `ManagerDashboardController`, `EmployeeDashboardController` | Role-protected dashboard HTTP contracts |
| `com.evalora.backend.service.DashboardService` | Read-only aggregation using principal-derived user identity |
| `com.evalora.backend.service.PerformanceScoreCalculator` | Pure `BigDecimal` KPI, overall and grade arithmetic |
| `com.evalora.backend.util.EmailNormalizer` | `trim()` + `toLowerCase(Locale.ROOT)` |
| `com.evalora.backend.controller.AuthController` | Login, current-user and logout HTTP mapping |
| `com.evalora.backend.service.AuthService` | Credential check, failed-attempt tracking, lockout, token creation |
| `LoginResult` / `LoginFailure` (service) | Explicit login outcome; failures are returned, not thrown |
| `LoginRequest` / `LoginResponse` / `AuthUserResponse` / `LogoutResponse` (dto) | Safe request and response contracts |
| `UserRepository.findByEmailForUpdate` | Case-insensitive lookup with `PESSIMISTIC_WRITE` for login |

No second user model, no duplicate user table, no client-supplied roles. The controller never returns an entity:
responses are DTO records that contain no password hash, counter or lock fields.

---

## 3. Security architecture

```
Browser / API client
        │  Authorization: Bearer <jwt>
        ▼
CorsFilter ── allowed origin only: http://localhost:5173
        ▼
SecurityFilterChain (stateless, CSRF off, no form login, no HTTP Basic)
        ▼
JwtAuthenticationFilter
        │  no token  ──────────────────────────────► continue (anonymous)
        │  invalid / expired / unknown user ───────► clear context + reason
        │  disabled / locked ──────────────────────► clear context + reason
        │  valid + usable ─────────────────────────► EvaloraPrincipal in context
        ▼
AuthorizationFilter ── public endpoint? ── no token ──► 401 JSON
        │                                            authenticated but no right ──► 403 JSON
        ▼
Controller → Service → Repository
```

Configuration summary:

| Setting | Value | Why |
|---|---|---|
| Sessions | `STATELESS` | The JWT is the whole session |
| Request cache | disabled | Nothing to replay after login |
| CSRF | disabled | The token is sent in a header, not a cookie |
| HTTP Basic | disabled | No password on every request |
| Form login / logout endpoint | disabled | No server-rendered login |
| Default rule | `anyRequest().authenticated()` | Fail closed |
| Public business endpoint | `POST /api/auth/login` only | The one way to obtain a token |
| Role path rules | `/api/hr/**` → `ROLE_HR`; `/api/employee/**` → `ROLE_EMPLOYEE`; `/api/manager/**` → `ROLE_MANAGER` | Roles are resolved from the authenticated database-backed principal |
| Public infrastructure endpoints | `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/error` | Local API documentation and error dispatch |
| Protected authentication endpoints | `GET /api/auth/me`, `POST /api/auth/logout` | They need the token they operate on |
| Preflight | `OPTIONS /**` permitted | CORS preflight never needs a token |

Login flow:

```
POST /api/auth/login
        ▼
AuthController  ── blank field ──────────────────────► 400 BAD_REQUEST
        ▼
AuthService (@Transactional)
        │  normalise email
        │  findByEmailForUpdate (row lock)
        │  unknown / disabled ──────────────────────► 401 generic
        │  active lock ─────────────────────────────► 423 ACCOUNT_LOCKED
        │  expired lock ── clear counter + lock
        │  BCrypt mismatch ── increment (+ lock) ───► 401 generic
        │  BCrypt match ── reset, last_login_at, JWT
        ▼
200 { token, tokenType, expiresIn, user }
```

---

## 4. JWT

### Algorithm and key

- **HS256** (HMAC-SHA256) via `io.jsonwebtoken` (jjwt) 0.13.0.
- The key is the UTF-8 bytes of `JWT_SECRET`. HS256 requires at least 256 bits, so **32 bytes minimum**.
- The secret is read from the environment only. `application.properties` contains `${JWT_SECRET:}` — no default
  value, no fallback secret, no secret in source control.
- `JwtService` validates the secret in its constructor. A missing, blank or too-short secret stops the application
  at startup with:

  ```
  JWT secret is not configured. Set the JWT_SECRET environment variable ...
  JWT_SECRET is too short (12 bytes). HS256 requires at least 32 bytes ...
  ```

Fail-fast is intentional: an application that silently starts with a weak key is worse than one that refuses to
start.

### Claims

| Claim | Example | Purpose |
|---|---|---|
| `sub` | `employee@evalora.local` | Normalised email — the only identity used for lookup |
| `iat` | `2026-10-30T07:15:00Z` | Issued at |
| `exp` | `2026-10-30T15:15:00Z` | Expiration, 8 hours after `iat` |
| `role` | `EMPLOYEE` | **Informational only** |

There is no user id, no password, no personal data and no permission list in the token.

Expiration defaults to `28_800_000` ms (8 hours) and is configurable with `JWT_EXPIRATION` /
`evalora.jwt.expiration-ms`. The value lives in one property, never duplicated in code.

### Signature and expiration validation

`JwtService.parseClaims` uses `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`, which checks the
signature first and then the `exp` claim. It throws:

| Situation | Exception |
|---|---|
| Malformed token | `MalformedJwtException` (a `JwtException`) |
| Expired token | `ExpiredJwtException` |
| Signature from another key | `SignatureException` / `SecurityException` (`JwtException`) |
| `null` or empty token | `IllegalArgumentException` |

All of them are caught in the filter and turned into a generic 401 — the client never sees which check failed.

---

## 5. How a request is authenticated

`JwtAuthenticationFilter` (a `OncePerRequestFilter` registered **only** inside the security chain):

1. Reads the `Authorization` header. Without a `Bearer ` prefix the chain continues anonymously.
2. Parses and verifies the token.
3. Normalises the subject email and loads the `users` row with `findByEmailIgnoreCase`.
4. Rejects the request when:
   - no such user exists,
   - `enabled = false`,
   - `locked_until` is in the future.
5. Otherwise builds an `EvaloraPrincipal` **from the database row** — id, email, current role, enabled, lock state —
   and stores a `UsernamePasswordAuthenticationToken` in the `SecurityContext`.

Consequences:

- **The role claim is never trusted.** It is written for diagnostics only. If HR changes a role, or an account is
  disabled, the change applies to the very next request — no token reissue needed for authorization.
- A token for a deleted or unknown account authenticates nobody.
- **No "partially authenticated" state exists**: either the principal is in the context or it is not.
- No password hash is ever placed in the principal or the context.
- The raw token is never logged.
- A missing token is not an error inside the filter; it is the entry point that returns 401 for protected paths.

### Disabled and locked accounts

| State | Result |
|---|---|
| `enabled = false` | 401, `Account is disabled or locked.` |
| `locked_until` in the future | 401, `Account is disabled or locked.` |
| `locked_until` in the past | Authenticates normally |

The filter only *honours* the existing `failed_login_attempts` / `locked_until` columns. Writing the lockout state
(five failures → five-minute lock) happens in `AuthService`; see section 11. This means an already-issued token stops
working while the account is locked, without any token revocation list.

---

## 6. Password hashing

- `SecurityConfig.passwordEncoder()` returns `BCryptPasswordEncoder(12)`.
- Strength 12 is the single cost factor for the whole application; it is not configurable per call site.
- Only hashes are stored (`users.password_hash`, `VARCHAR(100)`). The plain password never leaves the request DTO.
- Passwords and hashes are never logged.
- The encoder is injectable, so `AuthService` and the future account-creation service use exactly this bean for both
  hashing and verification (`passwordEncoder.matches`).

BCrypt hashes carry the cost factor: a strength-12 hash starts with `$2a$12$`, which the tests assert.

### Password policy

One rule set, implemented once in `util/PasswordPolicy` and used by both change-password and reset-password:

| Rule | Value |
|---|---|
| Minimum length | 8 characters |
| Alphabetic character | at least one `[A-Za-z]` |
| Numeric character | at least one `[0-9]` |
| Special character | at least one non-alphanumeric, non-whitespace character |

The failure message is deliberately identical for every violated rule, so a caller cannot use the response to
enumerate which part of a password is missing. `changePassword` additionally rejects a new password that matches the
stored one (`passwordEncoder.matches`), because BCrypt salts make plain string comparison unreliable.

---

## 7. Email normalisation

The contract, implemented once in `EmailNormalizer.normalize`:

```java
email.trim().toLowerCase(Locale.ROOT)
```

- Used by login before the lookup (`AuthService`), when creating a token (`JwtService`), when reading its subject
  (`JwtAuthenticationFilter`) and by forgot-password before the account lookup (`PasswordService`).
- Every future flow that accepts an email — account creation, password reset — must call the same utility.
- Login looks the user up with `findByEmailForUpdate`, a JPQL `lower(u.email) = :email` query, as a second safety net.
- The email in the login response is the stored email, not the raw client input.
- The PostgreSQL functional unique index `ux_users_email_lower` remains the last line of defence. The JPA entity
  intentionally does not declare an email uniqueness constraint (see `DATABASE_DESIGN.md`).

---

## 8. CORS

| Setting | Value |
|---|---|
| Allowed origin | `http://localhost:5173` only |
| Allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed headers | `*` |
| Exposed headers | `Authorization` |
| Credentials | `false` — the JWT is sent in a header, never a cookie |
| Preflight cache | 3600 s |

Requests from any other origin are rejected with **403** and no `Access-Control-Allow-Origin` header. Preflight
requests are permitted in the security chain so they reach the CORS filter.

If the frontend port changes, update `SecurityConfig.FRONTEND_ORIGIN` — it is a single constant used by both the
configuration and the tests.

---

## 9. Error responses

The security handlers and the login controller emit the same JSON shape:

```json
{
  "timestamp": "2026-10-30T07:15:00.123456Z",
  "status": 401,
  "error": "UNAUTHORIZED",
  "message": "Invalid email or password.",
  "path": "/api/auth/login"
}
```

| Situation | Status | `error` | `message` |
|---|---|---|---|
| Blank login email | 400 | `BAD_REQUEST` | `Email is required.` |
| Blank login password | 400 | `BAD_REQUEST` | `Password is required.` |
| Unknown email, wrong password, disabled account | 401 | `UNAUTHORIZED` | `Invalid email or password.` |
| Actively locked account | 423 | `ACCOUNT_LOCKED` | `Account is temporarily locked. Please try again later.` |
| No token / anonymous on a protected path | 401 | `UNAUTHORIZED` | `Authentication is required to access this resource.` |
| Malformed, expired, foreign signature, unknown user | 401 | `UNAUTHORIZED` | `Invalid or expired token.` |
| Valid token, disabled or locked account | 401 | `UNAUTHORIZED` | `Account is disabled or locked.` |
| Authenticated but not allowed | 403 | `FORBIDDEN` | `You do not have permission to access this resource.` |

Unknown email, wrong password and disabled account are intentionally indistinguishable: same status, same `error`
and same message. An active lock is the only state that gets its own response, because the client needs to know to
wait. A disabled account is checked before the lock and the password, so it never leaks extra account information.

No stack traces, exception class names, token fragments, SQL or secret material are ever returned. The real
exception is not logged with the token either.

---

## 10. Login endpoint

`POST /api/auth/login` is the only public business endpoint. It returns a token for any of the three roles; there is
no role field in the request and no role-specific login URL.

Request:

```json
{
  "email": "employee@evalora.local",
  "password": "<the user's password>"
}
```

Success — HTTP 200:

```json
{
  "token": "<jwt-token>",
  "tokenType": "Bearer",
  "expiresIn": 28800,
  "user": {
    "id": 1,
    "email": "employee@evalora.local",
    "role": "EMPLOYEE"
  }
}
```

- `expiresIn` is in **seconds** and is derived from `evalora.jwt.expiration-ms / 1000`. It is never hardcoded.
- `user` is `AuthUserResponse`: id, email and role only. No hash, counter, lock timestamp or last-login timestamp.
- `tokenType` is always `Bearer`; the client sends `Authorization: Bearer <token>` afterwards.
- Blank or missing fields are rejected by Bean Validation with HTTP 400 before the service is called.

Processing order in `AuthService.login` (one `@Transactional` call):

1. Normalise the email with `EmailNormalizer.normalize`.
2. Load the account with `UserRepository.findByEmailForUpdate` (`PESSIMISTIC_WRITE`).
3. Unknown email or `enabled = false` → generic 401. The password is not checked.
4. `locked_until` in the future → 423. The counter is not changed and the password is not checked.
5. `locked_until` in the past → clear the expired lock and the old counter before continuing.
6. Verify the password with the shared BCrypt `PasswordEncoder`.
7. Wrong password → register one failure, commit, return the generic 401.
8. Correct password → reset the counter, clear the lock, set `last_login_at`, generate the JWT and return 200.

`AuthService` returns a `LoginResult` instead of throwing on bad credentials on purpose: a runtime exception inside
the transaction would roll back the failed-attempt increment. Returning normally lets the counter commit while the
controller still answers with 401.

---

## 11. Failed-login tracking and account lockout

The existing `users` columns are used as-is; no schema change was needed.

| Rule | Implementation |
|---|---|
| Maximum failed attempts | `AuthService.MAX_FAILED_ATTEMPTS = 5` |
| Lock duration | `AuthService.LOCK_DURATION = Duration.ofMinutes(5)` |
| Wrong password | `failed_login_attempts = failed_login_attempts + 1` |
| Fifth failure | counter reaches 5 **and** `locked_until = now + 5 minutes` |
| Attempt while actively locked | 423; counter and `locked_until` unchanged |
| Attempt after the lock expired | allowed; old counter and lock are cleared first |
| Wrong password after lock expiry | starts a new sequence at 1 |
| Successful login | counter = 0, `locked_until = null`, `last_login_at = now` |
| Concurrent attempts | the user row is locked with `PESSIMISTIC_WRITE` for the whole attempt |

The row lock is what prevents two simultaneous requests from reading the same counter and overwriting each other.
`findByEmailForUpdate` is a JPQL `lower(u.email) = :email` lookup with `@Lock(LockModeType.PESSIMISTIC_WRITE)` and
must run inside the service transaction. The ordinary `findByEmailIgnoreCase` is unchanged and is still used by the
JWT filter, which only reads.

The lock is time-based, not permanent: when `locked_until` has passed the next login is evaluated normally. An
account that is already locked is also rejected by the JWT filter, so existing tokens cannot be used during the
lock.

---

## 12. Current user and stateless logout

### `GET /api/auth/me`

Requires a valid `Authorization: Bearer <token>` header. Returns:

```json
{
  "id": 1,
  "email": "employee@evalora.local",
  "role": "EMPLOYEE"
}
```

- The body comes entirely from `EvaloraPrincipal` in the `SecurityContext`, which the JWT filter built from the
  current `users` row.
- There is no id parameter. A client-supplied `?id=` (or body) is ignored, so one user cannot read another user's
  account through this endpoint.
- The role is the role stored in the database at request time, not the `role` claim in the token. A role change takes
  effect on the next request, with the same token.
- No password hash, counter, lock field or token is included.
- No token means the entry point returns the standard JSON 401.

### `POST /api/auth/logout`

Requires authentication and returns HTTP 200:

```json
{
  "message": "Logged out successfully."
}
```

Evalora is stateless, so logout is a confirmation, not a server-side state change:

- No HTTP session is created (`SessionCreationPolicy.STATELESS`).
- No token blacklist, denylist, revocation table or refresh token exists.
- The JWT remains cryptographically valid until its `exp` claim.
- **The client must discard the token.** Until it does, or until the token expires, the token still authenticates.
- Disabling or locking the account is the server-side way to stop an already-issued token immediately, because the
  JWT filter re-reads account state on every request.

This is the agreed v1 behaviour. Server-side revocation would require a denylist or short-lived tokens plus refresh
tokens, which are explicitly out of scope.

---

## 13. Password management (Phase 3B3)

`PasswordService` owns all three flows. `AuthController` only validates the request body and maps a
`PasswordOperationResult` to HTTP.

| Endpoint | Auth | Request | Success |
|---|---|---|---|
| `PUT /api/auth/change-password` | Bearer token | `currentPassword`, `newPassword`, `confirmPassword` | 200 `Password changed successfully.` |
| `POST /api/auth/forgot-password` | public | `email` | 200 generic message |
| `POST /api/auth/reset-password` | public | `token`, `newPassword`, `confirmPassword` | 200 `Password reset successfully.` |

### Change password

- The account is taken **only** from `EvaloraPrincipal` in the `SecurityContext`. The DTO has no id or email field, so
  the endpoint cannot be pointed at another account.
- Confirmation mismatch, policy violation, incorrect current password and password reuse all return HTTP 400 with a
  safe message. The current password is verified with `passwordEncoder.matches`, never by comparing strings.
- A password change does **not** clear failed-login counters or an active lock.
- Existing JWTs stay valid — see section 18.

### Forgot password and token storage

- The email is normalised with `EmailNormalizer` before the lookup.
- Unknown, disabled and ineligible accounts return the **same** body and status as an eligible one:
  `"If an account with that email exists, password reset instructions have been made available."` No 404, no
  distinction in the message, no token is created.
- Tokens come from `PasswordResetTokenUtil.generate()`: 32 bytes from `SecureRandom`, rendered as 64 lowercase hex
  characters (256 bits). No timestamp, user id or `java.util.Random` is involved.
- Only the **SHA-256 hex hash** is stored in `password_reset_tokens.token_hash`. The raw token is never persisted.
- Lifetime is 15 minutes (`RESET_TOKEN_LIFETIME`); `expires_at` is stored in UTC.
- Issuing a new token marks every other unused token of that user as used in the same transaction, so only the newest
  token is valid.
- No email, SMTP or provider call exists.

### Development-only token exposure

- `PasswordService` reads `Environment.acceptsProfiles(Profiles.of("dev"))` once at construction.
- With the `dev` profile active, `forgot-password` adds `resetToken` to the response
  (`ForgotPasswordDevResponse`). Without it, the response type is `ForgotPasswordResponse`, which has **no token
  field at all** — the value is omitted, not merely blanked.
- The raw token is never written to the database and never logged. The production/default configuration is unchanged;
  `dev` must be activated explicitly (`--spring.profiles.active=dev` or `SPRING_PROFILES_ACTIVE=dev`).

### Reset password

Order of checks: confirmation match → policy → token hash lookup → account enabled → token not used → token not
expired. All failures return the same `"Invalid or expired reset token."` with HTTP 400, so a caller cannot tell an
unknown token from an expired or consumed one.

The password update, the `used_at` mark and the invalidation of the user's other tokens happen in **one**
`@Transactional` method. If any step fails, none of it is committed.

### Concurrency model

| Flow | Locks, in acquisition order |
|---|---|
| Change password | `users` row (`findByIdForUpdate`) |
| Forgot password | `users` row (`findByEmailForUpdate`) → token inserts |
| Reset password | `users` row (`findByIdForUpdate`) → `password_reset_tokens` row (`findByTokenHashForUpdate`) |

- Every password path takes the user row first, so the flows cannot deadlock against each other.
- Reset first reads the owner id with the scalar `findUserIdByTokenHash` (no entity in the persistence context), then
  locks the user, then locks the token. The second of two concurrent resets therefore waits on the user row and then
  sees `used_at` already set, so it fails instead of consuming the token twice.
- `invalidateAllUnusedForUser` / `invalidateOtherUnusedForUser` are bulk JPQL updates inside the transaction.

### Responses and status codes

| Situation | Status |
|---|---|
| Successful change / reset | 200 |
| Blank field, confirmation mismatch, weak password, reused password, wrong current password, invalid token | 400 |
| Change password without a token | 401 (standard JSON entry point) |
| Forgot password, any email | 200, identical body |

No response ever contains a password, a hash, a stored token hash or an entity.

---

## 14. HR account setup and staff management (Phase 3B4)

### Authorization

```java
.requestMatchers("/api/hr/**").hasRole("HR")
```

`hasRole("HR")` matches the `ROLE_HR` authority created from the database `users.role` for the current request. The
role is never read from the JWT claim or from the request body. The rest of the chain is unchanged: auth endpoints stay
public exactly as before, and `anyRequest().authenticated()` still covers everything else.

| Caller | `/api/hr/**` |
|---|---|
| No/invalid token | 401 |
| EMPLOYEE | 403 |
| MANAGER | 403 |
| HR | Allowed |

There is no `ADMIN` role and no endpoint that creates an HR account. HR is created only by the development seeder
below, or directly in the database by an operator.

### Development-only HR bootstrap

`BootstrapHrSeeder` is a `@Profile("dev")` `ApplicationRunner`; outside `dev` the bean does not exist, so it cannot
run in any other profile.

| Environment variable | Required | Purpose |
|---|---|---|
| `EVALORA_BOOTSTRAP_HR_EMAIL` | together with the password | Initial HR email |
| `EVALORA_BOOTSTRAP_HR_PASSWORD` | together with the email | Initial HR password |
| `EVALORA_BOOTSTRAP_HR_EMPLOYEE_CODE` | no | Profile code, default `HR-001` |
| `EVALORA_BOOTSTRAP_HR_FULL_NAME` | no | Profile name, default `HR Administrator` |

- No default email or password exists.
- Both credentials missing: a safe warning is logged and no account is created.
- Only one of the two supplied: startup fails with a message naming the missing variable.
- Password is checked against the Phase 3B3 policy, then BCrypt-hashed with the shared encoder.
- Email is normalised with `EmailNormalizer`.
- `User` and `Employee` are created in one `@Transactional` run.
- If any HR account already exists, the seeder does nothing (idempotent).
- If the configured email belongs to a non-HR account, startup fails rather than creating a duplicate.
- The password is never logged or returned.

### Account creation

| Endpoint | Role created |
|---|---|
| `POST /api/hr/employees` | `EMPLOYEE` |
| `POST /api/hr/managers` | `MANAGER` |

- The role is chosen by the endpoint; `CreateStaffRequest` has no role field.
- Email is trimmed/normalised once with `EmailNormalizer`; uniqueness is case-insensitive (service pre-check plus the
  database functional unique index).
- `employee_code` is unique case-insensitively: the service checks first, and the V3 functional unique index
  (`ux_employees_code_lower` on `LOWER(employee_code)`) closes the concurrent-insert gap while V1's case-sensitive
  constraint remains in place. V3 refuses to run over pre-existing case-insensitive duplicates and reports them.
- Initial password must satisfy the Phase 3B3 policy and is BCrypt-hashed with the shared strength-12 encoder.
- `User` + `Employee` (+ optional reporting assignment) are written in one transaction. A failure rolls all of it back;
  this is verified by `EmployeeCreationRollbackIntegrationTest`.
- A manager request with a `managerId` is rejected; managers have no manager in v1.
- An employee may be created with a valid, active `MANAGER` profile. The profile pointer and the first history row are
  created together.
- The manager row is locked with the same pessimistic write lock used by reassignment and manager deactivation before
  its role/status/account state is checked, and the lock is held until the creation transaction commits. Creation and
  deactivation therefore serialise: if deactivation commits first, creation rejects the inactive manager and rolls
  back; if creation commits first, deactivation sees the new active report and returns 409. This is verified by
  `ManagerAssignmentConcurrencyIntegrationTest`.

### Listing and profile updates

- `GET /api/hr/employees` and `GET /api/hr/managers` return only the requested role. The optional `status` parameter
  defaults to `ACTIVE`; `INACTIVE` is available for deactivated records.
- `GET .../{id}` and the update endpoints return 404 when the id does not exist **or belongs to the other role**, so an
  id cannot be probed across roles.
- `PUT` replaces profile fields only: name, email, employee code, department, designation, joining date. Role, status,
  password, enabled flag, lockout state and manager are not writable here, and unknown JSON fields are ignored.
- Email updates are normalised and uniqueness-checked; a conflict returns 409.
- Updates never touch assessment, result or reporting-history rows.

### Manager assignment history

V1 stored only `employees.manager_id`, so a reassignment would have destroyed the previous reporting line. Phase 3B4
adds `V2__employee_manager_assignments.sql` and `EmployeeManagerAssignment`:

- `effective_from` / `effective_to`; a null `effective_to` is the current assignment.
- A PostgreSQL partial unique index (`uq_ema_active_employee`) permits at most one open row per employee.
- `PUT /api/hr/employees/{id}/manager` validates: employee exists and is `EMPLOYEE`; employee is active; manager exists
  and is `MANAGER`; manager is active; ids differ (self-assignment rejected); HR and manager accounts cannot be targets.
- The service locks the employee row, closes the open row by setting `effective_to`, flushes that update, then inserts
  the new open row. The flush matters because Hibernate writes inserts before updates and the partial unique index
  would otherwise see two open rows.
- The previous row is never deleted or overwritten, and `employees.manager_id` is updated to the new current manager.
- No hard deletes exist anywhere in this module.

### Deactivation and reactivation

| Operation | Effect |
|---|---|
| `PATCH /api/hr/employees/{id}/deactivate` | `status = INACTIVE`, `user.enabled = false`, open reporting row closed, `manager_id = null` |
| `PATCH /api/hr/employees/{id}/reactivate` | `status = ACTIVE`, `user.enabled = true`; no manager restored |
| `PATCH /api/hr/managers/{id}/deactivate` | 409 while active employees still report to the manager; otherwise `status = INACTIVE`, account disabled |
| `PATCH /api/hr/managers/{id}/reactivate` | `status = ACTIVE`, account enabled; no assignments recreated |

- Deactivation is a soft state change; no row is deleted, so assessments and published results survive.
- Reactivation deliberately does not clear `failed_login_attempts` or `locked_until`, and does not replay reporting
  history.
- Repeated deactivate/reactivate returns 409 rather than silently succeeding.

### Errors

| Situation | Status |
|---|---|
| Created | 201 |
| Read / update / state change | 200 |
| Invalid body, invalid manager role, self-assignment, weak password | 400 |
| Missing/invalid token | 401 |
| Non-HR caller | 403 |
| Unknown id or wrong role | 404 |
| Duplicate email/code, inactive target, manager with active reports, repeated state change, DB uniqueness race | 409 |

All error bodies use the same `SecurityErrorResponse` shape as the rest of the application. Database messages and stack
traces are never returned.

### KPI management (Phase 3C)

The KPI catalogue reuses the same `/api/hr/**` rule, so it adds no new authorization mechanism:

| Method | Path | HR | MANAGER / EMPLOYEE | No token |
|---|---|---|---|---|
| `POST` | `/api/hr/kpis` | 201 | 403 | 401 |
| `GET` | `/api/hr/kpis`, `/api/hr/kpis/{id}` | 200 | 403 | 401 |
| `PUT` | `/api/hr/kpis/{id}` | 200 | 403 | 401 |
| `PATCH` | `/api/hr/kpis/{id}/deactivate`, `.../reactivate` | 200 | 403 | 401 |

- The controller takes no role, active flag, creator or id from the body. `CreateKpiRequest` and `UpdateKpiRequest` only
  contain `name`, `description`, `category` and `defaultWeightage`; `created_by` comes from
  `EvaloraPrincipal.getUserId()` and a new KPI is always active.
- `name` is trimmed, required, at most 150 characters and unique case-insensitively. The service pre-checks, and V1's
  `ux_kpi_master_name_lower` functional unique index closes the concurrent gap. A `DataIntegrityViolationException` on
  flush is translated to `409 A KPI with this name already exists.`, never returned as a database error.
- Updates use `existsByNameIgnoreCaseAndIdNot`, so changing only the letter case of the KPI's own name is allowed while
  colliding with another KPI is still a 409.
- `description` is optional (`TEXT` column), `category` is optional and at most 80 characters, and
  `defaultWeightage` is an optional `BigDecimal` with `0 < w <= 100` and at most 2 decimal places. Values are stored
  with scale 2.
- Deactivate/reactivate only flip `active`; no row is deleted and no `cycle_kpis`, `employee_kpis`, assessment or result
  snapshot is touched. Repeating the same state change returns 409.
- Unknown ids return 404 and invalid bodies/`active` filter values return 400 with a safe message.
- The KPI response contains only catalogue fields plus `createdById`; it exposes no JPA entity, password data or
  relationship graph.

`KpiConcurrencyIntegrationTest` covers a forced database uniqueness failure and two overlapping create transactions
where exactly one KPI survives. H2 enforces only the entity's case-sensitive constraint, so the tests do not claim to
prove the PostgreSQL `LOWER(name)` index; that index is part of the unmodified V1 migration.

### Performance cycles (Phase 3D)

Cycle management reuses the same `/api/hr/**` rule; it adds no role and no authorization mechanism:

| Method | Path | HR | MANAGER / EMPLOYEE | No token |
|---|---|---|---|---|
| `POST` | `/api/hr/cycles` | 201 | 403 | 401 |
| `GET` | `/api/hr/cycles`, `/api/hr/cycles/{id}` | 200 | 403 | 401 |
| `PUT` | `/api/hr/cycles/{id}` | 200 | 403 | 401 |
| `POST` | `/api/hr/cycles/{id}/kpis` | 200 | 403 | 401 |
| `GET` | `/api/hr/cycles/{id}/kpis` | 200 | 403 | 401 |
| `PUT` | `/api/hr/cycles/{id}/employees` | 200 | 403 | 401 |
| `GET` | `/api/hr/cycles/{id}/employees` | 200 | 403 | 401 |
| `POST` | `/api/hr/cycles/{id}/publish` | 200 | 403 | 401 |

- `CycleRequest` contains only name, description and the five dates. `id`, `status`, `createdBy`, `publishedAt`,
  `completedAt` and audit timestamps are not accepted; a new cycle is always `DRAFT` and `created_by` comes from
  `EvaloraPrincipal.getUserId()`.
- Cycle names are trimmed, required, at most 150 characters and unique case-insensitively. The service pre-checks
  and V1's `ux_cycles_name_lower` is the final concurrent guard. A uniqueness violation is translated to 409.
- Date rules are validated in the service as well as being present in V1: `start < end`, self deadline within the
  cycle, manager deadline ≥ self deadline, HR deadline ≥ manager deadline.
- Only `DRAFT` cycles may be updated, configured or published. Every mutation takes a pessimistic write lock on the
  cycle row first, so two HR requests cannot interleave. Published cycles return 409 and are immutable.
- KPI selection is a full replacement of the DRAFT configuration. Only active master KPIs can be selected, each at
  most once, and weights are either all null (average mode) or all present, `0 < w <= 100` with at most 2 decimals
  and a total of exactly `100.00` (`BigDecimal`). The whole request is validated before any row is replaced.
- Employee selection is a full replacement. The service locks employee rows then manager rows in ascending id order,
  re-reads role and `ACTIVE`/`enabled` state from the database, rejects HR/MANAGER as appraised employees, rejects
  self-review and duplicate employees, and never trusts a role from the request.
- An explicit `managerId` changes only the cycle's reviewing-manager snapshot; it never rewrites
  `employee_manager_assignments` or the employee's current reporting line. Omitting it uses the current reporting
  manager.
- Publication locks the cycle and every referenced staff row, re-validates all roles/state and KPI weights, copies
  KPI text and weights into `employee_kpis`, sets assignments to `SELF_ASSESSMENT_PENDING`, and sets the cycle
  `PUBLISHED` with `publishedAt`. It never sets `completedAt`. The whole operation is one transaction and rolls back
  if any snapshot write fails. A second publish returns 409.
- Snapshots are immutable: later edits to `kpi_master` do not change `employee_kpis`, and published cycle
  configuration cannot be edited.
- The cycle, KPI and employee responses are dedicated DTOs; no JPA entity or relationship graph is exposed.

`CyclePublishingRollbackIntegrationTest` injects a snapshot-write failure and proves the cycle stays `DRAFT` with no
snapshots. `CyclePublishingConcurrencyIntegrationTest` proves two simultaneous publishes produce one winner and that a
manager deactivated while publication waits on the row lock is rejected. These tests exercise JPA row locking on H2,
not PostgreSQL-specific index or locking implementations.

### Employee self-assessment (Phase 3E)

The employee module is the first non-HR path rule. It adds no role and no new authorization mechanism:

| Method | Path | EMPLOYEE | HR / MANAGER | No token |
|---|---|---|---|---|
| `GET` | `/api/employee/self-assessments/cycles` | 200 | 403 | 401 |
| `GET` | `/api/employee/self-assessments/{id}` | 200 | 403 | 401 |
| `GET` | `/api/employee/self-assessments/{id}/status` | 200 | 403 | 401 |
| `PUT` | `/api/employee/self-assessments/{id}/draft` | 200 | 403 | 401 |
| `POST` | `/api/employee/self-assessments/{id}/submit` | 200 | 403 | 401 |

- `SecurityConfig` adds exactly one rule, `.requestMatchers("/api/employee/**").hasRole("EMPLOYEE")`. No existing
  matcher was weakened; `/api/hr/**` still requires `ROLE_HR`.
- The employee is resolved from `EvaloraPrincipal.getUserId()` through `employees.user_id`. No endpoint accepts an
  employee id in the path, query or body, so a caller cannot choose whose data to read.
- Every assignment lookup is ownership-scoped in the repository
  (`where a.id = :id and a.employee.id = :employeeId`). Another employee's assignment, an unknown id and an
  invisible-cycle roster row all produce the same `404 Self-assessment not found.`, so the API does not confirm that
  another employee's appraisal exists.
- Visibility is keyed on **exactly `PUBLISHED`**. `DRAFT` roster rows created in Phase 3D, and any later or future
  cycle status (`IN_PROGRESS`, `COMPLETED`, ...), are hidden with the same 404 on all five endpoints and excluded
  from the listing query (`...Cycle_Status...` = `PUBLISHED`). A future phase must widen this deliberately.
- Draft save and submission take a pessimistic write lock on the assignment row **before reading its status**, so the
  two operations serialise per assignment: two submits cannot both observe `SELF_ASSESSMENT_PENDING`, a draft cannot
  be written after a submission commits, and concurrent draft saves cannot create a second row for one KPI.
- `employeeKpiId` is validated against the assignment's own `employee_kpis`; a KPI from another assignment returns
  400. Ratings are validated as integral values from 1 to 5 on the `BigDecimal` JSON value, so `3.5` is rejected
  instead of being silently truncated.
- Draft writes are validate-then-write in one transaction, and each entry upserts; `uq_self_assessment` on
  `employee_kpi_id` is the database backstop. A rejected payload leaves the saved draft untouched.
- Submission is a one-way `SELF_ASSESSMENT_PENDING` -> `MANAGER_REVIEW_PENDING` transition with
  `self_submitted_at`, in one transaction. A second submit or a draft edit afterwards returns 409. Cycle status, KPI
  snapshots, `manager_assessments` and `hr_assessments` are not touched.
- Responses are dedicated DTOs; no JPA entity or relationship graph is exposed. The self-rating contributes 0 % to
  any score and no scoring code exists in this phase.

`EmployeeSelfAssessmentIntegrationTest` covers the HTTP contract, 401/403/404 boundaries and the exactly-`PUBLISHED`
visibility rule; `SelfAssessmentDataIntegrityIntegrationTest` covers one row per KPI, ownership, snapshot stability and
untouched manager/HR tables; `SelfAssessmentConcurrencyIntegrationTest` covers concurrent double submission and
concurrent draft saves, plus two latch-based tests that hold the assignment row lock in a separate transaction to
prove deterministically that a write blocks and that a committed submission wins. These tests exercise JPA row
locking on H2, not PostgreSQL-specific lock or index behaviour.

### Manager assessment (Phase 3F)

| Method | Path | MANAGER | HR / EMPLOYEE | No token |
|---|---|---|---|---|
| `GET` | `/api/manager/assessments` | 200 | 403 | 401 |
| `GET` | `/api/manager/assessments/{id}` | 200 | 403 | 401 |
| `GET` | `/api/manager/assessments/{id}/status` | 200 | 403 | 401 |
| `PUT` | `/api/manager/assessments/{id}/draft` | 200 | 403 | 401 |
| `POST` | `/api/manager/assessments/{id}/submit` | 200 | 403 | 401 |

- `SecurityConfig` adds `/api/manager/**` → `ROLE_MANAGER` without changing the HR or employee rules. The controller
  obtains the manager id only from `EvaloraPrincipal.getUserId()` and the corresponding employee profile.
- Every assignment lookup is scoped by `employee_assignments.manager_id`, the reviewing-manager snapshot selected
  when the cycle roster was configured. It never uses the employee's current `employees.manager_id` relationship.
  Another manager's assignment, an unknown id, an unsubmitted self-assessment, and a cycle that is not exactly
  `PUBLISHED` all return the same 404. The queue includes only the authenticated manager's `MANAGER_REVIEW_PENDING`
  assignments with `self_submitted_at` set.
- Detail responses use dedicated DTOs with basic employee identity, frozen KPI snapshots, submitted self-ratings and
  comments, and saved manager drafts. They do not expose user credentials or accept client-supplied ownership ids.
  Previously submitted manager reviews remain readable while the cycle is published, but are read-only and excluded
  from the pending queue.
- Draft entries are validated completely before writes. KPI ids must belong to the assignment and be unique within
  the request; ratings must be whole values from 1 through 5. Drafts are partial upserts, empty drafts are no-ops,
  comments are trimmed, and omitted KPI values are preserved.
- Draft saves and submission take the same ownership-scoped `PESSIMISTIC_WRITE` lock on the assignment before status
  checks. Submission requires every assigned KPI rating, then atomically writes `manager_submitted_at` and moves
  `MANAGER_REVIEW_PENDING` → `HR_REVIEW_PENDING`. A waiting draft sees the submitted status and returns 409;
  concurrent submissions yield one winner. The V1 unique constraint on `manager_assessments.employee_kpi_id` prevents
  duplicate rows as a database backstop.
- The service writes only `manager_assessments` and the assignment's existing status/timestamp fields. It does not
  write employee self-assessments, HR assessments or performance results, and it performs no weighted/final scoring.

`ManagerAssessmentIntegrationTest` covers endpoint responses, manager/HR/employee/anonymous access, ownership,
historical reviewer stability, visibility, draft validation, submission and immutability.
`ManagerAssessmentDataIntegrityIntegrationTest` covers one row per KPI, the recorded reviewer, historical reviewer
stability after a reporting-line change, and untouched self-assessment/HR/result tables. The latch-coordinated
`ManagerAssessmentConcurrencyIntegrationTest` covers concurrent submissions, concurrent upserts, a draft blocked
behind a successful submission, and two deterministic proofs that draft writes and submissions wait on the assignment
row lock. Like the employee tests, these use H2 in PostgreSQL mode; PostgreSQL-specific row locking and index
behavior are not independently verified.

### HR assessment (Phase 3G)

| Method | Path | HR | MANAGER / EMPLOYEE | No token |
|---|---|---|---|---|
| `GET` | `/api/hr/assessments` | 200 | 403 | 401 |
| `GET` | `/api/hr/assessments/{id}` | 200 | 403 | 401 |
| `GET` | `/api/hr/assessments/{id}/status` | 200 | 403 | 401 |
| `PUT` | `/api/hr/assessments/{id}/draft` | 200 | 403 | 401 |
| `POST` | `/api/hr/assessments/{id}/submit` | 200 | 403 | 401 |

- No security rule was added or weakened: the endpoints live under the existing `/api/hr/**` -> `ROLE_HR` matcher.
  The HR user is resolved only from `EvaloraPrincipal.getUserId()` and stored as `hr_assessments.hr_id`; no endpoint
  accepts an HR, employee or assignment identity that could override it. A service-level role check backs up the
  filter chain, and client-supplied identity fields are ignored.
- HR reviews **all** eligible employees, so unlike Phases 3E/3F there is no ownership predicate. Authorization is
  role-based; visibility is workflow-based. An assignment is visible only while its cycle is exactly `PUBLISHED`,
  `self_submitted_at` and `manager_submitted_at` are both set, and its status is `HR_REVIEW_PENDING` (read-only
  detail also for `COMPLETED` / `RESULTS_PUBLISHED`). DRAFT cycles, every other cycle status, employee- or
  manager-pending assignments, and unknown ids all return the same `404 HR assessment not found.`, so the API does
  not confirm that an invisible appraisal exists. The pending queue additionally excludes rows whose HR review is
  already finalized.
- Detail responses are dedicated DTOs: minimal employee identity (id, code, name, department, designation - no email
  or account data), cycle header, frozen KPI snapshots, and the employee, manager and HR ratings/comments kept
  separate. No computed score is returned.
- `employeeKpiId` is validated against the assignment's own `employee_kpis`; foreign or duplicate ids are rejected
  with 400. Ratings are parsed as `BigDecimal` and must be integral 1-5, so `3.5` is rejected instead of truncated.
  Comments are trimmed and blank becomes `null`. Draft writes are validate-then-write in one transaction, so a
  rejected payload leaves the saved draft untouched.
- Draft saves and submission take the same `PESSIMISTIC_WRITE` lock on the assignment row before reading status.
  Submission requires every KPI to have an HR rating and a manager rating, then atomically writes the result header,
  one result item per KPI, `hr_finalized_at`, and the `HR_REVIEW_PENDING` -> `COMPLETED` transition. This is
  finalization, not result publication: `results_published_at` stays `null`, the cycle stays `PUBLISHED`, the result
  header stays `DRAFT`, and its `published_at` / `published_by` remain `null`. A waiting draft sees the submitted
  status and returns 409; concurrent submissions yield one winner; concurrent draft saves cannot duplicate a row,
  with `uq_hr_assessment` on `employee_kpi_id` as the database backstop.
- Scoring uses only the frozen manager and HR ratings: `manager * 0.75 + hr * 0.25`. The employee self-rating is copied
  into the result item for context but contributes 0 %. Weighted mode requires every frozen weight and a total of
  exactly 100.00; average mode requires every weight to be `NULL`; every other combination fails before a write.
- The service writes `hr_assessments`, the assignment's existing status/timestamp fields, and the new result header
  and items. It never modifies employee self-assessments, manager assessments, KPI snapshots, or an existing result.
  A result that already exists while the assignment is pending returns 409 instead of being overwritten.
- Result persistence and the assignment update share one transaction. If item persistence fails, the header and the
  assignment change roll back together; there is no partial result.

`HrAssessmentIntegrationTest` covers endpoint responses, HR/manager/employee/anonymous access, queue eligibility and
ordering, exactly-`PUBLISHED` visibility, three-perspective detail, draft validation and semantics, submission,
immutability and absence of score fields. `HrAssessmentDataIntegrityIntegrationTest` covers one row per KPI, the
editing HR user, only-`PUBLISHED` visibility at the service boundary, untouched self-assessment/manager/result
tables, snapshot stability and the `COMPLETED` finalization transition. The latch-coordinated
`HrAssessmentConcurrencyIntegrationTest` covers concurrent submissions, concurrent upserts, a draft blocked behind a
committed submission, and two deterministic proofs that draft writes and submissions wait on the assignment row lock.
These use H2 in PostgreSQL mode; PostgreSQL-specific row locking and index behavior are not independently verified.


---

## 15. Environment variables

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `JWT_SECRET` | **yes** | — | HS256 key, at least 32 bytes |
| `JWT_EXPIRATION` | no | `28800000` | Token lifetime in ms (8 h) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | — | Local PostgreSQL (see `SETUP_GUIDE.md`) |

Generate a local secret (never reuse one from another project or from the reference application):

```powershell
# Option 1 - OpenSSL (Git for Windows / WSL)
openssl rand -hex 32

# Option 2 - PowerShell, no extra tooling
[Convert]::ToHexString((1..32 | ForEach-Object { Get-Random -Maximum 256 })).ToLower()
```

A 64-character hex string is 32 bytes and satisfies HS256. Do not commit it, do not paste it into documentation,
and do not reuse it outside local development.

`spring.autoconfigure.exclude` disables Boot's `UserDetailsServiceAutoConfiguration`, so no in-memory user and no
generated default password are ever created or printed.

`dev` is a **development-only** profile. It changes exactly one thing: forgot-password returns the raw reset token in
the response body. It has no default value and is never active unless set explicitly.

---

## 16. Running locally

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12.1"
$env:Path      = "$env:JAVA_HOME\bin;$env:Path"

$env:DB_URL      = "jdbc:postgresql://localhost:5432/evalora"
$env:DB_USERNAME = "evalora_app"
$env:DB_PASSWORD = "your-local-password"
$env:JWT_SECRET  = "the-64-hex-characters-you-generated"

cd backend
.\mvnw.cmd spring-boot:run
```

Expected:

- Flyway applies `V1__init_schema.sql`.
- Hibernate validates and prints no DDL.
- Tomcat starts on 8080.
- No "Using generated security password" line in the log.

`POST /api/auth/login`, `POST /api/auth/forgot-password` and `POST /api/auth/reset-password` are public;
`/api/auth/change-password`, `/api/auth/me` and `/api/auth/logout` require a bearer token. No account exists until the
account-creation or seeder phase, so login cannot succeed yet on a fresh database; until then every protected path
still returns the JSON 401 shown above.

To exercise the reset flow locally without email, start the backend with the dev profile and call forgot-password:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The response then contains `resetToken`. Do not use this profile outside local development.

---

## 17. Tests

| Test class | What it proves |
|---|---|
| `PasswordEncoderTest` (5) | BCrypt strength 12, correct/incorrect password, hash ≠ plain text, per-hash salt |
| `JwtServiceTest` (10) | Generate/validate, subject, role claim, `iat`/`exp`, expiry rejected, malformed rejected, foreign signature rejected, missing/short secret fails fast |
| `SecurityIntegrationTest` (14) | Real filter chain via MockMvc: 401 without token, JSON shape, malformed/expired/foreign tokens, disabled and locked users rejected, expired lock accepted, **role read from DB not token**, CORS allowed origin, unapproved origin rejected, Swagger paths not blocked |
| `AuthServiceTest` (12) | Unit-level lockout rules: normalisation, success reset, configured `expiresIn`, unknown/disabled/wrong-password rejection, 4th vs 5th attempt, active lock without increment, expired lock reset, new sequence after expiry |
| `AuthIntegrationTest` (24) | End-to-end through controller, service, filter chain and H2: success response shape, valid JWT, uppercase/space email, generic 401 for unknown/wrong/disabled, 400 for blank fields, counter and 5-minute lock behaviour, 423 while locked, expired-lock recovery, success reset and `last_login_at`, authenticated `/me`, client-supplied id ignored, DB role wins over token claim, 401/200 logout, and token reuse after logout proving there is no blacklist |
| `PasswordPolicyTest` (7) | Every rule boundary: minimum length, missing letter, missing number, missing special character, whitespace is not special, null rejected |
| `PasswordResetTokenUtilTest` (4) | 64-hex-character token, two tokens differ, SHA-256 matches a known vector, hash is deterministic and never the raw value |
| `PasswordServiceTest` (17) | Unit-level change/forgot/reset: hashing and save, wrong current password, mismatch and policy short-circuits, reuse, generic unknown/disabled forgot, hash-only storage with 15-minute expiry, dev token exposure, successful reset, unknown/expired/used token, disabled user, and single-use under repeated calls |
| `PasswordManagementIntegrationTest` (27) | Non-dev HTTP + H2: change success and login with the new password, old password rejected, JWT still valid, all failure codes and messages, forgot generic response and hash-only storage, expiry window, reset success/invalid/expired/used/mismatch/weak/disabled, reuse rejection, other tokens invalidated, and two concurrent resets yielding exactly one `SUCCESS` |
| `PasswordManagementDevIntegrationTest` (4) | `dev` profile only: raw token returned, stored value is its hash, previous token invalidated when a new one is issued, and still no token for unknown/disabled accounts |
| `BootstrapHrSeederTest` (8) | Unit-level seeder decisions: neither credential, only email, only password, weak password, existing HR, non-HR email collision, email normalisation, and the happy path (role, hash, profile) |
| `BootstrapHrSeederDevIntegrationTest` (2) | `dev` profile startup creates exactly one HR account and profile with a BCrypt hash; running again creates nothing |
| `BootstrapHrSeederNotDevIntegrationTest` (2) | Outside `dev` the seeder bean does not exist and no account is created even when credentials are set |
| `HrEmployeeManagementIntegrationTest` (29) | HR creation of employees/managers, email/code uniqueness (case-insensitive), policy rejection, manager validation, listings and status filter, wrong-role 404, profile updates, ignored security fields, assignment and reassignment with closed history, inactive/self/HR targets rejected, employee deactivate/reactivate with login checks, manager 409 with active reports, reactivation without replay |
| `HrSecurityIntegrationTest` (4) | 401 unauthenticated, 403 for EMPLOYEE and MANAGER, HR allowed, and existing login/`me`/logout/change-password/forgot-password still unchanged |
| `EmployeeCreationRollbackIntegrationTest` (1) | Injected profile-write failure after the account insert leaves no user or profile behind |
| `ManagerAssignmentConcurrencyIntegrationTest` (3) | Creation takes the manager's pessimistic row lock; creation waiting on a lock held by a deactivation later rejects the inactive manager and rolls back; deactivation blocks behind an in-flight creation and then returns 409 for the new active report |
| `EmployeeCodeUniquenessMigrationTest` (6) | Case-different codes rejected by the service and distinct codes accepted; the V3 duplicate-detection query finds existing `EMP001`/`emp001` rows and finds nothing for distinct codes; V3 guards before the index, contains no data-changing statement, keeps V1's constraint, and V1/V2 hashes are unchanged |
| `HrKpiManagementIntegrationTest` (27) | HR create/list/get/update/deactivate/reactivate through the filter chain: trimming, missing/blank/oversized name, weightage bounds and scale, duplicate and case-insensitive duplicate names, creator from the principal, ignored `id`/`active`/`createdBy`/audit fields, newest-first listing, `active` filter and invalid filter, 404s, editable-field updates, duplicate-name updates, case-only rename, immutable state/creator, repeated deactivate/reactivate conflicts, and soft deactivation preserving the row |
| `HrKpiSecurityIntegrationTest` (4) | KPI endpoints return 401 without a token, 403 for EMPLOYEE and MANAGER, and 200/201/404 for HR |
| `KpiConcurrencyIntegrationTest` (2) | A database uniqueness violation is translated to `HrApiException(CONFLICT)`; two overlapping create transactions yield exactly one KPI and one 409 |
| `HrCycleManagementIntegrationTest` (22) | Cycle create/list/get/update through the filter chain: trimming, required fields, date/deadline ordering, case-insensitive duplicates, protected-field ignoring, newest-first listing, unknown ids, DRAFT-only updates, published/completed rejection, and 401/403 for non-HR callers |
| `HrCycleKpiConfigurationIntegrationTest` (19) | Active KPI selection, average and weighted modes, 100.00 total, single/three-KPI weights, master-default preservation, inactive/unknown/duplicate rejection, partial/total/zero/negative/over-100/scale rejection, full replacement, invalid replacement leaving prior rows intact, empty clearing, published-cycle rejection |
| `HrCycleEmployeeConfigurationIntegrationTest` (19) | Active employee/manager assignment, current-manager fallback, explicit-manager snapshot without history mutation, inactive/disabled employee or manager rejection, HR/MANAGER-as-employee and self-review rejection, non-manager reviewer, duplicates, unknown ids, full replacement, empty clearing, published-cycle rejection |
| `CyclePublishingIntegrationTest` (12) | Missing KPI/employee, invalid weights, inactive employee/manager/KPI, self-review, successful assignment and snapshot creation, average mode, field/weight copying, initial status, double publish, published immutability, and master-KPI edits not rewriting snapshots |
| `CyclePublishingRollbackIntegrationTest` (2) | Injected snapshot-write failure rolls the cycle back to DRAFT with no snapshots; a later retry publishes successfully |
| `CyclePublishingConcurrencyIntegrationTest` (2) | Two simultaneous publishes produce one winner and one 409; a manager deactivated while publication waits on the row lock is rejected without snapshots |
| `EmployeeSelfAssessmentIntegrationTest` (27) | Employee-only access (401/403 for HR, MANAGER and anonymous), own-published-cycle listing, exactly-`PUBLISHED` visibility (`IN_PROGRESS`/`COMPLETED` hidden on every endpoint and in the listing), DRAFT hiding, assignment-id swapping returning 404, frozen KPI detail, draft save/partial/empty/update, integer-only 1-5 validation, duplicate/foreign KPI rejection, validate-before-write, submit completeness, status transition and timestamp, double submit, post-submit edit rejection, and status progress |
| `SelfAssessmentDataIntegrityIntegrationTest` (15) | One row per assigned KPI after repeated saves, draft not changing assignment status, no rows for other assignments/KPIs, ownership-scoped read/write, only-`PUBLISHED` visibility at the service boundary, submission persistence, untouched manager/HR/result tables, post-submit immutability, and master-KPI edits not rewriting snapshots or saved values |
| `SelfAssessmentConcurrencyIntegrationTest` (5) | Two concurrent submissions yield one success and one 409, concurrent draft saves keep one row per KPI, a draft save racing a submission leaves a consistent submitted assignment, and two latch-based tests prove deterministically that a draft write blocks while another transaction holds the assignment row lock and that a committed submission wins over a waiting draft |
| `ManagerAssessmentIntegrationTest` (26) | Manager-only access, assignment-snapshot ownership despite current-manager changes, exactly-`PUBLISHED` visibility after employee submission, frozen KPI and self-assessment detail, partial/empty/repeated draft behavior, rating and KPI-id validation, validate-before-write, HR-review transition/timestamp, submitted immutability, and untouched HR/results data |
| `ManagerAssessmentConcurrencyIntegrationTest` (5) | Concurrent submissions yield one success, concurrent drafts keep one row per KPI, a draft blocked behind a committed manager submission returns 409 without changing saved values, and two latch-based tests prove deterministically that both a draft write and a submission block while another transaction holds the assignment row lock |
| `ManagerAssessmentDataIntegrityIntegrationTest` (19) | One row per assigned KPI after repeated saves, the assignment manager recorded on every row, draft not changing assignment status, ownership-scoped read/write/submission, reassigning the employee's current manager not transferring the appraisal, only-`PUBLISHED` visibility at the service boundary, untouched self-assessment/HR/result tables, post-submit immutability, and master-KPI edits not rewriting snapshots |
| `HrAssessmentIntegrationTest` (28) | HR-only access (401/403 for MANAGER, EMPLOYEE and anonymous), queue eligibility and manager-submission ordering, submitted assessments leaving the pending queue, exactly-`PUBLISHED` visibility, three-perspective detail with frozen KPI snapshots, partial/empty/repeated drafts, integer-only 1-5 validation, duplicate/foreign KPI rejection, validate-before-write, blank-comment normalisation, unchanged assignment status on draft, completeness-checked submission, `HR_REVIEW_PENDING` -> `COMPLETED` transition and timestamp, result header/items created as unpublished DRAFT, double-submit and post-submit edit rejection, untouched self/manager assessments, and no score fields exposed |
| `HrAssessmentDataIntegrityIntegrationTest` (16) | One row per assigned KPI after repeated saves, the editing HR user recorded on every row, draft not changing assignment status, no rows outside the assignment, non-HR callers rejected, foreign/unknown KPI rejection, DRAFT and non-`PUBLISHED` cycles invisible at the service boundary, employee/manager-pending assignments hidden, `COMPLETED` finalization persistence, one result header plus one item per KPI, untouched self/manager assessments, post-submit immutability, and master-KPI edits not rewriting snapshots or generated items |
| `HrAssessmentConcurrencyIntegrationTest` (5) | Concurrent submissions yield one success and one 409 with exactly one result header and one item per KPI, concurrent drafts keep one row per KPI and create no result, a draft blocked behind a committed HR submission returns 409 without changing saved values, and two latch-based tests prove deterministically that both a draft write and a submission block while another transaction holds the assignment row lock |
| `PerformanceScoreCalculatorTest` (19) | Pure `BigDecimal` KPI scores (4.25, 3.25, 1.00, 5.00), weighted and average examples (3.95, 3.92), HALF_UP at the final step, repeating-decimal averages, weighted/average mode detection, mixed/partial/total/zero/negative/over-100/empty rejection, and the full grade threshold scale |
| `PerformanceResultGenerationIntegrationTest` (14) | Weighted and average result persistence, one header plus one item per KPI, frozen snapshot and master-KPI immutability, self-rating exclusion, missing manager/HR rating rejection, invalid weight rejection, existing-result conflict without overwrite, unpublished DRAFT status, and atomic rollback plus successful retry when item persistence fails |

These are complemented by the 20 Phase 3A persistence tests (**487** tests in total, 0 failures, 0 errors, 0 skipped). All run on
in-memory H2; no PostgreSQL, no network, no email.

Test isolation: `SecurityIntegrationTest` uses its own database URL and two `@Import`ed endpoints
(`/api/test/**`) that exist only in test sources. `AuthIntegrationTest` uses its own `evalora_auth` H2 database and
encodes one BCrypt strength-12 hash for the whole class. The password tests use `evalora_password` (default profile)
and `evalora_password_dev` (`@ActiveProfiles("dev")`), both with `LOCK_TIMEOUT=10000` for the pessimistic locks. HR
tests use `evalora_hr`, `evalora_hr_security` and `evalora_hr_rollback`; the seeder tests use
`evalora_bootstrap_hr` and `evalora_bootstrap_hr_no_dev`. The rollback test spies only the `EmployeeRepository` in its
own context. KPI tests use `evalora_kpi`, `evalora_kpi_security` and `evalora_kpi_concurrency`; the concurrency test
spies only `KpiMasterRepository` in its own context. Cycle tests use `evalora_cycle`, `evalora_cycle_kpi`,
`evalora_cycle_employee`, `evalora_cycle_publish`, `evalora_cycle_rollback` and `evalora_cycle_concurrency`; the
rollback test spies only `EmployeeKpiRepository` in its own context. Self-assessment tests use
`evalora_self_assessment`, `evalora_self_assessment_data` and `evalora_self_assessment_concurrency`. Manager assessment
tests use `evalora_manager_assessment`, `evalora_manager_assessment_data` and
`evalora_manager_assessment_concurrency`. HR assessment tests use `evalora_hr_assessment`,
`evalora_hr_assessment_data` and `evalora_hr_assessment_concurrency`; the result-generation suite uses
`evalora_result_generation` and spies only `PerformanceResultItemRepository` in its own context.

---

## 18. Deliberate limitations (Phase 3B4-3H)

1. **Three path-level role rules.** `/api/hr/**` requires `ROLE_HR`, `/api/employee/**` requires `ROLE_EMPLOYEE`, and
  `/api/manager/**` requires `ROLE_MANAGER`.
2. **No HR creation through an API.** The only HR account comes from the `dev` seeder; production HR provisioning is a
   deliberate manual/operator step for now.
3. **No logout/revocation list.** With stateless JWTs the client discards the token. Revocation would need a
   denylist or short-lived tokens plus refresh tokens, which is out of scope for v1. Locking or disabling the
   account is the immediate server-side stop. Deactivating an account does take effect on the next request, because
   the JWT filter re-reads `enabled`.
4. **No refresh tokens.** One 8-hour access token, no renewal endpoint.
5. **Swagger UI / OpenAPI documentation.** Springdoc OpenAPI
   (`springdoc-openapi-starter-webmvc-ui` 3.2.0) is installed and configured
   through {@code OpenApiConfig}. The OpenAPI document is available at
   {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html} or
   {@code /swagger-ui/index.html}. Both documentation paths are public
   (permitAll) and require no authentication.

   A JWT bearer security scheme (`bearerAuth`) is registered so Swagger UI
   provides an Authorize button. Obtain a token via
   {@code POST /api/auth/login} and paste it as {@code Bearer <token>}.
   Role-based authorization ({@code ROLE_HR}, {@code ROLE_EMPLOYEE},
   {@code ROLE_MANAGER}) is still enforced by {@code SecurityConfig}; OpenAPI
   documents the authentication requirement only.

   Swagger UI is intended for local development. It should only be exposed in
   a suitably secured environment (for example, behind a VPN or restricted to
   localhost) and is not intended for production use without additional
   access controls.

   Until springdoc was added, these paths returned 404 (not 401/403).
6. **Lockout has no administrator unlock.** It is purely time-based (five minutes); an operator can clear the two
   columns directly if a support case requires it. Deactivation/reactivation does not touch them.
7. **No email of any kind.** No SMTP, provider SDK, notification or password-reset email is configured or called.
8. **Password change/reset does not revoke existing JWTs.** Tokens are stateless and there is no denylist or
   per-user token version, so a token issued before a password change stays valid until its `exp` claim (up to 8
   hours). The same applies after a reset. The client should discard old tokens; disabling the account or letting the
   lock expire is the only immediate server-side stop. Closing this gap requires token revocation or refresh-token
   rotation, which is a separate phase.
9. **Only SHA-256 token hashes are stored**, with no pepper. Because tokens are 256-bit random values, a precomputed
   or brute-force attack on the hash space is not practical; a pepper would add defence in depth but was not required
   here.
10. **Reporting history is new in V2.** `employees.manager_id` is still the fast current-manager pointer; the
    append-only history lives in `employee_manager_assignments`. The PostgreSQL partial unique index is not created by
    H2 (tests generate their schema from entities), so one-open-row is additionally enforced by the service's
    employee-row lock and close-then-insert sequence.
11. **Case-insensitive employee-code uniqueness is new in V3.** H2 cannot run the migration's PL/pgSQL guard or
    emulate a PostgreSQL functional unique index, so the tests exercise the service pre-check and the guard's
    detection query, not the index itself. Real PostgreSQL migration execution remains unverified in this environment.
12. **No pagination or search on the HR listings.** The existing project has no pagination convention; the optional
    `status` filter is the only one for staff and the optional `active` filter the only one for KPIs. Large catalogues
    would need paging in a later phase.
13. **No audit log beyond `assigned_by`.** Reporting-history rows record the HR user and timestamps; other profile
    edits are not separately audited.
14. **KPI name uniqueness is enforced twice, but H2 proves only half of it.** The service pre-check and the
    `DataIntegrityViolationException` mapping are covered by tests. H2 cannot execute PostgreSQL functional indexes, so
    `ux_kpi_master_name_lower` itself is not exercised there; it remains part of the unchanged V1 migration and is the
    final guard in PostgreSQL.
15. **KPI management does not include cycles, assignments, assessments or scoring.** This phase only maintains the
    reusable catalogue; those workflows belong to later phases.
16. **Cycle management stops at publication; assessments are separate.** Phase 3D creates assignments in
  `SELF_ASSESSMENT_PENDING` and freezes KPI snapshots. Phase 3E records employee reference ratings and advances to
  `MANAGER_REVIEW_PENDING`; Phase 3F records manager ratings and advances to `HR_REVIEW_PENDING`. HR assessment,
  final scores, results and performance history remain later phases.
17. **Deadlines are informational only.** No endpoint in Phases 3D, 3E or 3F rejects an action because a date has passed,
    and the test suite does not assert clock-based blocking.
18. **Draft roster rows live in `employee_assignments`.** There is no separate draft table. Because every mutating
    endpoint first requires and locks a `DRAFT` cycle, those rows can never be mistaken for published work, and
    publication reuses them instead of inserting a second copy.
19. **Case-insensitive cycle-name uniqueness and publication locking are only partly proven on H2.** The service
    pre-check, rollback and JPA pessimistic row locks are tested. H2 cannot execute the PostgreSQL functional
    index `ux_cycles_name_lower`, and its row-lock implementation is not PostgreSQL's, so the PostgreSQL index and
    lock behaviour remain verified by the unchanged V1 migration rather than by the H2 suite.
20. **Scoring starts only at HR finalization.** Employee ratings in `self_assessments` are reference-only (0%);
  manager ratings carry 75% and HR ratings 25%. No result exists while the assignment is still in employee, manager
  or HR draft review, and master KPI edits do not rewrite snapshots, assessments or generated results.
21. **Submission is one-way.** There is no withdraw, reopen or HR-unlock endpoint. Once an assignment leaves its
  current review-pending status, draft edits and resubmission return 409. Reopening would be a later workflow decision.
22. **The employee listing is unpaginated.** Like the existing HR listings, `/api/employee/self-assessments/cycles`
    returns every visible assignment, newest cycle first; paging is deferred until the catalogue is large enough to
    need it.
23. **Assessment locking is only partly proven on H2.** The ownership-scoped queries, pessimistic
    assignment lock and the `uq_self_assessment` unique key are exercised by the suite. PostgreSQL's actual lock
  implementation and the V1 constraints remain the production guarantee, not the H2 run. Manager writes use the
  same assignment lock and the V1 `uq_manager_assessment` unique key.
24. **Assessment visibility is exactly `PUBLISHED`.** A cycle that advances to `IN_PROGRESS` or `COMPLETED` disappears
  from the employee self-assessment and manager assessment APIs. Widening either rule is a deliberate later-phase
  decision, not a side effect.
25. **Phase 3H finalization stores the result as DRAFT; Phase 3I publishes it separately.** HR finalization calculates
  each KPI score, the overall score and grade, and writes one `performance_results` header plus one
  `performance_result_items` row per KPI. HR publication updates the result and assignment publication state
  together. Employee/manager reads require both publication timestamps and the matching `PUBLISHED` /
  `RESULTS_PUBLISHED` states.
26. **HR review is one-way.** As with employee and manager submissions, there is no withdraw, reopen or HR-unlock
  endpoint. Once HR submits, the assessment is read-only (detail remains readable while the cycle is published) and
  draft edits or resubmission return 409.
27. **The HR pending queue is unpaginated.** It returns every eligible `HR_REVIEW_PENDING` assignment, ordered by
  manager submission time descending. Paging is deferred until the queue is large enough to need it.
28. **HR visibility has no ownership filter.** Unlike Phases 3E/3F, HR can read any eligible employee's review. This
  is intentional for the HR role, but it means the security boundary is the role plus the published/completed
  workflow state, not a per-user ownership query.
29. **HR row locking and result uniqueness are only partly proven on H2.** The service takes a pessimistic write lock
  on the assignment before status checks and result generation, `uq_hr_assessment` is the HR-row duplicate backstop,
  and `uq_result_assignment` / `uq_result_item` are the result backstops. H2 exercises the JPA lock and transaction
  rollback semantics but not PostgreSQL's row-lock implementation or V1 constraints, so production concurrency relies
  on the unchanged migrations and PostgreSQL itself.
30. **Result publication locks and visibility are exercised on H2, not PostgreSQL.** Individual and bulk publishers
  serialize through assignment locks, and read queries enforce both publication timestamps, their equality, and
  lifecycle states.
  The integration tests use deterministic latches, but PostgreSQL-specific lock behavior remains unverified.

For the HR endpoint contract and result lifecycle, see [API documentation](./API_DOCUMENTATION.md) and
[Performance workflow](./PERFORMANCE_WORKFLOW.md). Scoring thresholds are documented in
[Scoring logic](./SCORING_LOGIC.md).
