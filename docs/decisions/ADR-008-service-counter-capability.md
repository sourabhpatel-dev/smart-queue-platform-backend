# ADR-008: Service & Counter Ownership, Capability Model, Cross-Branch Integrity

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 2B — Services & Service Counters)
- Relates to: AGENTS.md §§2/3, ADR-006, ADR-007, V3 migration

## Context

Phase 2B adds the bookable catalog under Branch: services customers request
and counters where they are delivered. The critical trap is modeling a
counter as belonging to one service — the queue engine needs counters that
serve many services — and allowing a counter from one branch to serve a
service from another, which would break tenant isolation at the data level.

## Decision

- **Ownership: Branch owns both.** `Service → Branch` and
  `ServiceCounter → Branch` (lazy, no cascade, explicit FKs) — tenant paths
  `Service → Branch → Organization` always resolve. A counter never belongs
  to a service.
- **Capability as an association entity** (`CounterCapability`, table
  `counter_capability`): "Counter 2 handles Blood Test". Chosen over a bare
  `@ManyToMany` join table because (a) the cross-branch rule needs a
  branch-aware row (below), and (b) future capability metadata (priority,
  enabled flag) gets a home without restructuring. Uniqueness
  `(service_id, counter_id)` enforced in the DB.
- **Cross-branch integrity enforced by composite foreign keys.**
  The capability row carries the shared `branch_id`; V3 defines
  `FOREIGN KEY (service_id, branch_id) REFERENCES service (id, branch_id)`
  and the mirror for counters (backed by `UNIQUE (id, branch_id)` targets).
  A cross-branch link is rejected by the database on every code path —
  proven by a raw-SQL insert test that bypasses the domain guard. A
  constructor guard fails fast with a clear message; the composites are the
  backstop.
- **JPA stays simple on purpose.** The composite keys live in the Flyway
  migration (the schema authority); the entity maps one join column per
  association. Rationale, learned empirically: Hibernate rejects mixed
  writable/read-only columns inside one `@JoinColumns` ("mix insertable with
  insertable=false"), so mirroring composites in JPA annotations is not just
  verbose — it does not compile. Validate checks columns, and they match.
- **Duration as whole minutes** (`standard_duration_minutes INTEGER`,
  `@Min(1)`, setter guard, `getStandardDuration()` as the single
  `Duration` conversion point). Empirically: Hibernate 6 maps `Duration` to
  an opaque `NUMERIC(21,0)` of nanoseconds and *rejects* PostgreSQL
  `INTERVAL` under `validate` — so the explicit minutes column beats both
  on readability, sortability, and honesty about units.
- **Statuses:** `ServiceStatus {ACTIVE, INACTIVE}`;
  `CounterStatus {ACTIVE, INACTIVE, MAINTENANCE}` — administrative states
  only. Queue-runtime states (`BUSY`, `SERVING`) are deliberately absent;
  they belong to future service-session modeling, not to a static counter
  row. All enums persisted by name.
- **Names unique within a branch** (`uq_service_branch_name`,
  `uq_counter_branch_name`), reusable across branches — the Phase 2A
  pattern. No global uniqueness anywhere.
- **Indexes:** the two branch-scoped uniques serve branch lookups via
  leftmost columns; `uq_capability_service_counter` serves service-first
  reads; `idx_capability_counter (counter_id)` serves counter-first reads
  ("services supported by a counter", a queue-engine access pattern).
  Nothing else — no speculative indexes.
- **No services, APIs, or DTOs this phase** (same rule as ADR-007):
  persistence-only, validated by repository integration tests. The two
  capability finders (`findByService`, `findByCounter`) are the exception —
  they serve the required relationship tests and are exactly the queue
  engine's future reads.

## Alternatives Considered

1. **Counter as child of one Service (`counter.service_id`).** Rejected:
   contradicts the domain (multi-service counters) and would force
   duplication or null-FK hacks later.
2. **Bare `@ManyToMany` with implicit join table.** Rejected: no place for
   the shared `branch_id`, so cross-branch integrity would rest on
   application code alone — explicitly ruled out for a critical invariant.
3. **`INTERVAL` / opaque-numeric duration storage.** Rejected empirically
   (see above); minutes-INTEGER is explicit and sortable.
4. **Full CRUD controllers "to exercise the domain".** Rejected: forbidden
   surface for this phase; repositories + tests suffice.

## Consequences

- Queue engine inherits a branch-safe capability graph: every link is
  same-branch by construction, queryable from either side.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
