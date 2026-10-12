# ADR-014: Queue Aging & Starvation Prevention

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 2H — Queue Aging & Starvation Prevention)
- Relates to: AGENTS.md §§2/7, ADR-012, `queue/QueueAgingProperties`

## Context

Phase 2F's strict priority + FIFO lets a continuous stream of
higher-priority arrivals delay NORMAL customers indefinitely. The fix must
preserve priority's business meaning (not degenerate to oldest-first),
stay deterministic and explainable, remain atomic under the existing
`SKIP LOCKED` claim, and never mutate the stored priority.

## Decision

- **Derived numeric score, computed in SQL at claim time:**
  `score = baseWeight + min(floor(waitMinutes / intervalMinutes), maxPromotions)`
  with `PRIORITY = 1`, else `0`; ordering is `EMERGENCY-first`,
  then score `DESC`, then `joined_at`, then id. Persisted
  `QueuePriority` is never modified — base fact vs effective rank stay
  separate for audit and analytics.
- **EMERGENCY is a structural hard ceiling**, not a score cap: a separate
  leading sort key always precedes it. No aged entry of any base priority
  can ever outrank a fresh EMERGENCY — answered and tested, not assumed.
- **Policy** (`queue.aging`, static config, restart to change — project
  policy, not industry standard): `enabled` (default true),
  `interval-minutes: 15` (one service-length of waiting per step),
  `max-promotions: 3` (an aged NORMAL durably competes with fresh
  PRIORITY). Disabled reproduces the exact Phase 2F order (tested).
  `@Min` constraints fail fast at startup (verified: interval 0 refuses
  to boot).
- **Actual guarantee** (not oversold): an aged NORMAL (≥1 interval)
  precedes every newer NORMAL and every PRIORITY it has out-waited; only
  continuous EMERGENCY arrivals can still starve it. Starvation is bounded
  everywhere else — proven by the starvation-scenario test, not claimed.
- **Clock reuse**: the claim takes epoch seconds from the project `Clock`
  (deterministic under test control; also sidesteps the known
  `Instant`-binding quirk in native queries). No sleeps in tests — fixed
  clock plus backdated joins. No new time abstraction.
- **Java-side sorting rejected**: fetching candidates to rank in memory
  would split select from lock (TOCTOU) and drag unbounded rows per call;
  the expression lives in the claim query where selection stays atomic.
- **No schema change, no migration**: ranking derives from `joined_at` +
  config; the existing waiting-entry index still drives candidate access
  (small per-queue working sets — documented, unbenchmarked, no Redis).
- **No priority mutation, no scheduled jobs, no aging column** — the three
  rejected designs from the brief, each avoided for its stated reason
  (write churn/races, audit ambiguity, schema noise).

## Alternatives Considered

1. **Oldest-first FIFO.** Rejected: destroys priority's business meaning.
2. **Mutating stored priority on a schedule.** Rejected: write churn,
   races, audit ambiguity — the brief's explicit anti-pattern.
3. **Java-side candidate ranking.** Rejected: select-then-lock race window
   plus unbounded row transfer; breaks the atomic-claim design.
4. **Redis-based ranking.** Rejected: second source of truth for core
   ordering; PostgreSQL already holds the facts.
5. **Weighted random selection.** Rejected: nondeterministic — untestable
   guarantees, undebuggable incidents.
6. **Allowing aged NORMAL to outrank EMERGENCY.** Rejected: violates the
   business meaning of emergency; ceiling is structural.

## Consequences

- Long-waiting NORMAL entries now surface ahead of fresh PRIORITY ones
  (FIFO seniority breaks ties); EMERGENCY behavior is byte-for-byte the
  old behavior. All Phase 2F ordering/concurrency tests pass unchanged —
  with waits near zero the new query reduces to the old order.
- Test-discovered fact worth keeping: PostgreSQL `uuid` ordering is
  *unsigned* byte order while Java's `UUID.compareTo` is *signed* — the
  tie-break test replicates the database semantics explicitly.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019
  (the queue-aging reservation is fulfilled by this ADR).
