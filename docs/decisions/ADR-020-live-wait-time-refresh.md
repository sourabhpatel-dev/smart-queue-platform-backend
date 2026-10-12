# ADR-020: Live Wait-Time Refresh & Projection Freshness

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3D — Live Wait-Time Refresh & Projection Freshness)
- Relates to: AGENTS.md §§2/7/9/10, ADR-014, ADR-015, ADR-016, ADR-017,
  ADR-018, `queue/realtime/`
- Note: ADR-019 stays reserved for the shared-database multi-tenancy detail
  deferred by ADR-011/015/016/017/018; this phase takes the next free number.

## Context

Phase 3C delivers queue snapshots to Redis and STOMP purely on committed
business events. But queue rank and wait estimates are functions of the
project Clock as well as of stored rows: as minutes pass, aging steps
accrue and every waiting entry's position value drifts, with zero
`INSERT`/`UPDATE`/`DELETE` in PostgreSQL. An event-only projection is
therefore structurally unable to stay fresh — a client watching a quiet
queue sees a frozen number that the estimator would no longer compute.
Phase 3D must add bounded time-driven freshness without redesigning the
estimator, the projection model, or the delivery topology.

## Decision

- **Freshness requirement (measurable, not "real-time")**: every queue that
  holds live entries is rebuilt from PostgreSQL at least once per
  `queue.realtime.refresh.interval` (default one minute), and the refreshed
  snapshot reaches Redis and — when its live content changed — the queue's
  STOMP topic. Quiet queues drift by at most one interval between rebuilds;
  no latency guarantee is claimed beyond that bound.
- **Interval choice (deliberate, not aggressive)**: one minute default.
  Roughly fifteen rebuilds per 15-minute aging step (rank drift is picked
  up promptly, far from per-second chatter); well below the 5-minute
  snapshot TTL (a healthy scheduler renews each key about five times per
  TTL window, so expiry means the scheduler is actually down); one ranked
  query plus estimator reads per active queue per minute against small
  operational tables (stated without benchmark claims).
- **Reuse, not reinvention**: the tick calls `buildSnapshot` — the exact
  Phase 3C builder over `QueueRankingSql` + `QueueWaitTimeEstimator` — so
  ordering, aging, capacity, and waits cannot drift from event-driven
  snapshots. Persisted `QueueEntry.priority` is never mutated; aging stays
  derived. The payload contract (`QueueRealtimeState`) is unchanged:
  clients cannot tell an event snapshot from a scheduled one.
- **Active-queue discovery**: one query returning distinct queue ids for
  `ACTIVE` queues with at least one `WAITING`/`CALLED`/`SERVING` entry
  (`QueueRepository.findIdsRequiringRealtimeRefresh`). Empty queues have
  nothing to display and inactive queues hold history only — neither is
  refreshed; their Redis keys, if any, expire. No connection/subscriber
  tracking exists, so no such optimization was built.
- **Redis TTL relationship**: `refresh interval < TTL` (1 min < 5 min) as
  related but distinct concepts — the interval bounds drift, the TTL bounds
  how long a snapshot may be served without any successful refresh.
  An unchanged rebuild still rewrites the key (TTL renewal) but skips the
  WebSocket broadcast.
- **Snapshot comparison**: `sameLiveContent` compares everything except
  `generatedAt` (which changes on every rebuild by construction — comparing
  it would make "unchanged" unreachable). Rank order is significant: the
  waiting-list comparison is order-sensitive, so an aging-driven reorder
  with an identical entry set counts as a material change.
- **Stale-write protection**: the tick reads the raw Redis value
  *before* rebuilding, then writes via an atomic Lua compare-and-set
  against that observation. A concurrent event-driven refresh in between
  fails the guard instead of being reverted to older state; the tick then
  rebuilds once from current PostgreSQL state and stores that. Raw-JSON
  comparison (not timestamps) is the guard: two builds at the same Clock
  instant can still differ in content, and the newer build is not always
  the fresher read, so timestamp ordering would be the wrong criterion.
- **Scheduler behavior**: Spring `@Scheduled(fixedDelayString)` on
  `QueueRealtimeRefreshService.scheduledRefresh` — fixed delay, never
  fixed rate, so a slow pass cannot overlap itself; no custom threads, no
  `while(true)`, no Quartz. The service is orchestration only (discover →
  rebuild → guarded store → conditional publish); per-queue failures are
  caught, logged with the queue id, and skipped, and the tick itself never
  throws. No method here mutates any business state.
- **Failure semantics**: Redis, broker, or PostgreSQL failures degrade to
  `SKIPPED` with a warn log. The scheduler performs no business writes, so
  there is nothing to roll back; a failed broadcast still leaves the fresh
  snapshot in Redis. The application starts and serves with Redis down
  (unchanged Phase 3B contract).
- **Multi-instance limitation (documented, not solved)**: two instances
  duplicate the refresh work and resolve last-write-wins between two fresh
  rebuilds — harmless because both derive from committed PostgreSQL state,
  and the guarded write still blocks a stale overwrite. No ShedLock,
  Redis lock, leader election, or external scheduler: unjustified for a
  best-effort derived-state refresh in a modular monolith.
- **Configuration**: `queue.realtime.refresh.enabled` (default true) +
  `interval` (default `PT1M`, ISO-8601 — the `@Scheduled` parser only
  accepts ISO-8601 or millis, so Boot-style `1m` is documented as
  unsupported here). Static, restart to change; zero/negative/unparseable
  intervals fail fast via `@AssertTrue`, in the `QueueAgingProperties`
  style. Disabling removes the refresh bean; event-driven snapshots are
  unaffected.

## Alternatives Considered

1. **Faster/more granular refresh (seconds).** Rejected: aging moves in
   15-minute steps and service durations are tens of minutes — sub-minute
   rebuilds would multiply database, Redis, and socket traffic for no
   visible change.
2. **Refreshing all queues blindly.** Rejected: the discovery query bounds
   each tick to queues with live entries; empty/inactive queues cost
   nothing.
3. **Timestamp-ordered writes (`generatedAt` comparison).** Rejected with
   evidence: a scheduled build stamped *after* an event build can still
   carry *older* PostgreSQL state, and equal Clock instants can carry
   different content — timestamps order builds, not reads. Raw compare-and-
   set guards the actual race.
4. **Refreshing TTL separately from content.** Rejected: rewriting the key
   on every tick is one code path and keeps TTL semantics obvious; the
   broadcast (the expensive, client-visible part) is what gets skipped.
5. **Distributed locks / ShedLock / Quartz / external scheduler.**
   Rejected: no demonstrated multi-instance harm — duplicate fresh rebuilds
   are idempotent, and the guard prevents regression. Revisit with measured
   contention, not anticipation.
6. **Outbox / event log / Kafka / Redis Streams for ordering.** Rejected:
   the race is closed with one Lua script and one retry; durable ordering
   infrastructure would not make derived snapshots any more correct.
7. **Per-entry incremental patching from Redis state.** Rejected outright:
   projections rebuild from PostgreSQL or not at all (ADR-018 holds).

## Consequences

- Displays observe wait/rank drift bounded by the configured interval on
  top of immediate event-driven updates, on the unchanged payload and topic
  contract.
- 24 new tests (14 refresh incl. threshold/cap/emergency/zero-capacity/
  multi-counter/called-serving/terminal/discovery/event-interaction/stale/
  broker-failure, 3 scheduling incl. a live-tick wire proof, 2 disabled,
  5 properties incl. fail-fast validation); full suite green; no migration
  (V1–V8 untouched); no new dependencies (scheduling ships with Spring).
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019
  (unchanged).
