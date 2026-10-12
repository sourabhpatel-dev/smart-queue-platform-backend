# ADR-013: Service Sessions — Actual Service Interactions

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 2G — Service Session & Queue Operational Completion)
- Relates to: AGENTS.md §§2/7, ADR-006, ADR-012, V8 migration

## Context

The queue knows lifecycle states, but nothing records the actual service
interaction — who served whom, where, for how long. Future wait-time math
needs measured durations, not estimates of estimates. The session must also
close two races (concurrent starts, concurrent completions) without
duplicating rows or stranding half-states.

## Decision

- **Session separate from entry**: `QueueEntry` is queue lifecycle;
  `ServiceSession` is the interaction (entry + counter + service +
  branch + started/ended instants). Analytics later reads durations from
  timestamps — never from a stored duration field. Lives in the `queue`
  package (created by its lifecycle), not the catalog `service` package.
- **One session per entry** (`UNIQUE(queue_entry_id)`) — the concurrent-
  start backstop. No retries-as-sessions; re-service is a future model.
- **Branch-pinned with composites**: the row carries `branch_id`;
  `(counter, branch)` and `(service, branch)` composite FKs pin both legs
  (targets exist from V3). Entry↔session *service* equality (same service,
  not just same branch) stays application-enforced — exposing it would
  require denormalizing onto the closed `queue_entry` table — guarded in
  the constructor, validated by the lifecycle service, and honest about the
  boundary in a test that proves the DB permits what the domain forbids.
- **No status enum**: `endedAt NULL` = running, set + `CHECK(ended_at IS
  NULL OR ended_at > started_at)` = delivered. Strict `>` (zero-length
  interactions are data errors); entities can never reopen an ended
  session.
- **Row-locked transitions**: `startServing`/`complete` load the entry
  `FOR UPDATE` first — the loser blocks, re-reads the winner's committed
  state, and fails its guard cleanly (`QueueEntryStateException`), so
  exactly one session starts and exactly one end lands. Proven by two
  dedicated race tests. No `@Version`: nothing here is a lost-update
  vector once the lock orders the writers.
- **Lifecycle service reworked, not forked**: `startServing(entryId,
  counterId)` and `complete(entryId)` now coordinate entry + session in
  one transaction each (SERVING without a session, or COMPLETED with an
  open one, can never commit). `cancel`/`markNoShow` untouched.
- **Project `Clock` bean** (UTC system; fixed in tests): session facts are
  recorded from injectable time, giving exact-timestamp tests without
  wall-clock flakiness. Audit `createdAt/updatedAt` stay on entity
  callbacks — the Clock is for operational facts only.
- **No counter runtime states** (`BUSY`/`SERVING` untouched in
  `CounterStatus`): an active session *is* the occupancy signal. No
  wait-time fields, no averages, no aging, no events — the session only
  banks the facts later phases compute from.

## Alternatives Considered

1. **Session state on QueueEntry alone** (no entity). Rejected: no place
   for counter identity or measured duration; analytics would mine queue
   state for operational facts.
2. **Full DB enforcement incl. service equality** (denormalize onto
   queue_entry / triggers). Rejected: churn on a closed table (or trigger
   maintenance) for a check the single writer path already guarantees —
   documented split instead.
3. **Session status enum.** Rejected: timestamps already encode the three
   states with no sync-to-maintain.
4. **`@Version` optimistic locking.** Rejected: row locks already order
   the only writers; versioning would add a column with no race to win.
5. **`Instant.now()` scattered in services.** Rejected for new code: the
   Clock bean makes session times exactly assertable (10:00 → 10:12:30).
6. **Counter BUSY/SERVING states.** Rejected: duplicates session state and
   would need its own consistency protocol; deferred to the operational
   phase if ever required.

## Consequences

- Wait-time/analytics phases read `startedAt/endedAt` as ground truth;
  nothing else in the system records measured service time.
- The lifecycle service signature changed (`startServing` takes a
  counter): existing 2F tests were updated accordingly — a deliberate,
  documented evolution, not drift.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
