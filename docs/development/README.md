# Development Guide (Phase 3M, Implemented)

## Prerequisites

- Java 21 (`java -version`)
- Docker + Compose (verified: Docker 29.8.0, Compose v5.5.1) — required for
  PostgreSQL and Redis, and for tests (Testcontainers starts real containers)
- Included Maven wrapper (`mvnw.cmd`, run from `backend/`) — no system Maven needed

## Daily workflow

1. **Start infrastructure (from `backend/`):**
   `docker compose up -d` (PostgreSQL + Redis + Mailpit)
2. **Configure environment (optional):** copy `.env.example` to `.env`
   (git-ignored) and adjust `DB_*` / `REDIS_*` values; defaults target Compose.
   For account email, `.env.example` already carries the Mailpit shape
   (`APP_EMAIL_PROVIDER=smtp` + `localhost:1025`); the code default is
   `log` (sends nothing), so registration emails only arrive with the
   `smtp` values set — see "Account email" below.
3. **Start the app (from `backend/`):** `.\mvnw.cmd spring-boot:run` → check
   `http://localhost:8080/actuator/health` returns `{"status":"UP",…}`.
   The app also starts with Redis stopped (main health then reports DOWN
   while liveness/readiness stay UP — Redis gates nothing transactional).
4. **Run tests (from `backend/`):** `.\mvnw.cmd test` (795 tests, verified 2026-10-09; needs Docker daemon running).
5. **Schema changes:** add a new `V<version>__<description>.sql` under
   `src/main/resources/db/migration/` — never edit an applied migration
   (see `docs/database/README.md` and ADR-004).

## Important commands

```powershell
# All commands run from backend/ (compose.yaml lives here).
.\mvnw.cmd -B compile                      # compile
.\mvnw.cmd -B test                         # all tests (needs Docker)
.\mvnw.cmd -B test -Dtest=QueueRealtimeWebSocketTests   # live socket tests only
docker compose ps          # infra status
docker compose logs postgres
docker compose logs redis
docker compose logs mailpit
docker compose down        # stop infra, keep PG data
docker compose down -v     # stop infra AND reset PG data
docker exec smartqueue-redis redis-cli      # inspect Redis (dev only)
```

## Conventions

- `application.yml` is the single config file; no profiles yet. Do not add
  a profile hierarchy until a real environment needs one. Business policy
  lives there too (`queue.aging.*`, `queue.realtime.refresh.*`,
  `security.jwt.*` — all static, restart to change; invalid values fail
  fast at startup).
- Secrets and connection endpoints via `DB_*` / `REDIS_*` env vars only;
  placeholders in `.env.example` (no local Redis password by decision).
- Redis holds derived snapshots only (`infrastructure/redis/` boundary):
  build keys with `RedisKeyConvention` (tenant scope mandatory, nulls
  rejected), values as JSON via `RedisJson` (never entities), every future
  write with an explicit TTL (freshness bound, not correctness). See ADR-017.
- Real-time follows the same discipline: business services know nothing
  about Redis or sockets. They publish facts; `queue/realtime/` and
  `infrastructure/websocket/` are the only consumers. Anything a client can
  read must be safe to broadcast — snapshot payloads carry tokens/priority/
  status/waits only, never customer or staff identity.
- Tenant scope is an application concern (`tenant/` boundary, ADR-019):
  `TenantContext` carries organization + optional branch only (never User,
  roles, JWT, or entities); `TenantContextHolder` is a fail-closed
  ThreadLocal — `get()` throws when unset, never "all tenants". Set it at
  the application boundary and clear/restore it in a `finally` block.
  Boundaries that install their own scope (event bridge, controller,
  scheduler) must save the previous context and **restore** it, not blindly
  clear it. Worker threads install their own scope explicitly — ThreadLocal
  scope never crosses into pool threads on its own.
- When changing anything a live client can see, update both the projection
  test (`QueueRealtimeProjectionTests`) and the wire test
  (`QueueRealtimeWebSocketTests`); the second runs a real STOMP client
  against `RANDOM_PORT`, so it proves destination strings and JSON framing,
  not just the projection.
- Degradation claims need their own proof: `QueueRealtimeFailureTests` stops
  the Redis container mid-test and asserts committed queue transactions
  still succeed while only the projection fails. It resolves the Redis port
  through `@DynamicPropertySource` (not `@ServiceConnection`) because the
  container is stopped and restarted.

## Account email (verification, password reset, invitations)

Outbound account email goes through the `EmailSender` port (business logic
never touches SMTP directly). Two providers, one config surface
(`app.email.*`, overridable as `APP_EMAIL_*`):

| Provider | Behaviour |
|---|---|
| `log` (code default) | Records recipient + subject in logs, sends nothing. Safe anywhere. |
| `smtp` | Delivers through the configured relay (`smtp.host` **required** — startup fails fast without it; auth only when a username is set; STARTTLS only when `smtp.starttls-enabled` is `true`). |

Local development uses the **Mailpit** relay from `compose.yaml`
(development-only SMTP sink + inbox UI — no volume, no production role,
never exposed publicly):

```powershell
# 1. start Mailpit (SMTP localhost:1025, inbox UI http://localhost:8025)
docker compose up -d mailpit

# 2. point the backend at it (values already in .env.example)
$env:JWT_SECRET = "at-least-32-bytes-of-local-dev-randomness"
$env:APP_EMAIL_PROVIDER = "smtp"
$env:APP_EMAIL_SMTP_HOST = "localhost"
$env:APP_EMAIL_SMTP_PORT = "1025"
$env:APP_EMAIL_FROM_ADDRESS = "no-reply@smartqueue.local"
$env:APP_EMAIL_FRONTEND_BASE_URL = "http://localhost:5173"
.\mvnw.cmd spring-boot:run

# 3. register a customer (no organization — platform-level identity)
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/register `
  -ContentType "application/json" `
  -Body '{"displayName":"Asha","email":"asha@example.com","password":"Asha-Pass-1"}'

# 4. open http://localhost:8025, read `Verify your SmartQueue account`,
#    and follow its /verify-email?token=… link (or POST the token to
#    /api/v1/auth/verify-email, which is what the frontend page does).
#    Login stays 403 EMAIL_NOT_VERIFIED until then; the token works once;
#    reuse/unknown/expired tokens answer 400 INVALID_VERIFICATION.
```

Notes that matter:

- Emailed links are built from `APP_EMAIL_FRONTEND_BASE_URL` (never from
  a request `Host` header) plus the per-type route (`/verify-email`,
  `/reset-password`, `/invitations/accept`). Production sets its real
  public frontend origin here — never a localhost URL.
- Expiry wording in the message comes from the same TTLs the server
  enforces (`app.account.verification-token-ttl` P1D, `reset-token-ttl`
  PT1H, `invitation-token-ttl` P7D). Tokens are 256-bit random values;
  only SHA-256 digests are stored; bodies (and tokens) are never logged.
- Registration answers `200 verificationRequired: true` when the account
  **and its token row commit** — that means *pending*, not *delivered*.
  Delivery runs after commit only; transport failures degrade to warning
  logs (recipient + subject, never the body) and never roll back the
  account. Every email type has a resend path (`/resend-verification`,
  invitation resend, forgot-password re-request).
- Real external delivery (production operators): `APP_EMAIL_PROVIDER=smtp`,
  the relay host/port, credentials when the relay needs auth, and
  `APP_EMAIL_SMTP_STARTTLS_ENABLED=true` (Mailpit-shape plain SMTP stays
  `false`), plus the real `APP_EMAIL_FROM_ADDRESS` (an address you own —
  deliverability/SPF/DKIM live with the domain owner, not this codebase).
  No provider SDK is involved — plain SMTP.

### Platform administrator bootstrap (Phase 4R.4, ADR-041)

Review authority comes from `app.platform.admin-emails`
(`APP_PLATFORM_ADMIN_EMAILS`, comma-separated, empty by default). Empty
means fail-closed: review endpoints deny everyone, and the first
registered user is never crowned silently.

```powershell
# 1. register the admin address as a normal customer account
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/register `
  -ContentType "application/json" `
  -Body '{"displayName":"Founder","email":"founder@example.com","password":"Founder-Pass-1"}'

# 2. verify its email (Mailpit inbox http://localhost:8025, or the
#    /verify-email frontend route — same flow as any account)

# 3. configure + restart
$env:APP_PLATFORM_ADMIN_EMAILS = "founder@example.com"
.\mvnw.cmd spring-boot:run

# 4. sign in normally (POST /api/v1/auth/login); the session carries
#    PLATFORM_ADMIN and GET /api/v1/platform/organizations works.
#    The review dashboard is at /platform/applications.
```

Rules that matter: the listed address alone grants nothing (the account
must exist, sign in with its own credentials, and be verified — a
configured-but-unverified account cannot even sign in); every review
call re-checks configuration + live account state, so removing an
address (or deactivating the account) locks it out on the next call;
business applications, invitations, and organization admins can never
self-assign the role. Never commit a real admin address — production
sets the environment value.

## Live queue updates (local verification)

STOMP endpoint with authentication: `ws://localhost:8080/ws?token=<jwt>`
(no SockJS; native WebSocket or a STOMP client). Get a token first:

```powershell
$env:JWT_SECRET = "at-least-32-bytes-of-local-dev-randomness"
.\mvnw.cmd spring-boot:run
# create a user + credential first (no signup endpoint — provision via
# PasswordCredentialService in a test/console), then:
# POST http://localhost:8080/api/v1/auth/login {"email":"…","password":"…"}
```

| Purpose | Destination |
|---|---|
| Subscribe (public queue display / staff screen) | `/topic/organizations/{organizationId}/branches/{branchId}/queues/{queueId}` |
| Request a fresh snapshot | `/app/organizations/{organizationId}/branches/{branchId}/queues/{queueId}/snapshot` (no body, no reply destination) |

- Subscribe first, then join a queue (or request a snapshot) — the server
  only publishes on change or on request, it does not replay on connect.
- Anonymous handshakes are rejected; subscriptions outside the token's
  organization/branch scope are denied (and terminate that session —
  reconnect within scope). Managers/admins are organization-wide; staff
  need an ACTIVE assignment at the branch; customers observe their own
  organization's token-only displays.
- The snapshot request ignores any supplied body/tenant hint and rebuilds
  from PostgreSQL using the ids in the destination path, after verifying
  that the branch belongs to that organization.
- Frames arrive as STOMP `MESSAGE` with `content-type: application/json`;
  the body is a JSON object like
  `{"organizationId":…,"queueId":…,"generatedAt":…,"waiting":[…],"serving":[…],"called":[…]}`.
  STOMP clients that ignore `content-type` may see raw bytes — an existing
  protocol quirk of JSON-over-STOMP, documented rather than worked around.
- Snapshots are eventually consistent and expire after 5 minutes; the next
  queue event or snapshot request rebuilds them. Failures in Redis or the
  broker are logged and never fail the committed database transaction.
- Tokens: HS256 access tokens, 1-hour default (`security.jwt.expiration`),
  claims `sub`/`org`/`email`/`roles` only. No refresh tokens — a lapsed
  token simply logs in again. Stateless tokens stay valid until expiry.

## REST API (Phase 3G)

Run the app (`JWT_SECRET` set, Compose up), log in, and call the
representative reads:

```powershell
# login → token
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType "application/json" `
  -Body '{"email":"…","password":"…"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

# own organization (any other id → 404, by design)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/organizations/$($login.organizationId)" `
  -Headers $headers

# a branch of your organization (foreign → 404; same-org but unauthorized → 403)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/branches/<branchId>" -Headers $headers
```

- Conventions (DTOs, validation, `ApiError` envelope, status codes,
  tenant/role rules, JSON shapes): `docs/api/rest-foundation.md`.
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`; UI:
  `http://localhost:8080/swagger-ui/index.html` (dev only — production
  sets `springdoc.swagger-ui.enabled=false`).
- API tests are black-box HTTP (`RestApiFoundationTests`): real server on
  `RANDOM_PORT`, `java.net.http`, non-transactional fixtures (the server
  thread cannot see uncommitted test transactions), per-test UUID data.
  When adding an endpoint, prove: 200 shape, 401 anonymous, 403
  insufficient role, 404 scope-safe foreign id, validation 400 with
  `fieldErrors`, and the envelope fields on every error.
- Controllers stay thin (bind → validate → delegate → DTO). Scope lives
  in the application service, authorization in `TenantAccessPolicy`.
  Never return entities; never log `Authorization`/tokens/passwords.

## Branch management (Phase 3H)

Same login, then (manager/admin token for writes):

```powershell
# paginated, sortable list (sort values are read raw — see branches.md)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/branches?page=0&size=20&sort=name,asc" -Headers $headers
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/branches?status=ACTIVE" -Headers $headers

# create (org from token; 201 + Location)
$branch = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/branches `
  -Headers $headers -ContentType "application/json" `
  -Body '{"name":"Riverside","city":"Indore","country":"India","timeZone":"Asia/Kolkata"}'

# full-replacement update (PUT only; status/ownership immutable here)
Invoke-RestMethod -Method Put -Uri "http://localhost:8080/api/v1/branches/$($branch.id)" `
  -Headers $headers -ContentType "application/json" `
  -Body '{"name":"Riverside","city":"Bhopal","country":"India","timeZone":"Asia/Kolkata"}'

# lifecycle commands (idempotent; deactivation never deletes dependents)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/branches/$($branch.id)/deactivate" -Headers $headers
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/branches/$($branch.id)/activate" -Headers $headers
```

- Matrix: reads display-open (staff by assignment, customers own-org);
  writes MANAGER/ADMIN-only in own org; foreign ids are scope-safe 404s.
  Full contract: `docs/api/branches.md`; decisions: ADR-023.
- Pagination reuse for the next list endpoint: `common/api`
  (`ApiPageRequest` allow-list parsing + `id` tiebreak, `ApiPage`
  envelope, `InvalidRequestException` codes). Copy the raw-`sort`
  plumbing, not `List<String>` binding (it comma-splits).
- API tests: extend `BranchManagementApiTests` (black-box HTTP,
  dedicated orgs for ordering-sensitive cases so tests never depend on
  each other's rows); policy-tier rules also pinned in
  `TenantAccessPolicyTests`.

## Services, counters & capabilities (Phase 3I)

Same login; collections nest under the branch, items are flat
(manager/admin token for writes):

```powershell
# services at a branch (paginated; sort allow-list adds durationMinutes)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/branches/<branchId>/services?sort=durationMinutes,asc" -Headers $headers
$svc = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/branches/<branchId>/services" `
  -Headers $headers -ContentType "application/json" `
  -Body '{"name":"Blood Test","description":"Fasting sample","durationMinutes":15}'

# counters at a branch
$ctr = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/branches/<branchId>/counters" `
  -Headers $headers -ContentType "application/json" `
  -Body '{"name":"Counter 2"}'

# capabilities: add (201), list (plain array), remove (204)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/counters/$($ctr.id)/capabilities" `
  -Headers $headers -ContentType "application/json" `
  -Body "{`"serviceId`": `"$($svc.id)`"}"
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/counters/$($ctr.id)/capabilities" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/v1/counters/$($ctr.id)/capabilities/$($svc.id)" -Headers $headers
```

- Same matrix as branches (display reads; MANAGER/ADMIN org-scoped
  writes; foreign ids scope-safe 404s). Cross-branch links yield 422;
  duplicates yield 409 (constraint-arbitrated). Full contract:
  `docs/api/services-and-counters.md`; decisions: ADR-024.
- API tests live in `ServiceCounterApiTests` (same black-box style;
  dedicated branches for ordering-sensitive cases).

## Users & staff (Phase 3J)

Same login; the directory is management data (manager/admin token),
rosters operational, own records self-readable:

```powershell
# directory (managers/admins only; staff/customers get 403)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/users?role=STAFF" -Headers $headers

# create with optional initial password (BCrypt-hashed server-side, never returned)
$newUser = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/users `
  -Headers $headers -ContentType "application/json" `
  -Body '{"email":"asha@example.com","displayName":"Asha","roles":["STAFF"],"password":"Initial-Pass-1"}'

# roster of a branch (assigned staff included; customers denied)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/branches/<branchId>/staff" -Headers $headers

# assign + lifecycle (reactivation, not a second row, is the path back)
$asg = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/users/$($newUser.id)/staff-assignments" `
  -Headers $headers -ContentType "application/json" `
  -Body "{`"branchId`": `"<branchId>`"}"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/staff-assignments/$($asg.id)/deactivate" -Headers $headers
```

- Email is immutable identity (global unique); roles replace wholesale
  and stay org-scoped (no platform admin); deactivation preserves history
  and fails login generically — proven through real HTTP login in tests.
  Full contract: `docs/api/users-and-staff.md`; decisions: ADR-025.
- API tests live in `UserStaffApiTests` (black-box HTTP incl. the
  login→deactivate→denied→reactivate→login cycle); the two new policy
  tiers are also pinned in `TenantAccessPolicyTests`.

## Appointments (Phase 3K)

Same login; every role books for itself (no customer id in any body):

```powershell
# book (201 + Location; service must belong to the path branch)
$appt = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/branches/<branchId>/appointments `
  -Headers $headers -ContentType "application/json" `
  -Body '{"serviceId":"<serviceId>","startAt":"2026-10-08T04:30:00Z"}'

# role-routed list (customers pinned to self; staff name an assigned branchId)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/appointments?status=SCHEDULED&from=2026-10-08T00:00:00Z&to=2026-10-09T00:00:00Z" -Headers $headers

# lifecycle (cancel: owner/management; complete/no-show: operational scope)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/appointments/$($appt.id)/cancel" -Headers $headers
```

- Reads are privacy-first (non-owners outside operational scope get
  404); lifecycle refusals are honest 409s (not idempotent). Calendar,
  DST, snapshot, and overlap arbitration stay inside
  `AppointmentBookingService` — the REST shell never re-implements them.
  Full contract: `docs/api/appointments.md`; decisions: ADR-026.
- API tests live in `AppointmentApiTests` (black-box HTTP; unique slots
  per test via a shared counter so tests never contend; whole-day
  working-hour fixtures keep slot arithmetic trivial).
- Nullable-`Instant` JPQL lesson: PostgreSQL cannot type a bare null
  `Instant` bind — range edges travel with boolean presence flags (see
  `AppointmentRepository.search`), not `IS NULL` checks.

## Walk-in queues (Phase 3L)

Same login; every role joins for itself (optional triage priority for
operational staff only):

```powershell
# join (201 + Location; token/position/wait server-derived)
$entry = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/branches/<branchId>/queues/<queueId>/entries `
  -Headers $headers -ContentType "application/json" -Body '{}'

# live snapshot (ranked positions + waits, rebuilt from PostgreSQL every read)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/queues/<queueId>" -Headers $headers

# counter claims next (atomic SKIP LOCKED, spans the counter's servable queues)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/counters/<counterId>/call-next" -Headers $headers

# lifecycle (start needs the serving counter; cancel is owner/management)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/queue-entries/$($entry.id)/start-serving" `
  -Headers $headers -ContentType "application/json" -Body "{`"counterId`": `"<counterId>`"}"
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/queue-entries/$($entry.id)/complete" -Headers $headers
```

- The adapter (`queue/application/QueueService`) scopes, authorizes, and
  composes views; ranking/aging/claims/sessions/waits stay in the engine
  untouched. `callNext` installs explicit `(org, counter-branch)` tenant
  scope with save/restore (the claim needs branch scope); everything else
  consumes the filter-installed organization scope.
- Queue fixtures in tests: build entities directly for queues/services,
  but create capabilities through the 3I HTTP API — repository-built
  rows leave lazy proxies no session can initialize outside a
  transaction. Every join uses a fresh customer (duplicate-active rule);
  exact-count assertions use dedicated queues.
- Full contract: `docs/api/queues.md`; decisions: ADR-027.
- API tests live in `QueueApiTests` (black-box HTTP incl. a two-caller
  claim race) plus `QueueRestDegradationTests` (join/state/claim with
  Redis stopped — DynamicProperty wiring, restarted afterwards).

## Service sessions (Phase 3M)

Same login; read-only operational views over rows the 3L commands write
(manager/admin token shown; assigned staff likewise):

```powershell
# running session at a counter (404 when idle — the occupancy signal)
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/counters/<counterId>/current-session" -Headers $headers

# history, newest first, with facets
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/counters/<counterId>/service-sessions?active=false" -Headers $headers
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/service-sessions?branchId=<branchId>&active=true" -Headers $headers
```

- No lifecycle commands exist here by design — `start-serving` opens and
  `complete` ends sessions through the 3L entry routes. `active` is
  derived (`endedAt` null, the domain's own definition); durations are
  derived once ended. Customers have no session views.
  Full contract: `docs/api/service-sessions.md`.
- API tests live in `ServiceSessionApiTests` (black-box HTTP; each flow
  builds a dedicated queue + counter so claims/counts never touch
  another test's rows).

## Frontend foundation & auth shell (Phase 3N)

React 18 + TypeScript (strict) + Vite + React Router 6, no store library
(see ADR-028). The frontend is a thin client of the existing API — it
computes no positions, waits, or conflicts, and never touches PostgreSQL
or Redis directly. Full frontend setup (install, `VITE_API_BASE_URL`,
dev/test/build): `frontend/README.md` (repo root); 309 frontend tests
green, verified 2026-10-09.

Backend-owned notes for frontend work:
- No `/me` endpoint exists — identity comes from the login response + JWT claims
  (`sub`/`org`/`email`/`roles`/`exp`); client-side parsing verifies
  nothing and only restores identity/expiry.
- Session storage (honest tradeoff, ADR-028): `localStorage` under
  `smartqueue.auth.session` (token + derived identity, never passwords,
  never in URLs, never logged). No refresh-token infrastructure exists,
  so none is faked. Any 401 from any call clears the session and returns
  to `/login`; 403 renders the unauthorized page, never a login redirect.
- **Frontend authorization is navigation/UX only; the backend remains the
  security boundary.** Never claim "production secure" for this shell.
- CORS: the backend allow-lists exact origins on `/api/**` only
  (`infrastructure/cors`, default `http://localhost:5173`; production
  sets `app.cors.allowed-origins` / `APP_CORS_ALLOWED_ORIGINS`
  explicitly). The security filter chain is untouched.
- Tests use Vitest + Testing Library with `fetch` stubbed (no backend
  needed): login success/failure/logout/restore/expired-drop, 401-clears-
  session, anonymous→login / login→dashboard redirects, role-denied→
  unauthorized, Authorization header injection, 401/403/envelope parsing,
  per-role navigation. No snapshot tests.
- Conventions: API calls only via `shared/api/client.ts` (typed
  `ApiError` with stable backend `code`s); auth state only via
  `AuthProvider` (`user`, `isAuthenticated`, `isLoading`, `login`,
  `logout`, `hasRole`/`hasAnyRole`); TypeScript strict, no `any`
  bypasses. Dashboard shows no metrics — placeholders stay empty by
  design (no fake KPIs).

## Customer experience (Phase 4A)

CUSTOMER-only routes on the 3N shell (see ADR-029): customer dashboard
(next appointment + current queue, real data only), appointment list
(status filter, pagination), appointment detail (branch-local times,
owner cancel with confirmation), booking (branch → service → branch-
local start → review → confirmation; no availability calendar exists, so
none is faked — the backend validates), queue join (branch → queue →
join; empty body, token/position/wait server-derived), and `/queue/me`
(owner entry read + live updates).

- Consumed endpoints (nothing else): `GET /branches`, `GET
  /branches/{id}`, `GET /branches/{id}/services`, `POST
  /branches/{id}/appointments`, `GET /appointments` (no `customerId`
  ever sent — the backend pins customers to themselves), `GET/POST
  /appointments/{id}[/cancel]`, `GET /branches/{id}/queues`, `POST
  /branches/{id}/queues/{qid}/entries`, `GET/POST
  /queue-entries/{id}[/cancel]`. The staff-only snapshot/detail
  endpoints are never called from customer screens.
- Times: `customer/utils/datetime.ts` (`Intl`-only, no date library).
  Display uses the response zone; booking resolves the branch offset
  iteratively and preserves it in the ISO body. Null waits render "Not
  currently available", never `0`.
- `/queue/me` persistence: `localStorage` id bundle only
  (`smartqueue.queue.lastEntry`); state always re-read from the entry
  endpoint. Latest entry only (documented limitation).
- Realtime: dependency-free STOMP over native WebSocket
  (`customer/realtime/queueLive.ts`, `?token=` handshake per the
  documented contract). Snapshots are anonymous + tenant-scoped, so
  subscribing is privacy-safe; they act as REST refresh signals plus
  waiting-count context. Degradation: "Live updates unavailable" banner
  + manual Refresh + 30 s REST polling fallback. Redis never touched
  from the browser.
- Tests: 43 new Vitest cases (76 total) — contracts/URLs/bodies,
  timezone round-trips + DST, privacy (no identity params anywhere),
  terminal/unknown/404 states, cancel confirmations + 409 copy, live
  connect/message/degrade via a fake socket.

## Staff operations (Phase 4B)

STAFF + MANAGER/ADMIN routes on the same shell (see ADR-030); CUSTOMER
stays denied. Zero backend changes — every rule (ranking, aging, waits,
claims, transitions, scope) is backend-owned; the UI sends commands,
renders results, and refreshes.

- Branch context (`staff/hooks/useBranchContext.ts`): STAFF resolve
  assigned branches from their own ACTIVE assignments
  (`GET /users/{ownId}/staff-assignments` + per-branch reads);
  managers/admins list the org. Unassigned staff see an honest empty
  state. Selection persists per browser.
- Dashboard: branch queues (service + status + deep links), counters
  with occupancy derived from current-session reads (404 = free), quick
  actions. No invented aggregates.
- Queue operations (`/operations/queue`): rank-ordered live snapshot +
  paged entry history (status facet), entry commands (complete, no-show
  and cancel with confirmation; start-serving intentionally lives in
  the counter workspace where the counter is known), stale-row 409s as
  normal ops copy with refresh.
- Counter workspace (`/operations/counter`): counter picker with
  capabilities, occupancy card, counter-centric `call-next` (no list
  picking), claimed-entry actions (start with this counter, complete,
  no-show/cancel with confirmation), double-submit guards (UX only —
  races stay backend-arbitrated).
- Sessions (`/operations/sessions`): branch-scoped, running/ended
  filter, newest-first pages, backend-derived durations only.
- Staff appointments (`/operations/appointments`): branch facet +
  status filter, reads plus own-live-row cancellation only (others 403
  by backend rule — no button shown).
- Realtime reuses the shared 4A STOMP client as a refresh signal with
  the same degradation banner + manual Refresh (ops screens poll
  nothing on a timer by decision — no surprises mid-service).
- Privacy: entries show tokens/status/priority/position/waits plus a
  truncated customer ref; no names or emails exist in these contracts.
- Tests: 37 new Vitest cases (113 total) — contracts, branch
  discovery, lifecycle commands + 409s, idle/running occupancy,
  double-submit guard, staff/manager/customer authorization, live
  refresh + degradation.

## Manager/admin experience (Phase 4C)

MANAGER/ADMIN-only `/manage` routes on the same shell (see ADR-031);
CUSTOMER and STAFF are redirected to `/unauthorized` (UX only — the
backend enforces every write). Zero backend changes — the UI consumes
the Phase 3H/3I/3J contracts exactly as documented in
`docs/api/branches.md`, `docs/api/services-and-counters.md`, and
`docs/api/users-and-staff.md` (allow-list sorts, 0-based pages,
status/role filters, `409` conflicts, `422` branch mismatches).

- Overview (`/manage`, also the MANAGER/ADMIN dashboard landing):
  real organization counts from first-page totals (branches, people)
  plus configuration cards. No revenue/utilization/SLA inventions.
- Branches: directory (status filter, sort, pagination; tables on
  desktop, cards on small screens), detail hub (facts, lifecycle
  with confirmations, tabbed service/counter/staff previews linking
  out), full create/edit forms mirroring `CreateBranchRequest` /
  `UpdateBranchRequest` (no org/status/audit fields).
- Services: branch picker + status filter + pagination, inline
  create dialog, detail with PUT editing and lifecycle.
- Counters: branch picker + status filter (incl. MAINTENANCE),
  inline create dialog, detail with rename + lifecycle and
  capability add/remove (same-branch `422` and duplicate `409`
  surfaced as sentences; backend arbitrates).
- Staff: directory (status + role filters, sort, pagination),
  create (creation-only optional password, never rendered back),
  edit (display name + wholesale role replacement with change
  confirmation; email immutable), lifecycle with confirmations,
  assignment workflows (create via branch picker, activate/
  deactivate; STAFF-role requirement explained, not silently
  enforced client-side).
- Errors: `management/utils/errors.ts` maps stable backend codes
  to sentences; 400 `fieldErrors` render per-field. Loading uses
  skeletons shaped like tables; empty states name the next action.
- Tests: 17 new Vitest cases (130 total) — API shapes/URLs/bodies
  (incl. no-credential-leakage assertion), conflict copy, payload
  helpers, navigation (incl. no-future-placeholder), and per-route
  CUSTOMER/STAFF denial.

## Notification center (Phase 4D)

Persistent in-app notifications, end to end (see ADR-032 and
`docs/api/notifications.md`). Backend: V10 `notification` table,
`notification/` feature module (entity, enums, repository, service,
sender port + in-app STOMP implementation, AFTER_COMMIT listener,
controller, DTO, destinations). Six customer types from committed
facts only (`APPOINTMENT_BOOKED`, `QUEUE_JOINED/CALLED/COMPLETED/
CANCELLED/NO_SHOW`); serving/session events, staff broadcasts, and
appointment-lifecycle transitions stay silent by decision. Persist
first (own REQUIRES_NEW transaction — joining the completing
transaction flushes against a dead session, found by failing tests),
best-effort realtime echo second; failures degrade to REST.

- REST (all recipient-scoped, no user id accepted): `GET
  /notifications` (page/size/`createdAt,readAt` sorts, `unread`
  filter), `GET /notifications/unread-count` (badge value),
  `GET /notifications/{id}`, `POST .../{id}/read` (idempotent),
  `POST /notifications/read-all`. Foreign ids are scope-safe 404s.
- Realtime: per-session `/user/queue/notifications` (session named
  by user id; `/queue` broker prefix; the one explicitly allowed
  subscribe path). Same small DTO as REST.
- Frontend: bell + badge in the shell, panel (latest page,
  mark-read/all-read, deep links, live/offline status), full
  `/notifications` page (filters, pagination), dependency-free live
  client with degradation, QUEUE_CALLED-only toast.
- Tests: backend 18 (persistence 5 + API 13 incl. live-socket
  recipient isolation, rollback/dedup/sender-failure proofs);
  frontend 20 (150 total) — API shapes, live client, targets,
  bell/panel, routes.

## Notification retention (Phase 4E)

Scheduled cleanup for the notification table (see ADR-033 and
`docs/api/notifications.md`). `NotificationRetentionProperties`
(`notifications.retention`: `enabled`, `read-after` P90D,
`unread-after` P365D, `interval` PT6H — ISO-8601, validated, fail
fast, disableable) feeds `NotificationRetentionService`, which sweeps
`created_at`-eligible rows in 500-id bulk-delete batches (no entity
hydration, per-batch own transactions, Clock-derived cutoffs, summary
logging, failures swallowed per tick). V11 adds
`idx_notification_created_at` — both V10 indexes lead with recipient
and cannot serve the global sweep. No tenant context needed (the
predicate touches no tenant data); no REST/WebSocket involvement.

```yaml
notifications:
  retention:
    enabled: true
    read-after: P90D
    unread-after: P365D
    interval: PT6H
```

Static configuration (restart to change); overridable via environment
as `NOTIFICATIONS_RETENTION_ENABLED`, `NOTIFICATIONS_RETENTION_READ_AFTER`,
etc. Removed rows vanish from lists and read as scope-safe 404s.

- Tests: backend 15 (properties 7 + behavior 6 + disabled 2) —
  defaults/custom/invalid/disabled binding, old-vs-recent eligibility
  per policy with exact-boundary determinism, cross-tenant survivor
  integrity, 505-row multi-batch purge, empty-table no-op,
  failure containment. Seeding backdates via JDBC after
  `saveAndFlush` (a bare `save()` leaves the insert unflushed and the
  backdate UPDATE would hit zero rows).

## Appointment reminders (Phase 4F)

Upcoming-appointment reminders through the existing in-app channel
(see ADR-034 and `docs/api/notifications.md`).
`AppointmentReminderProperties` (`appointment.reminders`: `enabled`,
`before` PT24H, `interval` PT10M — ISO-8601, validated, fail fast,
disableable) feeds `AppointmentReminderService` + the thin
`AppointmentReminderScheduler` (fixed-delay tick, summary logging,
never throws). Eligibility is instant-based
(`startAt − before ≤ now`, still upcoming, still
SCHEDULED/CONFIRMED, no `UPCOMING` row yet); copy renders the stored
instant in the branch `ZoneId` (never server/JVM zone, no stored due
times). Each candidate commits its `appointment_reminder` row plus
its `APPOINTMENT_REMINDER` notification in one transaction; the
`uq_appointment_reminder` constraint arbitrates concurrent
schedulers (a lost race classifies to duplicate by re-reading
committed state — note a loser's commit itself reports the
aborted transaction, which is why classification happens at the
per-candidate boundary, not just around the insert). V12 adds the
reminder table plus `idx_appointment_status_start`; no tenant
context is installed (org/recipient come from the appointment row);
no REST, no settings UI. The existing `NotificationService#create`
(REQUIRES_NEW) is deliberately not reused here — it would split the
atomic claim+notify unit in two.

```yaml
appointment:
  reminders:
    enabled: true
    before: PT24H
    interval: PT10M
```

Static configuration (restart to change); overridable via environment
as `APPOINTMENT_REMINDERS_ENABLED`, `APPOINTMENT_REMINDERS_BEFORE`,
`APPOINTMENT_REMINDERS_INTERVAL`, etc. The sweep catches up after
downtime while appointments stay upcoming; terminal appointments
never produce reminders.

- Tests: backend 30 (properties 7 + behavior 18 + disabled 2 +
  concurrency 1 + end-to-end 2) — defaults/custom/invalid/disabled
  binding, exact-boundary and catch-up eligibility, terminal
  exclusion, sequential idempotency, 205-row bounded batching
  (200 + 5 across ticks), cross-org skip, sender-failure row
  survival, tick failure containment, per-branch local rendering,
  spring-forward/fall-back DST proofs, instant-vs-wall-clock due
  arithmetic, multi-timezone sweep, two-thread claim race (exactly
  one reminder + one notification), and HTTP end-to-end (timeline +
  unread count, cancelled yields nothing). Frontend 1 (reminder
  label/glyph/customer deep link — 151 total). Seeding constructs
  `Appointment` entities directly with explicit instants (booking
  calendar rules are the booking service's concern, not the
  reminder's); no JDBC backdating is needed since `startAt` is
  constructor-set, unlike lifecycle-stamped `createdAt`.

## Operational analytics (Phase 4G)

Manager/admin reporting over committed state (see ADR-035 and
`docs/api/analytics.md`). `AnalyticsService` (read-only) gates on
`requireOrganizationManagement` (staff/customers → existing 403),
pins scope to the principal's org (foreign branch/service →
scope-safe 404; same-org service∉branch → 422
`ANALYTICS_SCOPE_MISMATCH`), resolves one effective `[from, to)`
instant window (explicit zone wins, else branch zone, org-wide
requires one — never the JVM zone; 366-day cap via
`AnalyticsProperties`, never truncated), then runs ~10 small
`AnalyticsRepository` aggregates sharing exactly that scope and
assembles intent-revealing DTOs (counts `0`, averages `null`).
Anchors: appointments `startAt`, entries `joinedAt`, sessions
`startedAt`. Wait = `session.startedAt − joinedAt` over COMPLETED
entries with a session (inner join; Phase 2I estimates never used);
durations cover ended sessions only (`EXTRACT(EPOCH …)` per
endpoint through HQL — Hibernate rejects `EXTRACT` over instant
subtraction, found by failing validation). No entities cross the
layer; no N+1 (scalars/group-bys only); no Redis; no exports. V13
adds three predicate-shaped indexes (no tables).

```yaml
analytics:
  max-range-days: 366
```

Static configuration (restart to change); overridable via
environment as `ANALYTICS_MAX_RANGE_DAYS`.

- Tests: backend 30 (properties 4 + service 18 + API 8) —
  defaults/invalid bound, exact metric math with hand-computed
  waits/durations, `[from, to)` inclusion/exclusion per domain,
  single shared window across summary+breakdowns, branch-zone
  derivation and explicit override, 25-hour fall-back day and
  spring-forward gap, branch/service/combined filters, 422
  mismatch, scope-safe 404s, cross-org invisibility, staff/customer
  denial, invalid/overlong ranges, timezone-required/invalid
  rejection, zero-data nulls, running-session exclusion,
  session-less-completion wait exclusion, estimate-vs-history
  separation, volume ordering, and HTTP auth/shape/isolation. Queue
  timestamps are backdated via JDBC after `saveAndFlush` (a bare
  `save()` leaves the insert unflushed and the backdate hits zero
  rows — the retention suite's lesson, re-learned the hard way
  here). Frontend 10 (client URL building, honest-null formatting,
  summary/breakdown rendering, empty/error+retry, branch scoping,
  route guards — 161 total).
- Frontend: `/manage/analytics` (MANAGER/ADMIN guard + nav +
  overview card), day-granular filters (branch → service list;
  org-wide sends the browser IANA zone explicitly), compact summary
  cards, two tables with proportional fills over real counts (cells
  are the accessible equivalent), skeleton/empty/error+retry. No
  chart library, no fake deltas, no invented KPIs.

## Production hardening (Phase 4H)

No new subsystem — a full-system audit (backend
security/tenant/transactions, frontend workflows/states/a11y) with
a P0–P3 assessment, fixing P0/P1 and high-value P2 only. Notable
rules applied:

- Realtime echoes go out strictly after commit (persist-first is a
  contract, not a comment): `NotificationService` via an
  `afterCommit` synchronization, the reminder sweep by sending
  outside the per-candidate transaction.
- Error states branch on backend codes: genuine absence renders
  not-found; failures render explained errors with retry. Dependent
  selects check error before loading spinner (a failed fetch leaves
  `data === null`, which otherwise spins forever).
- Dialogs: shared `useDismissOnEscape` hook, initial focus on the
  safe action, no mid-mutation dismiss (backdrop + Escape + buttons
  all respect the pending flag).
- No new ADR: every fix aligns code with an existing decision
  (ADR-021/028/032), none changes one.

## Authentication (Phase 3F)

- Configure `JWT_SECRET` (≥32 bytes) via environment — startup fails fast
  without it. `.env.example` holds a placeholder only.
- Provision credentials with `PasswordCredentialService.setPassword`
  (BCrypt); users without a row fail login generically. No signup,
  reset, lockout, or audit in this phase.
- Login: `POST /api/v1/auth/login` → `{"tokenType":"Bearer",
  "accessToken":"…","expiresAt":"…","userId":"…","organizationId":"…",
  "roles":[…]}`. Failures are a uniform 401 (`Invalid email or password`)
  for unknown/inactive/credential-less/wrong-password alike.
- Security tests: service-level login matrix, JWT tamper/expiry/claims
  units, real-HTTP chain posture (401 anonymous/bad-token, 404
  authenticated-but-unmapped), policy matrix, filter units, config
  fail-fast, and live-socket security proofs. Test-only HTTP tests use
  plain `java.net.http` against `RANDOM_PORT` — and non-transactional
  fixtures (the server thread cannot see uncommitted test transactions).
- Two test-infrastructure lessons, documented not hidden: (1) a same-named
   `application.yml` under `src/test/resources` **shadows** (replaces) the
  main one in this Boot line — the shared test secret therefore lives in
  `application.properties`, which merges correctly; (2) combining
  `@AutoConfigureMockMvc` with `@ServiceConnection` containers breaks
  context bootstrap here — HTTP tests go black-box instead.
- Non-transactional fixtures must re-save role grants explicitly
  (`users.save` after `grantRole`) — detached entities get no
  dirty-checking flush.
- **No authentication or authorization yet** (ADR-018): anyone who can reach
  `/ws` can subscribe to any queue topic. Socket auth is deferred to the
  security phase.

## Live wait-time freshness (Phase 3D)

Queue rank and wait estimates are functions of the project Clock as well as
of stored rows — aging accrues with zero database mutations, so an
event-only projection freezes between events. The scheduled freshness pass
bounds that drift (ADR-020):

- **Semantics:** every queue holding live entries (`ACTIVE` + at least one
  `WAITING`/`CALLED`/`SERVING` row) is rebuilt from PostgreSQL at least
  once per `queue.realtime.refresh.interval` (default `PT1M`). Empty and
  inactive queues are excluded by the discovery query; their Redis keys
  expire untouched. No latency guarantee is claimed beyond that bound.
- **Configuration** (`queue.realtime.refresh.*`, static, restart to
  change): `enabled` (default `true`; disabling removes the refresh bean —
  event-driven snapshots keep working) and `interval` (ISO-8601 duration —
  the `@Scheduled` parser accepts only ISO-8601 or millis, so Boot-style
  `1m` is unsupported here; zero/negative/unparseable values fail fast at
  startup).
- **TTL relationship:** `interval < TTL` (1 min < 5 min) — the interval
  bounds drift, the TTL bounds how long a snapshot may be served without a
  successful refresh. An unchanged rebuild renews the TTL silently; only
  material changes (reorder, wait movement, membership) publish to the
  topic. `generatedAt` is excluded from the comparison by construction.
- **Stale-write protection:** the tick reads the raw Redis value before
  rebuilding and writes through an atomic Lua compare-and-set; a concurrent
  event-driven refresh in between fails the guard, the stale snapshot is
  discarded, and one fresh rebuild is stored instead. Raw-JSON comparison,
  not timestamps (equal Clock instants can carry different content).
- **Testing strategy:** most refresh tests invoke
  `QueueRealtimeRefreshService.refreshQueue/refreshAll` directly with a
  fixed advanceable Clock (no sleeps, no wall-clock timing); the scheduler
  itself is proven by `QueueRealtimeRefreshSchedulingTests` at a 100 ms
  interval with bounded polling. `QueueRealtimeRefreshTests` is
  non-transactional (real commits fire the AFTER_COMMIT bridge into a
  mocked broker) and pins `joined_at` to the fixed Clock after each join.
- **Degradation:** Redis, broker, or PostgreSQL failures log and skip per
  queue; the scheduler never throws and performs no business writes, so
  there is nothing to roll back. The app boots and serves with Redis down
  (unchanged Phase 3B contract). Two instances may duplicate the refresh
  work — documented, accepted, no distributed lock.

### Troubleshooting

- Nothing arrives → subscribe before triggering a change; check
  `QueueRealtimeEventBridge` logs for Redis/broker errors.
- Garbled body → inspect `content-type` on the STOMP frame (see above).
- App still boots with Redis stopped; only main health goes DOWN and live
  snapshots stop updating.

## Tenant context in tests (Phase 3E)

- Unit tests (`TenantContextTests`): no containers, no Spring — holder
  lifecycle and scope semantics only.
- Service tests: set the context after the fixture is created
  (`TenantContextHolder.set(new TenantContext(org.getId(), branch.getId()))`)
  and clear it in `@AfterEach` (`TenantContextHolder.clear()`). Tests that
  call scope-requiring services from pool threads (concurrency proofs) must
  install the scope **inside** each worker `Callable` with try/finally —
  ThreadLocal scope never crosses into pool threads on its own.
- Cross-tenant tests (`TenantIsolationTests`): separate organizations and
  branches with distinct data; assert scope-safe not-found (never
  "forbidden") for foreign ids and `TenantContextRequiredException` for
  missing context.
- Background tests (`TenantBackgroundTests`): the scheduler and event
  bridge work without a pre-set context and never leak scope
  (`TenantContextHolder.getOrNull()` stays null afterwards).
- Persistence tests keep proving database constraints directly (FKs,
  composite FKs, uniques, exclusions) without an application context.

Common mistakes:

- Calling a scope-requiring service without setting a context
  (`TenantContextRequiredException`).
- Clearing instead of restoring a previous context in a nested boundary
  (destroys the caller's scope — always save/restore).
- Assuming `null` branch means "no scope" (it means organization-wide).
- Trusting client-supplied tenant ids as authorization (never do this;
  authentication/authorization arrive in the security phase).
