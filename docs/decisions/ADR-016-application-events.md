# ADR-016: Application Event Foundation (In-Process, Post-Commit)

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3A — Domain & Application Events Foundation)
- Relates to: AGENTS.md §§2/7, ADR-012, ADR-013

## Context

Queue, session, and booking operations run synchronously today, but live
updates, notifications, and projections will need to react to them. Calling
Redis/WebSocket/notification code from domain services would couple the
modular monolith's core to infrastructure it cannot yet justify. An
in-process event seam lets future phases attach consumers without touching
business logic.

## Decision

- **Plain Spring application events** (`ApplicationEventPublisher` +
  immutable records): the smallest mechanism fitting a Spring Boot
  monolith. A project-owned event bus would be indirection over a stable
  framework API with no second implementation in sight — rejected as
  premature abstraction.
- **Facts, not commands**: 9 records — `QueueEntryJoined/Called/
  ServingStarted/Completed/Cancelled/NoShow`, `ServiceSessionStarted/
  Completed`, `AppointmentBooked`. Entry-lifecycle vs session facts stay
  distinct (different consumers); no `AppointmentCancelled` exists because
  no service flow performs it (events mirror existing operations only).
- **Payloads are IDs + scalar snapshots** (org/branch/queue/entry/service/
  counter/customer, token, business date, priority, interval, status) —
  never entities or proxies. `eventId` is app-generated per emission
  (never a business id — one entry emits many events); `occurredAt` comes
  from the project Clock (recorded-fact time, not commit time).
- **Ownership by feature**: `queue/event/`, `appointment/event/` next to
  the code producing the facts — no central event dump.
- **Publish in services, after state is established** (persist, then
  publish); **delivery AFTER_COMMIT** via `@TransactionalEventListener`
  on consumers: rolled-back transactions never deliver, committed ones
  always do — proven by tests both ways. Publishing outside a transaction
  would silently drop AFTER_COMMIT delivery; all current publishers are
  `@Transactional`, documented as a constraint.
- **No ordering beyond per-transaction causality, no durability**: identity
  + timestamp only; no correlation/trace scaffolding. No outbox (single
  process + PostgreSQL; durable external delivery is a later problem,
  explicitly deferred with no crash-safety claims). No Kafka/Redis/
  WebSocket/notifications — zero new dependencies, zero containers.
- **No async**: synchronous in-process dispatch; later phases move selected
  consumers async where justified. No config, no migration (V1–V8
  untouched).

## Alternatives Considered

1. **Custom event bus abstraction.** Rejected: no second backend exists;
   indirection without a second implementation.
2. **Publishing from entities/repositories.** Rejected: persistence layer
   must not know about consumers; services own orchestration.
3. **Immediate (in-transaction) delivery semantics.** Rejected for
   committed-fact consumers: rollback would already have notified.
4. **Outbox table now.** Rejected: pays durability costs with no external
   consumer to protect; documented deferral instead.
5. **Single generic event + type field.** Rejected: loses compile-time
   payload shape per fact; records are cheap.
6. **Entity payloads / shared base class.** Rejected: lazy-proxy leakage
   and premature hierarchy, respectively.

## Consequences

- Phase 3B/3C attach Redis/WebSocket bridges as AFTER_COMMIT listeners
  without modifying queue/appointment/session services.
- Tests own the only consumer (a recording collector); production adds
  none yet — the seam is proven, not the plumbing.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019.
