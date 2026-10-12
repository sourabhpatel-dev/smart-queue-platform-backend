# ADR-021: Authentication & Authorization Foundation

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3F — Authentication & Authorization Foundation)
- Relates to: AGENTS.md §§2/3/12, ADR-009, ADR-018, ADR-019, ADR-020,
  `auth/`, `tenant/`, `infrastructure/websocket/`

## Context

Through Phase 3E the application had tenant scope but no identity:
sockets were open, services trusted caller-supplied ids, and the
`TenantContext` had no authenticated producer. Phase 3F must establish
who the caller is, bind it to tenant scope, and gate the two live
boundaries (REST, STOMP) — without building the full REST API, an
identity provider, or a permission matrix for endpoints that do not exist.

## Decision

- **Credentials separate from identity**: new `user_credential` table
  (V9: `user_id` unique FK, `password_hash` only — no plaintext, no
  tokens, no history) holds one BCrypt hash per user; `User` keeps email,
  roles, and status with no security fields. `UserStatus` stays the source
  of truth for whether login works; users without a credential row fail
  exactly like a wrong password. No backfill of existing rows.
- **Email login identifier** via the single `User.normalizeEmail` rule
  (extracted so the service cannot drift from the entity); global
  uniqueness already guaranteed.
- **Generic failures everywhere**: unknown user, inactive account, missing
  credential, and wrong password all return "Invalid email or password"
  (plus a dummy-hash comparison on the empty path for timing parity) —
  proven byte-identical at HTTP and service level. Passwords never logged,
  never compared manually, never returned.
- **Self-contained HS256 JWTs** (Nimbus, Boot-managed, issuer = audience =
  this application): access tokens only, one-hour default, claims limited
  to `sub`/`org`/`email`/`roles`/`iat`/`exp`. No branch list in the token
  (assignments change mid-lifetime — the database stays authoritative for
  branch access). No refresh tokens, no revocation, no persistence:
  tokens are valid until expiry (documented).
- **Secret discipline**: `security.jwt.secret` from `JWT_SECRET` only, no
  default — startup fails fast when missing or under 32 bytes; tests use a
  synthetic key. Never committed, never logged.
- **Principal**: immutable `AuthenticatedPrincipal`
  (userId/organizationId/email/roles); Spring authorities are an in-memory
  `ROLE_`-prefixed projection, never a second persisted model. Domain
  entities take no security dependencies.
- **Filter chain**: login, WS handshake, health, and `/error` public;
  everything else authenticated; stateless sessions; CSRF disabled
  *because* credentials travel in the `Authorization` header rather than
  ambient browser state; CORS untouched; method security enabled as a
  capability with no annotations yet. `/error` stays public deliberately:
  stateless error dispatches re-authorize without request scope, so
  protecting it would mask every true status as 401.
- **TenantContext integration**: `TenantContextFilter` builds an
  organization-level context from the principal around the chain with
  save/restore semantics (reusing Phase 3E, never a second mechanism).
  Scheduler, event bridge, and projections take no authentication dependency.
- **Authorization policy** (`TenantAccessPolicy`, the reusable boundary):
  organization match first (role never substitutes for scope); branch must
  belong to that organization; MANAGER/ADMIN are organization-wide, STAFF
  requires an ACTIVE assignment at the branch, others observe their own
  organization's token-only displays. Key namespacing is not authorization.
- **WebSocket**: handshake interceptor requires a bearer token
  (`?token=`, browsers cannot set headers; `Authorization` header also
  accepted) and rejects anonymous handshakes; the handshake handler binds
  the principal to the session (STOMP CONNECT headers provably do not
  reach it — verified against Spring's bytecode); the channel interceptor
  authorizes SUBSCRIBE/SEND by organization, branch rule, and actual queue
  scope, denying unknown destinations; the controller re-checks path
  organization outside its log-only block. Denial terminates the session.

## Alternatives Considered

1. **Password column on `app_user`.** Rejected: mixes identity and secret
   lifecycles and drags security fields into the domain model.
2. **External IdP / OAuth login / sessions+cookies.** Rejected: no such
   requirement; self-contained tokens fit a single-deployable portfolio
   app without cookie/CSRF machinery.
3. **Refresh tokens + revocation.** Rejected: persistence, rotation, and
   logout infrastructure with no demonstrated need; short-lived access
   tokens suffice for the foundation.
4. **Branch assignments inside the JWT.** Rejected: stale the moment an
   assignment changes; resolved live from PostgreSQL instead.
5. **STOMP CONNECT-header authentication.** Attempted, then removed with
   evidence: the session principal resolves from the WebSocket session,
   never from CONNECT headers — handshake binding is the only correct
   point.
6. **`@PreAuthorize` scattered over services.** Rejected for now: no
   business REST endpoints exist to protect; the policy bean plus method
   security capability covers the future.
7. **Test `application.yaml` for the JWT test secret.** Attempted, then
   removed with evidence: a same-named YAML in test resources shadows
   (replaces) the main one in this Boot line, silently dropping
   datasource/JPA/Flyway settings. A same-keyed
   `application.properties` merges correctly instead.
8. **MockMvc controller tests.** Attempted, then removed with evidence:
   `@AutoConfigureMockMvc` combined with `@ServiceConnection` containers
   fails context bootstrap in this Boot line; black-box HTTP tests over
   `RANDOM_PORT` prove more (real chain, entry point, filters) anyway.

## Consequences

- One public endpoint (`POST /api/v1/auth/login`, DTOs only); sockets
  require authentication with tenant-scoped subscriptions.
- 52 new tests (8 login incl. enumeration-parity, 9 JWT incl. expiry/
  tamper/`none`/claims, 5 HTTP incl. chain posture, 13 policy, 6 filter
  units, 5 config validation, 6 live-socket security); full suite
  315/315 green; V9 migration; two dependencies
  (`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`
  for Boot-managed Nimbus).
- Known limitations, honestly held: stateless tokens valid until expiry;
  customers observe same-org displays; no lockout/rate-limit/audit;
  unknown REST paths 401 anonymously by stateless error-dispatch design;
  the `/ws` open-origin pattern predates this phase (ADR-018) and is left
  untouched — authentication now guards the handshake, but origin
  tightening waits for a real frontend deployment.
