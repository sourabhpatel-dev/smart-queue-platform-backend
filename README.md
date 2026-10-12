# Smart Queue Platform — Backend

Spring Boot backend for the Smart Queue & Appointment Management Platform:
concurrent-safe appointment booking (no double-booking), fair walk-in queue
processing (atomic staff claims, no starvation), honest wait-time estimates,
and real-time queue updates — with strict multi-tenant isolation across
organizations and branches.

This project is self-contained: everything below runs from `backend/`
(all paths in this file are relative to `backend/` unless stated otherwise).
The React frontend lives in `../frontend/` and is optional — no backend
build, test, or runtime step depends on it.

> Current state: organization onboarding & approval (business applications
> with `PENDING_APPROVAL` lifecycle, verified-owner + platform-review
> activation, `PLATFORM_ADMIN` bootstrap, `ACTIVE`-only discovery/booking).
> See `docs/PROJECT_STATUS.md` (single source of truth) and `AGENTS.md`.

## Features (implemented)

- Organization to Branch domain with scoped management APIs
- Branch services + counters + capability links (same-branch enforced)
- Users (global email identity) + org-scoped roles + branch staff assignments
- Weekly working hours (split intervals) + branch holidays + branch time zones
- Appointment booking over `[start, end)` instants with partial exclusion
  constraints — proven race-safe by concurrent booking tests
- Walk-in queues: daily tokens, priority order with capped aging (no
  starvation), atomic `SKIP LOCKED` call-next, guarded lifecycle
- Service sessions: measured start/end facts, atomic start/completion
- Wait-time estimates: shared position model, whole minutes, snapshot semantics
- Domain events: immutable facts, `AFTER_COMMIT` delivery, no brokers
- Redis: derived/ephemeral state only (keys/TTL/health), PostgreSQL authoritative
- Live queue snapshots over WebSocket/STOMP (authenticated, tenant-scoped)
- Scheduled freshness tick (active queues rebuilt every minute, guarded writes)
- Auth: BCrypt credentials, self-contained HS256 JWTs (1h, no refresh tokens),
  tenant-bound security, authenticated sockets
- REST: `/api/v1`, entity-free DTOs, Bean Validation, one `ApiError` envelope,
  scope-safe `404`s, OpenAPI + Swagger UI (dev)
- Account lifecycle: registration (org-less customers + business applications),
  email verification, password recovery/change, invitations, OAuth identity rows,
  rate-limited public endpoints
- In-app notification center (persisted + per-session realtime echo),
  retention sweep, appointment-reminder sweep
- Operational analytics (manager/admin, measured waits/durations only)
- Public discovery restricted to `ACTIVE` organizations

## Architecture

**Modular monolith, package-by-feature** (ADR-001):

```text
src/main/java/com/sourabh/smartqueue/
├── common          // BaseAuditableEntity (UUID + timestamps), ApiError, global handler, pagination
├── organization    // entity/repo + read/service + management API
├── branch          // entity/repo + read/service + management API
├── service         // branch services + management API
├── counter         // counters + capability API
├── user / staff    // roles, directory, rosters, assignments, lifecycle API
├── workinghour / holiday
├── appointment     // engine + booking/lifecycle API + reminder sweep
├── queue           // engine (join/claims/aging/waits) + realtime + freshness + operations API
├── tenant          // TenantContext, fail-closed holder (ADR-019)
├── auth            // credentials, JWT, login, account lifecycle, policies
├── membership / platform / discovery // org membership, onboarding review, public discovery
├── notification / email              // in-app center, retention, EmailSender port (log/smtp)
├── analytics       // read-only aggregates
└── infrastructure  // redis, websocket, cors, openapi
```

Layering: thin controllers → `@Transactional` application services →
domain logic → Spring Data JPA. DTOs (`record`s) at the API boundary;
entities are never exposed. Tenant isolation is enforced at the service
layer (scope checks + query scoping), never by trusting client IDs.

Decisions: `docs/decisions/` (ADR-001 to ADR-041, one intact series —
including the frontend-experience ADRs 028–031, kept together deliberately).
System view: `docs/architecture/system-architecture.md`.

## Technology Stack (actual, from `pom.xml`)

- Java 21, Spring Boot **4.1.1**, Spring Web MVC, Spring Data JPA/Hibernate
  (`ddl-auto: validate`), Bean Validation
- Spring Security + OAuth2 resource-server (Nimbus JOSE, HS256 JWTs)
- Flyway + `flyway-database-postgresql` — sole schema mechanism
- PostgreSQL driver; Testcontainers (PostgreSQL + Redis via `@ServiceConnection`)
- Spring Data Redis (Lettuce), Spring WebSocket/STOMP (simple in-process broker)
- Spring Boot Mail (SMTP sender behind the `EmailSender` port), Actuator (health only)
- springdoc-openapi 3.1.1 (`/v3/api-docs` + Swagger UI, `bearerAuth` scheme)
- Build: Maven wrapper (`mvnw` / `mvnw.cmd`) — no system Maven needed

## Prerequisites

- Java 21 (`java -version`)
- Docker + Compose (verified: Docker 29.8.0, Compose v5.5.1) — required for
  PostgreSQL/Redis/Mailpit and for tests (Testcontainers starts real containers)
- Included Maven wrapper — no system Maven needed

## Environment Configuration

Connection settings and secrets come from environment variables (never
hard-coded). Copy the template and adjust:

```powershell
Copy-Item .env.example .env   # git-ignored; placeholders only, never commit real secrets
```

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | `localhost` / `5432` / `smartqueue` / `smartqueue` / `smartqueue` | PostgreSQL (dev default only) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis (derived state only; no password locally, by decision) |
| `JWT_SECRET` | *(none — required)* | HMAC key, min 32 bytes; startup fails fast without it |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Exact frontend origin(s); production sets its real origin(s), never `*` |
| `APP_EMAIL_PROVIDER` | `log` | `log` records attempts and sends nothing; `smtp` delivers (local Mailpit or real relay) |
| `APP_EMAIL_FROM_ADDRESS` | `no-reply@smartqueue.local` | Envelope sender |
| `APP_EMAIL_FRONTEND_BASE_URL` | `http://localhost:5173` | Origin used to build emailed links (production: the real public frontend origin) |
| `APP_EMAIL_SMTP_HOST` / `APP_EMAIL_SMTP_PORT` | *(none)* / `1025` | Relay host (required for `smtp`) and port (Mailpit: `localhost:1025`) |
| `APP_EMAIL_SMTP_USERNAME` / `APP_EMAIL_SMTP_PASSWORD` | *(none)* | Only for relays that need auth (Mailpit needs none) |
| `APP_EMAIL_SMTP_STARTTLS_ENABLED` | `false` | `true` for real external relays; `false` for local Mailpit |
| `APP_PLATFORM_ADMIN_EMAILS` | *(none — fail-closed)* | Comma-separated admin account emails; empty denies all review access |

Static policy (`application.yml`, restart to change, fail-fast on invalid):
`queue.aging.*`, `queue.realtime.refresh.*`, `security.jwt.expiration`
(PT1H), `appointment.reminders.*`, `notifications.retention.*`,
`analytics.max-range-days` (366), `app.account.*` (token TTLs, password
rules), `app.security.rate-limit.*`. All overridable via environment
(`APPOINTMENT_REMINDERS_*`, `NOTIFICATIONS_RETENTION_*`,
`ANALYTICS_MAX_RANGE_DAYS`, `APP_ACCOUNT_*`, `APP_SECURITY_RATE_LIMIT_*`).

## Infrastructure Startup

```powershell
# From backend/:
docker compose up -d          # PostgreSQL + Redis + Mailpit (first run pulls images)
docker compose ps             # status
docker compose logs postgres  # per-service logs (redis, mailpit likewise)
docker compose down           # stop, keep PG data volume
docker compose down -v        # stop AND reset local database (deletes volume)
```

`compose.yaml` starts backend infrastructure only — there is no application
container; the app runs on the host and connects over `localhost`.
Mailpit: SMTP `localhost:1025`, inbox UI `http://localhost:8025`.

## Database / Flyway Behavior

On startup the app validates and applies `V<version>__<name>.sql` from
`src/main/resources/db/migration/` (`classpath:db/migration`,
`baseline-on-migrate: true`). Hibernate `ddl-auto: validate` only checks
entity/schema agreement — it never modifies the schema.

Applied migrations (immutable — schema changes always add a new version,
never edit an old one):

| Version | Content |
|---|---|
| V1 | Baseline (pipeline proof) |
| V2 | Organization + branch |
| V3 | Services, counters, capability links |
| V4 | Users, roles, staff assignments |
| V5 | Working hours + holidays |
| V6 | Appointments (partial exclusions) |
| V7 | Queues, entries, token counter |
| V8 | Service sessions |
| V9 | User credentials (BCrypt, per-user) |
| V10 | Notifications |
| V11 | Notification retention index |
| V12 | Appointment reminders (claim-arbitrating unique) |
| V13 | Analytics reporting indexes (no tables) |
| V14 | Credential password-changed tracking |
| V15–V16 | Account verification + password-reset tokens |
| V17–V18 | Organization invitations + OAuth identity |
| V19 | Backfill: one `ACTIVE` queue per pre-existing (branch, service) pair (no schema change, idempotent) |
| V20 | Organization membership + customer-leg FK rework (ADR-040) |
| V21 | Organization onboarding lifecycle (`PENDING_APPROVAL`, ADR-041) |

Details: `docs/database/README.md` and ADR-004.

## Build and Test

```powershell
# From backend/ (Docker daemon must be running — Testcontainers):
.\mvnw.cmd -B test                      # full suite
.\mvnw.cmd -B test -Dtest=QueueApiTests # single class
.\mvnw.cmd -B compile                   # compile only
.\mvnw.cmd -B package                   # build jar (target/*.jar)
```

Verified 2026-10-09: **795 tests, 0 failures** (unit + service + black-box
HTTP API + security/tenant-denial + concurrency races + Testcontainers
PostgreSQL/Redis). Tests use real containers (`postgres:16-alpine`,
`redis:7.4-alpine`) — no H2, no infra mocks; without a Docker daemon they
cannot run, by design.

## Run the Backend

```powershell
# From backend/ (infra up; JWT secret required):
$env:JWT_SECRET = "at-least-32-bytes-of-local-dev-randomness"
.\mvnw.cmd spring-boot:run
```

Health: `GET http://localhost:8080/actuator/health` returns UP when all
infrastructure is up (only health is exposed; details never shown). The app
boots and serves with Redis stopped — main health reports DOWN while
liveness/readiness stay UP, because Redis gates nothing transactional.

Local end-to-end (Mailpit): set `APP_EMAIL_PROVIDER=smtp`,
`APP_EMAIL_SMTP_HOST=localhost`, `APP_EMAIL_SMTP_PORT=1025`, register a
customer (`POST /api/v1/auth/register`), open `http://localhost:8025`,
and follow the verification link. Full walkthrough:
`docs/development/README.md` ("Account email", "Platform administrator
bootstrap", "REST API", "Live queue updates").

## API Documentation

- Endpoint contracts: `docs/api/` (`auth.md`, `onboarding.md`,
  `rest-foundation.md`, `branches.md`, `services-and-counters.md`,
  `users-and-staff.md`, `appointments.md`, `queues.md`,
  `service-sessions.md`, `notifications.md`, `analytics.md`)
- Live OpenAPI JSON: `GET /v3/api-docs`; Swagger UI:
  `/swagger-ui/index.html` (dev only — production sets
  `springdoc.swagger-ui.enabled=false`)
- WebSocket: `ws://localhost:8080/ws?token=<jwt>` (native WebSocket, no
  SockJS); subscribe `/topic/organizations/{o}/branches/{b}/queues/{q}`,
  snapshot via `/app/.../snapshot`, notification echo on
  `/user/queue/notifications`
- Postman collection & environment template: `postman/` (`SmartQueue.postman_collection.json`, `SmartQueue.local.postman_environment.json`). Guide: [`docs/api/POSTMAN_GUIDE.md`](docs/api/POSTMAN_GUIDE.md)

## Authentication and Authorization

`POST /api/v1/auth/login` (email + password) leads to a Bearer JWT (1h,
claims `sub`/`org`/`email`/`roles`). Flow: login → JWT → Security filter
chain → role check → organization/branch/resource check → operation.
Roles: `CUSTOMER`, `STAFF`, `MANAGER`, `ADMIN` (plus derived
`PLATFORM_ADMIN`, see below). Failures are uniform `401`s;
unknown/inactive/unverified accounts fail generically. No refresh tokens —
a lapsed token logs in again. Details: `docs/api/auth.md`, ADR-021.

## Tenant Isolation and Membership

Hierarchy: `Organization` to `Branch` to Services / Counters /
Appointments / Queues (shared PostgreSQL, ADR-019). Customers are
**platform-level identities** (registration takes no organization; JWT
carries no `org` claim); `STAFF`/`MANAGER`/`ADMIN` operate through
**`OrganizationMembership`** rows — the sole authorization fact (ADR-040,
supersedes ADR-036). Customer reads are owner-scoped and resource-derived;
foreign ids are scope-safe `404`s (never "forbidden"); every request runs
under an explicit `TenantContext` (fail-closed holder — missing scope
throws, never "all tenants"). Never trust client-supplied tenant ids.

## Email Verification

Outbound account email (verification, password reset, invitations) goes
through the `EmailSender` port — business logic never touches SMTP.
Provider `log` (default) records and sends nothing; `smtp` delivers.
Local development uses Mailpit from `compose.yaml` (dev-only sink, no
volume, no production role): `docker compose up -d mailpit`, point the
backend at `localhost:1025` (values in `.env.example`), register, and
read the inbox at `http://localhost:8025`. Tokens are 256-bit random,
only SHA-256 digests stored, single-use, expiring (verification P1D,
reset PT1H, invitation P7D); login stays `403 EMAIL_NOT_VERIFIED` until
verification. Emailed links are built from `APP_EMAIL_FRONTEND_BASE_URL`
(never the request `Host`).

## Platform-Admin Configuration

Review authority comes from `APP_PLATFORM_ADMIN_EMAILS` (comma-separated,
empty by default → fail-closed: review endpoints deny everyone, and the
first registered user is never crowned). Setup: register the address as a
normal customer, verify its email, set the variable, restart, sign in —
the session carries `PLATFORM_ADMIN` and platform review endpoints work.
The role is re-checked per call; removing an address (or deactivating the
account) locks it out on the next call. Business applications, invitations,
and organization admins can never self-assign it. Never commit a real admin
address. Details: `docs/api/onboarding.md`, ADR-041.

Security limitation (honest): this is a portfolio build — no security audit
or certification is claimed. Secrets come from environment only; passwords,
tokens, and JWT secrets are never logged.

## Limitations (honest)

- No application Docker image yet — the app runs via `spring-boot:run`;
  `compose.yaml` covers infrastructure only.
- No production deployment, no load tests, no measured performance numbers —
  none claimed.
- No appointment rescheduling, no organization mutation/listing, no
  deletions/transfers on most resources.
- `pom.xml` SCM/license/developer metadata placeholders still empty.

## Key Documents

- `docs/PROJECT_STATUS.md` — current state, phases, debt (source of truth)
- `docs/development/README.md` — workflow, conventions, email, platform admin, REST, sockets
- `docs/api/` — endpoint contracts per domain
- `docs/database/README.md` — schema, migrations, indexing rationale
- `docs/architecture/system-architecture.md` — modules, flows, strategies
- `docs/decisions/` — ADR-001 to ADR-041
- `scripts/README.md` — dev-only SQL utilities (never Flyway)

Contributing agents: read `AGENTS.md` first.
