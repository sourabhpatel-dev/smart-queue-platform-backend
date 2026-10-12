# ADR-018: Real-Time Queue State & WebSocket/STOMP

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3C — Real-Time Queue State & WebSocket/STOMP)
- Relates to: AGENTS.md §§2/7/10, ADR-012, ADR-016, ADR-017,
  `queue/realtime/`, `infrastructure/websocket/`

## Context

Business facts already flow as post-commit events, and Redis stands ready
for derived state — but no client can observe the queue live. Phase 3C must
close that last mile (projection → delivery) without letting delivery
mechanics leak into business logic or reversing the PostgreSQL-first order.

## Decision

- **Snapshot, not event stream**: one immutable `QueueRealtimeState` per
  queue (tenant ids, status, counts, rank-ordered waiting rows with waits,
  called/serving tokens, `generatedAt`) travels Redis and WebSocket
  identically — one model, two transports. Missed messages heal on the next
  snapshot; replays are harmless. No revision sequence exists to invent;
  `generatedAt` marks recency with documented last-write-wins limits.
- **Public payload discipline**: tokens, never customer UUIDs/emails/roles
  (proven by a test asserting their absence from the wire JSON); queue ids
  stay for correlation. No auth yet — stated plainly, with tenant-scoped
  paths ready for the security phase to authorize.
- **Projection = rebuild from PostgreSQL every time** (shared rank query +
  shared estimator — zero ranking/wait duplication): idempotent by
  construction, crash-safe by design, and the recovery path (delete key →
  refresh → identical state, tested). TTL 5 min bounds staleness; expiry
  just triggers on-demand rebuild. No counters incremented from events.
- **AFTER_COMMIT → Redis → topic**, in that order, inside a bridge that
  catches everything — including the session→queue lookup, so no
  post-commit code path can reach the caller: a Redis/broker failure can
  never roll back (or fail the caller of) an already-committed
  transaction — graceful degradation by structure. Only the 8 queue/session
  events feed it; appointments stay out (queue scope only, per brief).
- **Destinations**: `/topic/organizations/{o}/branches/{b}/queues/{q}`
  (subscribe) + `/app/…/snapshot` (request triggers a fresh publish to the
  topic — the initial-state mechanism, no REST bootstrap, no user queues
  without principals). Path ids verified against the queue's real scope.
- **Simple in-process broker, no SockJS, open origins by documented
  decision** (no auth layer exists for either to mean anything yet; both
  tighten with security). No external broker — single deployable, and
  Redis already holds the shareable state a scaled-out design would need.
- **Outbound TEXT is explicit JSON with `application/json`**: discovered
  the hard way that STOMP `String` conversion silently drops untyped
  frames client-side — hence the header, and `byte[]` test decoding.
- **Observability = warn-with-queue-id logging** (never customer data);
  metrics platforms deferred with the rest.

## Alternatives Considered

1. **Mutation messages (ENTRY_ADDED/REMOVED).** Rejected: clients must
   reconstruct history; snapshots self-heal.
2. **Per-event incremental Redis patching.** Rejected: duplicates lifecycle
   logic in a second place and rots on missed events; rebuild is one code
   path.
3. **External STOMP broker (RabbitMQ/Kafka).** Rejected: no scale driver,
   no ops story for it; simple broker + Redis covers the monolith.
4. **SockJS.** Rejected: no legacy-browser requirement.
5. **REST bootstrap endpoint.** Rejected: the STOMP request-reply covers
   initial state with no extra surface.
6. **Scheduled snapshot refresh.** Rejected: event-driven is sufficient;
   time-drift documented honestly instead.
7. **Appending wait-time/aging computation anew.** Rejected outright —
   existing estimator and rank fragment reused verbatim.

## Consequences

- Displays can subscribe today; later phases add auth on these exact
  paths, Redis-backed history, and notifications without remodeling.
- 16 new tests (10 projection incl. rebuild/idempotency/TTL/privacy, 4
  live-STOMP incl. isolation, 2 Redis-outage degradation proving committed
  transactions survive); 209/209 green; no migration (V1–V8
  untouched); one dependency (`spring-boot-starter-websocket`).
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019.
