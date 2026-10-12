# Queues API (Phase 3L)

Walk-in queue operations over the untouched engine (decided in
[ADR-027](../decisions/ADR-027-queue-management-api.md)). Generic
conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.
Realtime/WebSocket behavior is unchanged (3C/3F); this surface only feeds
it through the existing events.

> **Identity model note (ADR-040, Phase 4R.3 implemented).** Customers
> are platform-level identities: one account joins queues at multiple
> ACTIVE organizations. The branch+queue in the request path name the
> scope (validated ACTIVE); the entry's organization is the row's
> historical context. Reads are owner-scoped across organizations for
> customers, organization-scoped for operational roles; cancel keeps
> explicit `403` for non-owner customers. Live position/wait reads and
> lifecycle commands run through an explicit per-request tenant-context
> bridge installed from the authorized row's organization.

## URL shape

```text
POST /api/v1/branches/{branchId}/queues/{queueId}/entries   {priority?}
GET  /api/v1/branches/{branchId}/queues
GET  /api/v1/branches/{branchId}/queues/{serviceId}/estimate-join
GET  /api/v1/queues/{queueId}
GET  /api/v1/queues/{queueId}/entries[?page&size&sort&status&priority&customerId]
GET  /api/v1/queue-entries/{entryId}
POST /api/v1/counters/{counterId}/call-next
POST /api/v1/queue-entries/{entryId}/start-serving   {counterId}
POST /api/v1/queue-entries/{entryId}/complete
POST /api/v1/queue-entries/{entryId}/cancel
POST /api/v1/queue-entries/{entryId}/no-show
```

Deliberately counter-centric claiming (not queue-centric): the atomic
claim selects across every queue the counter serves, so the endpoint
names the counter and returns whatever was selected. A queue-scoped
claim route would misrepresent the operation — a legally selected
sibling-queue entry cannot be "put back". There is no queue creation,
no queue status command, and no generic status write: queues are
provisioned automatically when their service is created (same
transaction; pre-existing pairs backfilled — see
[ADR-038](../decisions/ADR-038-queue-auto-provisioning.md)), so there
is no creation flow to expose and nothing to configure beyond the
independent `ACTIVE`/`INACTIVE` walk-in switch.

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| Join (self) | ✅ NORMAL only | ✅ NORMAL (+triage at assigned branch) | ✅ + triage | ✅ + triage |
| List queues (branch) | ✅ display | ✅ assigned branch | ✅ | ✅ |
| Queue snapshot / entries list | ❌ 403 | ✅ assigned branch | ✅ | ✅ |
| Read own entry | ✅ | ✅ (own) | ✅ (own) | ✅ (own) |
| Read others' entries | ❌ 404 | ✅ assigned branch | ✅ | ✅ |
| call-next / start / complete / no-show | ❌ 403 | ✅ assigned branch | ✅ | ✅ |
| Cancel own | ✅ | ✅ (own) | ✅ (own) | ✅ (own) |
| Cancel others' | ❌ 403 | ❌ 403 | ✅ branch mgmt | ✅ branch mgmt |

Triage (`PRIORITY`/`EMERGENCY` on join) needs operational authority —
customers and unassigned staff get `403`; unknown names are `400`.
Managers/admins stay organization-scoped; foreign ids are scope-safe
`404`s. Item reads are privacy-first (non-owners outside operational
scope get `404`, never a peek); commands deny explicitly (`403`).

## Pre-join estimate

`GET /api/v1/branches/{branchId}/queues/{serviceId}/estimate-join` returns
an aggregate wait estimate for a new joiner who has not yet entered the
queue. The estimate reuses the same scope and formula as the per-entry
estimator — a new entry would rank last among currently WAITING entries in
the capability set. The operation is read-only: no `QueueEntry` is created
or mutated. The response exposes only aggregate information (waiting
count, estimated wait, capable counter count, active session count,
calculation timestamp) — never customer PII, tokens, or individual session
details.

`estimatedWaitMinutes` is `null` when the queue is inactive or no active
capable counter exists — the caller must treat this as a snapshot, not a
promise, and render an appropriate UI.

Validation: branch must exist — in the caller's organization for
operational principals, at any ACTIVE organization for platform-level
customers (`404`); service must exist and belong to the branch (`422`);
queue must exist for the branch/service pair (`404`); queue must be
active (`400 QUEUE_INACTIVE`); service must be active (`400
SERVICE_INACTIVE`).

## Joining

Customer is always the principal — every role joins for itself, no
delegated joining exists. Token, business date, position, and wait are
server-derived (`201` + `Location` to the entry). Duplicate active
entries → `409 QUEUE_CONFLICT`; inactive queues → `409
QUEUE_NOT_ACTIVE`; path-branch/queue mismatch → `422`; foreign rows →
`404`.

## State, ordering, waits

- The snapshot (`GET /queues/{id}`) rebuilds from PostgreSQL on every
  read: waiting entries in claim rank order (EMERGENCY, aging score,
  joinedAt, id — the exact order `callNext` serves), then
  called/serving, each with token, position, and estimated wait — plus
  ids and customers, which the anonymous realtime view omits by design.
  Never served from Redis.
- Entry positions: 1-based WAITING rank (absent after a concurrent claim
  → null), `0` for CALLED/SERVING, null for terminal rows. Waits come
  from the shared estimator; null means unknown (zero capable counters,
  inactive queue, terminal) — never silent zero except genuinely-now
  states. Zero-capacity snapshots still rank (position without a wait).
- The paged entries listing sorts stored fields only (`joinedAt`
  default, `tokenNumber`, `status`, `priority`, `createdAt`,
  `updatedAt`) and carries no live positions/waits — rank order cannot
  page, so the snapshot owns it. Bulk-page estimate fan-out is the
  documented cost bound (same profile as the realtime builder).

## Lifecycle

`start-serving` (CALLED → SERVING + session atomically; counter must be
active, same-branch, capable), `complete` (SERVING → COMPLETED + session
end), `cancel` / `no-show` (WAITING/CALLED → terminal, history
preserved, active slot freed for immediate re-join). Terminal refusals
→ `409 INVALID_QUEUE_STATE` (domain wins over idempotency, like 3K).
Empty claim sets → `409 NO_WAITING_ENTRY` (documented, not a 500).
Concurrent claims never share a row (SKIP LOCKED preserved, proven over
HTTP with two racing callers).

## Events, realtime, Redis

Every command flows through the engine, so application events,
the bridge, and the projection behave exactly as before (proven: a REST
join lands in the Redis snapshot before the 201 returns). REST never
reads Redis and installs no Redis locks — with Redis down, joining,
state reads, and claiming all still succeed from PostgreSQL (proven by
a dedicated degradation test). Correctness never depends on freshness.

## N+1 note

Snapshot and single reads fan out like the realtime builder (rank query
+ per-entry estimates over bounded live sets) — accepted, documented,
not rewritten. The paged listing stays flat for the same reason.

## Error codes (this surface)

`QUEUE_NOT_FOUND` (404), `QUEUE_BRANCH_MISMATCH` (422),
`INVALID_PRIORITY` (400), plus the existing `QUEUE_ENTRY_NOT_FOUND`,
`INVALID_QUEUE_STATE`, `QUEUE_CONFLICT`, `QUEUE_NOT_ACTIVE`,
`NO_WAITING_ENTRY`, `INVALID_QUEUE_REQUEST`,
`COUNTER_CANNOT_SERVE_QUEUE`, `COUNTER_NOT_FOUND` codes.

## Intentional omissions

Queue creation/status commands, generic status writes, delegated
joining, customer triage, staff cancellation for others, appointment
check-in integration, requeue-after-no-show, availability search.
