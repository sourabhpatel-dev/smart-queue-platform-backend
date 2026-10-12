# Service Sessions API (Phase 3M)

Operational visibility over measured service interactions. Generic
conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.
The 3L entry commands remain the sole lifecycle authority: this surface
is read-only by design and transitions nothing.

## URL shape

```text
GET /api/v1/service-sessions[?page&size&sort&branchId&counterId&serviceId&customerId&queueEntryId&active&from&to]
GET /api/v1/service-sessions/{sessionId}
GET /api/v1/counters/{counterId}/current-session
GET /api/v1/counters/{counterId}/service-sessions[?page&size&sort]
```

## The model, honestly

Sessions carry no status enum: `active` is derived (`endedAt == null`
means running — the same definition the estimator and lifecycle use),
and `durationMinutes` is derived (`endedAt − startedAt`, present only
once ended). Neither is stored, and neither grows into utilization/SLA
modeling here. Each view links the full operational chain (branch,
counter, entry, queue, service, customer) without touching
appointments, which are unrelated.

## Authorization matrix

| Operation | CUSTOMER | STAFF | MANAGER | ADMIN |
|---|---|---|---|---|
| List sessions | ❌ 403 | ✅ assigned `branchId` required | ✅ + facets | ✅ + facets |
| Read session | ❌ 404 (even own) | ✅ assigned branch | ✅ | ✅ |
| Current session | ❌ 403 | ✅ assigned branch | ✅ | ✅ |
| Counter history | ❌ 403 | ✅ assigned branch | ✅ | ✅ |

Customers have no session views at all — their entry reads already
carry status and timestamps, so a second customer-visible surface
would only duplicate personal data. Items are privacy-first (same-org
outsiders get scope-safe `404`s); collections deny explicitly (`403`).
Managers/admins stay organization-scoped; foreign ids are `404`s both
directions.

## Current session

"Current" reuses the domain definition exactly (`endedAt IS NULL`,
newest wins if operations ever overlap). An idle counter has no
current session — the `404 SERVICE_SESSION_NOT_FOUND` doubles as the
derived occupancy signal (`200` occupied, `404` free). No
busy/available flag is persisted anywhere, and none is duplicated here.

## Listing

Paginated 20/100 like every list, defaulting to newest-first
(`startedAt` desc — the operationally useful order, via the shared
default-direction support). Sort allow-list: `startedAt`, `endedAt`,
`createdAt`, `updatedAt` + `id` tiebreak. Facets (`branchId`,
`counterId`, `serviceId`, `customerId`, `queueEntryId`) are each
scope-checked (`404` when foreign); mismatched combinations return
empty. `active` selects running vs ended; `from`/`to` bound
`startedAt` (ISO-8601, `INVALID_TIME_RANGE` when inverted — the window
edges reuse the 3K presence-flag pattern, since PostgreSQL cannot type
bare null `Instant` binds).

## N+1 note

Views traverse branch/counter/service/entry/customer lazily, like every
other list surface — accepted at the 100-row cap, not rewritten. No
fetch graphs, no projections framework, no new indexes (V8 deliberately
created none for counter reads; correctness never depends on one).

## Lifecycle integration

Every row here is written by the 3L commands (`start-serving` opens,
`complete` ends): join → call → start shows a running session with a
live current-session read; complete ends it (idle 404, history keeps
the ended row with its derived duration). Cancel/no-show paths create
no sessions — the views stay consistent with the domain by
construction, because they read the same rows.

## Redis independence

PostgreSQL answers every read; Redis is never consulted and no snapshot
is involved. Session correctness cannot degrade with freshness
infrastructure.

## Error codes (this surface)

`SERVICE_SESSION_NOT_FOUND` (404: unknown, foreign, invisible, or
idle), plus the shared `BRANCH_NOT_FOUND`, `COUNTER_NOT_FOUND`,
`SERVICE_NOT_FOUND`, `USER_NOT_FOUND`, `QUEUE_ENTRY_NOT_FOUND`,
`INVALID_STATUS`-family, `INVALID_TIME_RANGE` codes.

## Intentional omissions

Lifecycle commands (3L owns them), status writes, utilization/SLA/
productivity metrics, appointment linkage, availability search, and —
deliberately — an ADR: this phase applies the 3H–3K templates without
introducing a new architectural decision.
