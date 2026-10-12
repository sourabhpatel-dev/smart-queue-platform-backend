# ADR-015: Wait-Time Estimation (Deterministic, State-Derived)

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 2I — Wait-Time Estimation)
- Relates to: AGENTS.md §§2/7, ADR-012, ADR-014, `queue/QueueWaitTimeEstimator`

## Context

Customers and future operations need "how long until service starts" without
inventing ML, history we don't have, or real-time infrastructure. The answer
must agree with actual claiming, stay explainable, and never pretend to
precision the model cannot support.

## Decision

- **Definition**: whole minutes from now until the entry's expected service
  start — not time-in-queue, service duration, or a historical average.
- **Model**: position `P = ahead + active` in service order, where `ahead`
  counts WAITING entries the claim would serve first plus CALLED entries,
  and `active` counts open sessions on capable counters; with `C` active
  capable counters of standard duration `S`, `wait = floor(P / C) × S`.
  An active session counts one full unit (remaining time is unknowable
  from stored data — documented approximation, not hidden).
- **One ordering, single-defined**: `QueueRankingSql` holds the rank
  expression; claim and estimator assemble from it, so "ahead" means
  "would be claimed first" by construction — proven by a test asserting
  estimate order equals actual claim sequence.
- **Capacity**: ACTIVE counters in-branch capable of the entry's service
  (existing capability rules); zero capacity → no estimate, never silent
  zero. Terminal entries → no estimate; CALLED/SERVING → 0; inactive
  queue → no estimate.
- **Whole minutes, derived only**: no `estimated_wait` column, no stored
  refresh, no seconds of false precision. Point-in-time snapshot semantics
  documented (moves with queue state, never a promise).
- **Clock-driven, fixed in tests** (existing project Clock; epoch seconds
  into SQL sidestep the known `Instant`-binding quirk). No sleeps.
- **No migration, no new index**: nothing stored (V1–V8 untouched); the
  waiting-entry index still drives access; session counting scans a small
  operational table. Stated without benchmark claims.

## Alternatives Considered

1. **Row-value tuple comparison for "ahead".** Attempted first, then
   removed with evidence: PostgreSQL evaluates tuple `<` with a UUID bind
   parameter incorrectly (silently wrong counts, no error — reproduced in
   isolation). Ordered-id-list + Java `indexOf` replaces it: same ORDER BY
   text, zero exotic semantics.
2. **Java-side full ranking.** Rejected: unbounded row transfer plus a
   second ordering implementation drifting from the claim.
3. **Historical-average prediction / ML.** Rejected: no dataset exists;
   standard duration is the only honest duration input.
4. **Stored estimate column + refresh jobs.** Rejected: write churn for a
   value computable on demand; jobs belong to no current requirement.
5. **Counting all WAITING (priority-blind).** Rejected: contradicts claim
   order — the estimate would disagree with reality by construction.
6. **Redis-cached projections.** Rejected: second source of truth with no
   scale driver; PostgreSQL is fast enough at this size, unmeasured beyond
   that (no performance claims made).

## Consequences

- REST/WebSocket/notifications later consume `estimate(entryId)` unchanged;
  the result record carries its own basis (ahead/active/counters) so future
  UIs can explain the number.
- Test-found rule worth keeping: never trust row-value tuple comparison
  with UUID bind params — verify empirically.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019.
