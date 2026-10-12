# ADR-027 — Queue Management API

Status: Accepted (Phase 3L, 2026-10-06).

## Context

The queue engine (2F–2I: tokens, SKIP LOCKED claims, sessions, aging,
estimates) plus realtime (3C/3D) predates any HTTP. Phase 3L exposes it,
and four questions are genuinely new: how a counter-driven claim maps
to resources, how much identity the authorized views may carry next to
the anonymous realtime payload, where customers may look, and how the
range-query null-parameter lesson from 3K generalizes.

## Decision

1. **Counter-centric claiming.** `POST
   /counters/{counterId}/call-next` names the counter because the atomic
   claim selects across all its servable queues — a queue-scoped route
   would lie about the operation (a legally selected sibling entry
   cannot be put back). The response carries the selected entry's queue
   id. No queue creation or status commands: the engine defines none.
2. **No delegated joining, no customer triage.** The customer is always
   the principal; `PRIORITY`/`EMERGENCY` need operational authority
   (`403` otherwise, `400` on unknown names). Tokens, dates, positions,
   and waits are server-derived, always.
3. **Two views, one ranking.** The realtime snapshot stays anonymous;
   the REST snapshot adds ids, customers, and joined times for
   authorized eyes — both composed from the same rank query and
   estimator, so positions and waits cannot disagree with what
   `callNext` serves. REST never reads Redis; the paged record listing
   deliberately omits live positions/waits (rank cannot page; bulk
   estimate fan-out is bounded out by design).
4. **Privacy tiers match 3K exactly:** owners and operational scope read;
   same-org outsiders get scope-safe `404`s on items; commands deny with
   `403`; cancel stays owner-or-management; lifecycle refusals are
   honest `409`s. Empty claims are documented `409`s, not 500s.
5. **Events flow untouched.** REST commands delegate to the engine, so
   AFTER_COMMIT publication, the bridge, and the projection behave as
   before — proven by a join landing in Redis before its 201 returns,
   and by a Redis-down test proving correctness never depends on
   freshness. No controller touches Redis, events, or locks.
6. **No migration, no new dependencies, no concurrency changes** — the
   race suite plus a new HTTP-level two-caller race prove the claim
   still arbitrates.

## Alternatives considered

- **Queue-scoped call-next:** rejected — misrepresents the multi-queue
  claim; un-put-back-able selections would corrupt the endpoint's
  contract.
- **Reusing the realtime DTO for REST:** rejected — anonymity is a
  realtime design constraint, not a REST one; authorized views need
  identity, so a second view shares ingredients instead.
- **Rank as a page sort:** rejected — rank is computed, not stored;
  paging it would fake determinism across concurrent claims.
- **Idempotent lifecycle commands:** rejected (3K precedent) — terminal
  refusals are honest 409s.
- **Staff cancellation for others:** rejected — deferred like 3K;
  front-desk workflows can revisit with a modeled rule.

## Consequences

- The counter-centric operation pattern is available wherever an
  operation spans resources (no forced nesting).
- 21 black-box HTTP tests (20 + Redis-down); 2E–2I/3C/3D suites
  untouched and green.
- Intentionally deferred: queue creation/status, delegated joining,
  check-in integration, requeue rules, availability search, staff
  cancellation for others.
