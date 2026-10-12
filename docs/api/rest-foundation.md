# REST API Foundation (Phase 3G)

Shared conventions every future business endpoint follows (decided in
[ADR-022](../decisions/ADR-022-rest-api-contract-foundation.md)).
Authentication itself lives in [`auth.md`](auth.md).

## Versioning

All application endpoints live under **`/api/v1`**. No `/api` unversioned
prefix, no `/v2`, no `/api/version/1` alongside it. A future breaking
generation adds `/api/v2`; v1 stays until explicitly retired.

## Layering

```text
HTTP Request
  → Spring Security (JWT filter → principal, tenant filter → org scope)
  → Controller (thin: bind DTOs, validate, delegate)
  → Application service (@Transactional boundary, TenantAccessPolicy)
  → Domain → Repository (scoped queries) → PostgreSQL
  → Response DTO → HTTP response
```

Controllers never contain business rules, JPA queries, transactions,
manual tenant checks, or role `if`-statements. The service owns scope;
`TenantAccessPolicy` owns authorization.

## DTOs

JPA entities never leave the server. Request DTOs carry only
client-supplied data (never `organizationId`, `branchId`, audit fields,
or internal ids unless the endpoint genuinely needs them — tenant scope
comes from the principal, not the JSON). Response DTOs expose API-level
data only (no hashes, credentials, proxies, or persistence internals).
Mapping is explicit manual code next to the application boundary — no
mapping framework.

## Representative endpoints

### `GET /api/v1/organizations/{organizationId}` → `200`

Returns the caller's own organization:

```json
{
  "id": "…",
  "name": "City Clinic",
  "status": "ACTIVE",
  "createdAt": "2026-10-06T09:00:00Z",
  "updatedAt": "2026-10-06T09:00:00Z"
}
```

Any other id — unknown or foreign — is `404 ORGANIZATION_NOT_FOUND`,
so the endpoint cannot enumerate tenants. Requires authentication.

### `GET /api/v1/branches/{branchId}` → `200`

```json
{
  "id": "…",
  "organizationId": "…",
  "name": "Downtown",
  "city": "Indore",
  "country": "India",
  "timeZone": "Asia/Kolkata",
  "status": "ACTIVE",
  "createdAt": "2026-10-06T09:00:00Z",
  "updatedAt": "2026-10-06T09:00:00Z"
}
```

Scope order: unknown id or foreign organization → `404
BRANCH_NOT_FOUND` (IDOR-safe, indistinguishable); same organization but
outside the caller's role → `403 FORBIDDEN` (managers/admins org-wide,
staff by ACTIVE assignment, customers observe their own organization's
displays — the Phase 3F policy, reused unchanged).

## HTTP semantics

- `GET` success → `200 OK` with the DTO.
- Future creation endpoints → `201 Created` (+ `Location` where a new
  addressable resource exists), returning a response DTO, never the entity.
- No update endpoints exist yet: PUT-vs-PATCH is deferred to the first
  phase that genuinely needs an update (documented, not decided by default).
- No generic idempotency-key framework: booking/queue operations keep
  their own concurrency/idempotency handling in their API phases.

## Validation

Request shape uses Jakarta Validation (`@NotBlank`, `@Email`, `@Size`,
…); business/domain rules stay in services and the domain. Shape
failures → `400 VALIDATION_ERROR` with per-field detail (see below).
Unknown JSON properties are ignored (Jackson leniency preserved —
proven by test; client-supplied tenant ids are never trusted regardless).

## Error contract

Every controller-thrown failure renders as:

```json
{
  "timestamp": "2026-10-06T09:00:00Z",
  "status": 404,
  "code": "BRANCH_NOT_FOUND",
  "message": "Branch not found",
  "path": "/api/v1/branches/…",
  "fieldErrors": []
}
```

| Field | Meaning |
|---|---|
| `timestamp` | ISO-8601 server time of the failure |
| `status` | HTTP status (mirrors the response) |
| `code` | Stable machine-readable kind — parse this, not `message` |
| `message` | Human-readable detail (safe: no traces, SQL, or secrets) |
| `path` | Request path |
| `fieldErrors` | `[{field, message}]` for validation failures; empty otherwise |

Status/code map:

| Status | Codes |
|---|---|
| `400` | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `INVALID_REQUEST`, `INVALID_TIME_RANGE`, `INVALID_APPOINTMENT_REQUEST`, `INVALID_QUEUE_REQUEST`, `INVALID_PRIORITY`, `OUTSIDE_WORKING_HOURS`, `BRANCH_CLOSED`, `COUNTER_CANNOT_SERVE_QUEUE`, `INVALID_BRANCH_REQUEST`, `INVALID_USER_REQUEST`, `INVALID_SORT`, `INVALID_PAGE`, `INVALID_SIZE`, `INVALID_STATUS`, `INVALID_ROLE` |
| `401` | `UNAUTHENTICATED` (login failures stay generic — no enumeration) |
| `403` | `FORBIDDEN` (fixed message, mirrors the filter-level body) |
| `404` | `NOT_FOUND` (unmapped path), `ORGANIZATION_NOT_FOUND`, `BRANCH_NOT_FOUND`, `QUEUE_ENTRY_NOT_FOUND`, `QUEUE_NOT_FOUND`, `SERVICE_SESSION_NOT_FOUND`, `SERVICE_NOT_FOUND`, `COUNTER_NOT_FOUND`, `CAPABILITY_NOT_FOUND`, `USER_NOT_FOUND`, `STAFF_ASSIGNMENT_NOT_FOUND`, `APPOINTMENT_NOT_FOUND` |
| `409` | `APPOINTMENT_CONFLICT`, `APPOINTMENT_INVALID_TRANSITION`, `QUEUE_CONFLICT`, `INVALID_QUEUE_STATE`, `QUEUE_NOT_ACTIVE`, `NO_WAITING_ENTRY`, `BRANCH_NAME_CONFLICT`, `SERVICE_NAME_CONFLICT`, `COUNTER_NAME_CONFLICT`, `CAPABILITY_ALREADY_EXISTS`, `USER_EMAIL_CONFLICT`, `STAFF_ASSIGNMENT_CONFLICT` |
| `422` | `CAPABILITY_BRANCH_MISMATCH` (Phase 3I), `STAFF_ASSIGNMENT_BRANCH_MISMATCH` (Phase 3J), `APPOINTMENT_BRANCH_MISMATCH` (Phase 3K: service booked under the wrong branch), `QUEUE_BRANCH_MISMATCH` (Phase 3L: queue joined under the wrong branch) |
| `500` | `INTERNAL_ERROR` (generic body; detail logged server-side only) |

Filter-level rejections (missing/invalid bearer token, handled before MVC
dispatch) keep their minimal `{"error","message"}` shape — they cannot
reach the MVC advice. The login endpoint's statuses/messages are unchanged
by Phase 3G; only the envelope is now shared (see `auth.md`).

## Tenant & authorization

Tenant scope flows from the JWT (`TenantContextFilter` installs the
organization scope; services verify branch ownership). Client-supplied
tenant ids are never authorization. Role checks use `TenantAccessPolicy`
only — no controller `if`-statements, no second framework.

## JSON representations

- `Instant` / `LocalDate` / `LocalTime` → ISO-8601 strings (never
  database syntax).
- `ZoneId` → IANA string (`"Asia/Kolkata"`).
- UUIDs → canonical strings. Enums → name strings, never ordinals.

## Pagination / sorting / filtering (convention set, code deferred)

No list endpoint exists yet, so no pagination code ships in this phase.
When the first list endpoint arrives it uses (implemented in Phase 3H —
`common/api/ApiPageRequest` + `ApiPage`; per-endpoint contract in
[`branches.md`](branches.md)):

- Query params `page` (0-based, default 0), `size` (default 20, max 100),
  `sort` (explicit per-endpoint allow-list only — never raw JPA property
  names, never SQL fragments).
- Response `{content: [...], page, size, totalElements, totalPages}`.
- Filtering is explicit per-endpoint params (`status=ACTIVE`), never a
  generic `filter=` query language.

## What this phase deliberately omits

Complete CRUD for any domain, PUT/PATCH, idempotency keys, request-id
tracing (revisit with observability), analytics, notifications, OAuth/SSO,
GraphQL/gRPC/gateways, and the frontend. Transactions stay at the
application/service boundary — never on controllers. Concurrency behavior
(appointment exclusions, queue claims, sessions) is untouched.
