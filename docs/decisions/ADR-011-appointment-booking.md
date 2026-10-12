# ADR-011: Appointment Time Model & Database-Backed Booking Protection

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 2E — Appointments & Booking Foundation)
- Relates to: AGENTS.md §§2/6, ADR-006, ADR-010, V6 migration

## Context

Appointments are the first domain where concurrency and time are load-bearing:
two requests racing for one slot must not both win, and "10:00" must mean the
same instant to everyone. Check-then-insert in application code cannot
guarantee either — the database must remain the final authority.

## Decision

- **Branch carries a required IANA time zone** (`ZoneId ↔ VARCHAR`,
  Phase 2D's deferred decision resolved). A silent UTC default would book
  wrong local times, and a bare offset loses DST — so the constructor
  requires it and `ZoneId.of` rejects garbage before it reaches the DB.
- **Appointments store `[startAt, endAt)` instants** (`TIMESTAMPTZ` ↔
  `Instant`): unambiguous across zones, adjacency-safe (10:30 after
  10:00–10:30 does not overlap). `endAt` is snapshotted at booking from
  the service's standard duration — later duration edits never rewrite
  history. No `LocalDateTime` anywhere near stored appointments.
- **Capacity assumption (explicit): one appointment per service per
  interval, and no customer in two places at once.** No capacity column was
  invented — the current Service model has none, and per-slot capacity 1 is
  the honest default for bookable services. If the product later needs
  parallel capacity, that is a deliberate model extension, not a silent
  relaxation.
- **Two partial exclusion constraints** (service+range, customer+range),
  each `WHERE (status IN ('SCHEDULED','CONFIRMED'))`: only active bookings
  occupy slots, so CANCELLED/COMPLETED/NO_SHOW rows never block re-booking,
  and the branch is never serialized (different service+customer pairs
  coexist freely). `service_id` already implies its branch, so no wider
  key is needed.
- **Booking is one `@Transactional` method** (validate → derive interval →
  holiday check → working-hour fit in the branch zone → persist), but
  `@Transactional` is *not* the concurrency mechanism: concurrent inserts
  serialize on the exclusion indexes and exactly one wins, proven by a
  two-thread race test (1 success + 1 conflict + 1 row). No `synchronized`,
  no global isolation changes. Constraint rejections translate to
  `AppointmentConflictException` — no PostgreSQL details leak.
- **Pre-checks are for messages, not correctness:** working-hour/holiday
  validation gives clear errors, but only the exclusions are race-safe.
- **Ownership composites** (service,branch), (customer,org), (branch,org) —
  the 2B/2C pattern: wrong-branch services and foreign customers are
  rejected by the DB on every path, plus constructor guards. JPA maps
  simple joins; the migration owns the composites.
- **Guarded status transitions** (`cancel`/`complete`/`markNoShow` from
  active states only, one-way) — enough to free slots and model terminal
  history, deliberately short of a state-machine framework or workflows.
- **No REST, no DTOs:** the service is tested directly; HTTP surface waits
  for the API-hardening phase.

## Alternatives Considered

1. **Check-then-insert without DB enforcement.** Rejected: textbook race —
   two concurrent reads both see "free" and both insert. The mandatory
   concurrency test exists precisely to prove this cannot happen.
2. **Single branch-wide exclusion.** Rejected: would serialize the whole
   branch — the opposite of the intended narrow boundaries (tested: two
   simultaneous appointments for different services/customers coexist).
3. **Full-range exclusion over all statuses.** Rejected: history would
   block re-booking forever; terminal states must free slots.
4. **Pessimistic locking / SERIALIZABLE for booking.** Rejected: heavier
   than needed — the exclusion index already arbitrates concurrent inserts
   correctly under the default isolation.
5. **Capacity column / overlapping appointments per service.** Rejected as
   speculative: no requirement exists; the capacity-1 assumption is
   documented instead of silently exceeded.
6. **UTC-everywhere without branch zones.** Rejected: silently interprets
   wall-clock schedules as server time — the exact ambiguity the phase was
   told to resolve. Proven by a test booking via UTC instants against IST
   working hours.

## Consequences

- Booking is correct under concurrency by construction, not by timing
  luck; the race test guards the invariant permanently.
- The queue/notifications phases inherit trustworthy booked intervals and
  a slot model (capacity 1) they must extend deliberately, never assume
  away.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
