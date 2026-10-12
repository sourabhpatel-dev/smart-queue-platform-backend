# ADR-022 — REST API & Contract Foundation

Status: Accepted (Phase 3G, 2026-10-06).

## Context

Phase 3F delivered authentication (BCrypt credentials, HS256 JWTs, one
login endpoint, tenant-bound security, org/branch/role policy,
authenticated sockets) but deliberately no broader REST API. Every future
business phase (organizations, branches, services, appointments, queues)
needs the same HTTP conventions — versioning, DTO boundaries, validation,
error shape, tenant handling, authorization reuse, OpenAPI — and inventing
them per endpoint would fragment the contract. Phase 3G establishes the
reusable infrastructure plus two small representative reads proving it.

## Decision

1. **Versioning:** everything application-level lives under `/api/v1`
   (the login endpoint already set the prefix). No unversioned `/api`
   twin, no `/v2` until a breaking generation genuinely needs one.
   Actuator stays at `/actuator/*`, outside versioning and the OpenAPI
   document.
2. **Package-by-feature:** `organization/api/` + `organization/application/`,
   `branch/api/` + `branch/application/` — no global
   `controller/service/dto` packages. Only packages actually needed exist.
3. **Thin controllers:** bind DTOs, trigger validation, delegate to an
   application service, return a response DTO. No business rules, no JPA,
   no transactions, no manual tenant checks, no role `if`-statements.
4. **DTOs:** entities never cross the REST boundary. Request DTOs carry
   only client-supplied data (tenant scope comes from the principal, never
   the JSON); response DTOs carry API-level data only. Mapping is explicit
   manual code beside the application boundary — no mapping framework
   (the surface is small; a framework would obscure more than it saves).
5. **Validation:** Jakarta Validation for request shape; domain invariants
   stay in services/domain. Shape failures → `400 VALIDATION_ERROR` with
   `fieldErrors`. Unknown JSON properties are ignored (Jackson leniency
   preserved and test-pinned; client tenant ids are never trusted anyway).
6. **Error contract:** one `ApiError` envelope
   (`timestamp/status/code/message/path/fieldErrors`) rendered by a single
   `GlobalRestExceptionHandler` (`common/api/`). Stable codes per kind
   (`ORGANIZATION_NOT_FOUND`, `APPOINTMENT_CONFLICT`,
   `INVALID_QUEUE_STATE`, …); 400/401/403/404/409/500 per the table in
   `docs/api/rest-foundation.md`. No stack traces, SQL, entities, or
   secrets. The narrow Phase 3F `AuthExceptionHandler` is folded into it —
   login keeps its statuses and generic messages, now in the shared
   envelope (the per-response `timestamp` is the only variance, which is
   why the enumeration test compares status/code/message, not bytes).
   Filter-level 401/403 bodies (written before MVC dispatch) keep their
   minimal shape by necessity.
7. **Tenant handling:** REST consumes `AuthenticatedPrincipal` +
   `TenantContext` (installed by the Phase 3F filters). Unknown-or-foreign
   ids render the same scope-safe `404` (no IDOR, no enumeration);
   same-org role denial is `403` via `TenantAccessPolicy` — reused
   unchanged, no second authorization framework, no method-security
   annotations for what the policy already decides.
8. **Representative surface:** exactly two reads —
   `GET /api/v1/organizations/{organizationId}` (own org only) and
   `GET /api/v1/branches/{branchId}` (same-org + branch role rule).
   No other CRUD; no updates (PUT-vs-PATCH deferred to the first phase
   needing one); no idempotency framework (booking/queue keep their own).
9. **Pagination (convention set, code deferred):** with no list endpoint,
   shipping pagination classes would be speculative. The convention is
   fixed now — `page`/`size`/`sort`, explicit per-endpoint sort allow-list,
   `{content, page, size, totalElements, totalPages}`, explicit filter
   params instead of a query language — and the first list phase
   implements it.
10. **HTTP semantics:** `GET → 200`; future creates → `201` (+ `Location`
    where addressable) with a response DTO. JSON only. ISO-8601 date/time,
    UUID strings, enum name strings.
11. **OpenAPI:** `springdoc-openapi-starter-webmvc-ui:3.1.1` — the 3.x line
    is the Boot 4 line (Boot 3 uses 2.x), verified against the springdoc
    compatibility matrix before adding. One document (`OpenApiConfig`:
    title/description/version + `bearerAuth` JWT scheme); public endpoints
    render unlocked, protected reads carry the requirement (verified by
    test against the generated JSON). Docs paths (`/v3/api-docs/**`,
    `/swagger-ui/**`) are publicly readable; production may set
    `springdoc.swagger-ui.enabled=false`.
12. **Security posture preserved:** stateless JWT (CSRF stays disabled with
    the header-auth justification), CORS untouched (no wildcard — no
    frontend yet), default security headers unchanged, logs never touch
    `Authorization`/tokens/passwords, transactions stay in services.
    No request-id tracing (revisit with observability — a bare id without
    log correlation adds shape without value).

## Alternatives considered

- **Full CRUD for organizations/branches now:** rejected — this phase is
  the foundation; CRUD arrives incrementally per domain.
- **ModelMapper/MapStruct:** rejected — explicit mapping is readable at
  this surface size and keeps the boundary visible.
- **Generic filter/query DSL, idempotency framework, distributed tracing:**
  rejected as premature — each has a named future owner phase.
- **Paginating now without a list endpoint:** rejected as speculative
  code; convention documented instead.
- **Byte-identical login failure bodies:** given up for the shared
  envelope's timestamp; enumeration safety re-proven on
  status/code/message.
- **springdoc 2.x / Boot 3 coordinates:** rejected — the project is Boot
  4.1.1 (ADR-002); 3.x is the matching line.

## Consequences

- All future endpoints follow `docs/api/rest-foundation.md`; deviations
  need their own ADR note.
- One new dependency (springdoc UI bundle); no migration (no schema
  change); concurrency paths untouched.
- 16 new black-box HTTP tests (`RestApiFoundationTests`); one honest
  expectation update in `AuthControllerTests` (enumeration proof now
  semantic, not byte-level).
- Intentionally deferred: full CRUD, updates, idempotency, pagination
  code, tracing, analytics, notifications, OAuth/SSO, GraphQL/gRPC,
  gateways, frontend.
