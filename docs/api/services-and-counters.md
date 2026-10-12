# Services & Counters API (Phase 3I)

Branch service catalog and counter management (decided in
[ADR-024](../decisions/ADR-024-service-counter-capability-api.md)).
Generic conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.

## URL shape

Collections nest under the owning branch (ownership visible in the path,
scoped before any row is touched); item operations are flat:

```text
GET    /api/v1/branches/{branchId}/services[?page&size&sort&status]
POST   /api/v1/branches/{branchId}/services
GET    /api/v1/services/{serviceId}
PUT    /api/v1/services/{serviceId}
POST   /api/v1/services/{serviceId}/activate
POST   /api/v1/services/{serviceId}/deactivate

GET    /api/v1/branches/{branchId}/counters[?page&size&sort&status]
POST   /api/v1/branches/{branchId}/counters
GET    /api/v1/counters/{counterId}
PUT    /api/v1/counters/{counterId}
POST   /api/v1/counters/{counterId}/activate
POST   /api/v1/counters/{counterId}/deactivate

GET    /api/v1/counters/{counterId}/capabilities
POST   /api/v1/counters/{counterId}/capabilities        {"serviceId": "…"}
DELETE /api/v1/counters/{counterId}/capabilities/{serviceId}
```

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| List/get services & counters | ✅ own-org display | ✅ assigned branch | ✅ | ✅ |
| Create/update/activate/deactivate | ❌ 403 | ❌ 403 | ✅ | ✅ |
| List capabilities | ✅ own-org display | ✅ assigned branch | ✅ | ✅ |
| Add/remove capability | ❌ 403 | ❌ 403 | ✅ | ✅ |

Same semantics as branches: managers/admins organization-wide but never
platform-wide; foreign ids are scope-safe `404`s.

## Services

Response: `id`, `organizationId`, `branchId`, `name`, `description`
(nullable), `durationMinutes` (whole minutes — the storage unit, so no
sub-minute edge exists at this boundary), `status`, `createdAt`,
`updatedAt`. Create/update bodies carry `name` (≤150), `description`
(≤1000, optional), `durationMinutes` (≥1) — no ids, no ownership, no
`status`, no audit fields. Duplicate names at the branch → `409
SERVICE_NAME_CONFLICT` (constraint-arbitrated); same name at another
branch stays legal. Sort allow-list: `name,status,durationMinutes,
createdAt,updatedAt` + `id` tiebreak. Status filter `ACTIVE/INACTIVE`.

Service creation also provisions the branch+service walk-in queue in
the same transaction (see
[ADR-038](../decisions/ADR-038-queue-auto-provisioning.md)): a
successful `POST …/services` guarantees the queue exists (`ACTIVE`,
independent kill-switch — no status mirroring). The service response
is unchanged (no queue id embedded); the queue appears in the branch
queue listing and accepts joins once branch, service, and queue are
all `ACTIVE`.

## Counters

Response: `id`, `organizationId`, `branchId`, `name`, `status`,
timestamps. Capabilities are never embedded. Create/update bodies carry
the name only (≤100); duplicate → `409 COUNTER_NAME_CONFLICT`. Sort
allow-list: `name,status,createdAt,updatedAt` + `id` tiebreak. Status
filter accepts `ACTIVE/INACTIVE/MAINTENANCE`. Deactivation keeps
capabilities, so reactivation restores service.

## Capabilities

The `CounterCapability` association (same branch pinned by the V3
composite foreign keys — the backstop no code path bypasses). Listing
returns a plain array ordered by service name (bounded per-counter set,
not a paginated envelope). Each entry carries the served service's `id`,
`name`, and `status` inline — no second call for displays, no full
service embedding.

- Add: `201` + `Location` (`…/capabilities/{serviceId}`) + the view.
  Checks in order: counter scope (`404`), service scope (`404`),
  same-branch (`422 CAPABILITY_BRANCH_MISMATCH`), then the unique
  constraint arbitrates duplicates (`409 CAPABILITY_ALREADY_EXISTS`).
- Remove: `204`. Deletes only the association row — never the service,
  the counter, or any appointment/queue/session state. Missing
  association → `404 CAPABILITY_NOT_FOUND` (signals the stale view;
  documented choice over silent success).

## Lifecycle

PUT full-replacement everywhere (no PATCH), status/ownership immutable
in payloads, idempotent activate/deactivate, no DELETE for services or
counters (dependents must never cascade). Deactivated rows stay visible.

## Error codes (this surface)

`SERVICE_NOT_FOUND`, `SERVICE_NAME_CONFLICT`, `COUNTER_NOT_FOUND`,
`COUNTER_NAME_CONFLICT`, `CAPABILITY_ALREADY_EXISTS`,
`CAPABILITY_NOT_FOUND`, `CAPABILITY_BRANCH_MISMATCH` (422 — the only
new status in the contract, for requests well-formed but violating the
same-branch invariant), plus the shared `VALIDATION_ERROR`,
`INVALID_PAGE/SIZE/SORT/STATUS`, `UNAUTHENTICATED`, `FORBIDDEN`,
`BRANCH_NOT_FOUND` codes.

## Intentional omissions

No service/counter deletion or transfer, no capability metadata, no
cross-branch links, no batched capability writes.
