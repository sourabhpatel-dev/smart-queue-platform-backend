# ADR-012: Queue Engine — Cardinality, Tokens, Atomic Claim

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 2F — Queue & Walk-In Management Foundation)
- Relates to: AGENTS.md §§2/7, ADR-008, ADR-011, V7 migration

## Context

Walk-ins need a waiting line with tokens, priority order, and a "call
next" that two counters can invoke simultaneously without ever handing both
the same customer. All of it must work on PostgreSQL alone — no Redis, no
messaging — as plain application services.

## Decision

- **One logical queue per (branch, service)** (`UNIQUE`), shared by every
  capable counter — never one queue per counter. Counters serve queues
  dynamically through existing `CounterCapability` rows; no
  `queue.counter_id` exists. History lives on entries, so no retired queue
  rows complicate the model.
- **Entries carry branch-pinned ownership** (queue + customer composites,
  Phase 2B/2C pattern) plus a partial unique `(queue, customer)` over
  WAITING/CALLED/SERVING: one active entry per customer, re-joinable after
  terminal states.
- **Tokens are per (queue, business date) integers from a dedicated
  counter row** (`queue_token_counter`, business date in the branch zone).
  Allocation is `INSERT … ON CONFLICT DO NOTHING` (guarantees the day's row
  with no exception, no retry loop) followed by `SELECT … FOR UPDATE`
  (serializes concurrent joins on the row). Never `MAX()+1`, never
  `synchronized`/`AtomicInteger` — proven by an 8-thread race yielding
  exactly tokens 1–8. Numbers may gap under contention; uniqueness, not
  gaplessness, is guaranteed.
- **Strict priority, FIFO inside, UUID tie-break** (`EMERGENCY > PRIORITY >
  NORMAL`, then `joined_at`, then id) — deterministic and explicit, with NO
  aging. Starvation under strict priority is a documented trade-off owned
  by the later queue-algorithm phase, not a half-measure hidden here.
- **Atomic claim via `FOR UPDATE SKIP LOCKED`** in a single ordered native
  query across all queues a counter may serve: two concurrent callers can
  never receive the same row — the loser skips the locked row and takes the
  next eligible one instead of blocking. Proven by 2-counter (same counter
  racing itself) and 3-counter races asserting exact entry-set claims.
  `WAITING → CALLED` (+`calledAt`) happens in the same transaction as the
  lock.
- **Lifecycle as guarded entity transitions** (`WAITING→CALLED→SERVING→
  COMPLETED`, plus `WAITING|CALLED→CANCELLED|NO_SHOW`); `CALLED` stays
  separate from `SERVING` for the future ServiceSession boundary. No
  CALLED→WAITING requeue, no framework. Three focused services
  (join / call / lifecycle) instead of one giant queue service.
- **Appointments are NOT enqueued**: no auto-check-in, no arrival windows —
  those need product rules (late arrival, appointment priority) a later
  integration phase owns. Documented as future, not implemented.
- **No wait-time estimation**: no `estimatedWaitMinutes`, no duration
  history reads — the inputs exist (durations, positions) but prediction is
  its own phase.
- **No REST/DTOs**: services tested directly; HTTP waits for API hardening.

## Alternatives Considered

1. **One queue per counter.** Rejected: fragments the line, duplicates
   tokens, contradicts multi-service counters.
2. **`MAX(token)+1` / in-memory counters.** Rejected: textbook duplicate
   race; JVM locks don't span instances or transactions.
3. **Plain `SELECT then UPDATE` for call-next.** Rejected: two transactions
   read the same "next" row and both claim it — the exact bug the
   SKIP LOCKED query eliminates (proven by race tests).
4. **Blocking `FOR UPDATE` (no SKIP LOCKED).** Rejected: a second counter
   would stall behind the first's lock instead of taking the next eligible
   entry; skipping is the throughput-correct behavior here.
5. **Aging/weighted priority now.** Rejected: premature without measured
   starvation; strict order is honest and the algorithm phase is scoped.
6. **REQUIRES_NEW token allocation with catch-and-retry.** Attempted first,
   then removed: the isolated insert cannot see the caller's uncommitted
   queue row (FK violation), and continuing a session after a flush failure
   is unsupported Hibernate usage. The ON CONFLICT design has neither flaw
   — a genuine design correction, not a workaround.

## Consequences

- The queue works correctly under concurrency today (33 tests: ordering,
  capabilities, lifecycle, races, DB-level bypass rejections), without any
  infrastructure beyond PostgreSQL.
- Later phases add events (emitted around these same service methods),
  wait-time math (reads positions + durations), and aging (extends
  ordering) — none require remodeling.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
