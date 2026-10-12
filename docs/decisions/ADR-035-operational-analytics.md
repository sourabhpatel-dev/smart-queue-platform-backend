# ADR-035 — Operational Analytics & Reporting

Status: Accepted (Phase 4G, 2026-10-08).

## Context

Managers had no trustworthy view of operational activity: how many
appointments completed or no-showed, how long customers actually
waited, how long service took, or where volume concentrates. The
building blocks already existed: stored instants with branch zones
(ADR-011), lifecycle timestamps on entries and sessions
(ADR-012/013), the management-role gate (ADR-023), scope-safe reads
(ADR-022), and the fixed vocabulary of statuses. The genuinely new
questions: what a reporting window means across time zones, which
timestamp anchors each domain, how averages avoid lying about empty
data, and which indexes (if any) the new predicates justify.

## Decision

1. **PostgreSQL aggregates, read-only, one endpoint.**
   `GET /api/v1/analytics/overview` over committed state: ~10 small
   grouped `COUNT`/`SUM`/`AVG` queries (JPQL constructor-free
   scalars, entities never exposed), merged in Java by id. No
   warehouse, no Elasticsearch/ClickHouse, no materialized service,
   no brokers, no Redis cache — correctness first, and the data
   volume of a portfolio deployment does not justify a second store
   (which would immediately become a second source of truth).
2. **Explicit `[from, to)` local windows.** `from`/`to` arrive as
   local date-times in the effective zone and convert to instants
   once; every query filters `>= from AND < to`. `from < to`,
   non-empty, and a 366-day cap (`analytics.max-range-days`,
   validated, overridable) are enforced — overlong ranges are
   rejected, never truncated.
3. **Explicit timezone, never the server zone.** An explicit IANA
   `timezone` param wins; otherwise the selected branch's zone;
   organization-wide reports without one are rejected. DST gaps
   shift forward and overlaps take the earlier offset (java.time
   defaults, tested); the response echoes the effective zone and
   UTC instants. `ZoneId.systemDefault()` appears nowhere.
4. **One anchor per domain, current status only.** Appointments by
   `startAt`, entries by `joinedAt`, sessions by `startedAt`. Status
   splits report current rows and sum to their totals — the schema
   versions no transition history, so none is claimed (no
   cancellation/confirmation times, no transition durations).
5. **Measured waits, never estimates.** Average wait is
   `session.startedAt − entry.joinedAt` over COMPLETED entries
   joined in the window that have a session (inner join is the
   definition); Phase 2I estimates are live forward-looking
   snapshots with no persisted row and cannot answer history.
   Durations cover ended sessions only — running sessions count but
   never fabricate time. Counts are `0` on empty data; averages are
   `null` (zero minutes means something different from "nothing
   observed").
6. **Management-only, tenant-pinned.** The existing
   `requireOrganizationManagement` gate denies staff/customers
   (403); organization comes from the principal only; foreign
   branch/service ids are scope-safe 404s; same-org service∉branch
   is a 422 mismatch (never a silent empty report). No controller
   role logic, no new policy.
7. **Three justified indexes (V13).**
   `idx_appointment_analytics (organization_id, start_at)` and
   `idx_queue_entry_analytics (organization_id, joined_at)` bound
   the org+window predicates directly (branch/service filter the
   bounded set — no per-column variants).
   `idx_service_session_branch_started (branch_id, started_at)`:
   sessions carry no `organization_id` (V8 branch anchoring), so
   org-wide reports expand to the organization's few branches and
   scan each range, while branch-filtered reports hit equality plus
   range directly; denormalizing `organization_id` onto the closed
   session table was rejected. No status indexes (GROUP BY runs over
   the already-bounded set). Plans were not measured at scale —
   honestly documented as predicate-shape justification, not a
   benchmark.
8. **HQL duration math, documented PG dependence.**
   `AVG(EXTRACT(EPOCH FROM end) − EXTRACT(EPOCH FROM start))` keeps
   parameters type-safe (bare native SQL would reintroduce the
   ADR-026 null-bind problem); the two-sided form is required
   because Hibernate types `end − start` as `Duration`, which its
   `extract()` rejects — found by failing query validation, fixed
   the same day. H2 is not a supported database, so PG-only
   functions are acceptable and labeled as such.
9. **Atomic reports, generic failures.** Either the whole report
   succeeds or the request fails with the existing envelope (query
   faults stay generic 500s, logged server-side, no SQL leaks).
   Failed metrics are never zero-filled.
10. **Restrained frontend.** One manager page reusing the 4C design
    language: day-granular filters, compact summary cards, two
    tables with proportional fills over real counts (cells are the
    accessible equivalent), skeleton/empty/error+retry states — no
    chart library, no fake deltas, no invented KPIs.

## Alternatives considered

- **Native SQL everywhere:** rejected — HQL covers everything once
  `EXTRACT` is applied per-endpoint; native would regain the
  null-UUID-bind typing risk for zero clarity gain.
- **Single giant query:** rejected — ten clear queries with
  identical scope predicates beat one clever statement for review
  and debugging; summary and breakdowns cannot drift because the
  predicates are literally shared.
- **Java-side aggregation over loaded entities:** rejected —
  unbounded memory for a bounded question; the database counts.
- **Storing due/transition history for analytics:** rejected —
  schema changes serve product needs, not reporting wishes; the
  report says what the schema can prove.
- **Redis caching:** rejected — premature; correctness first, and a
  cache would need its own invalidation story for data that
  changes on every booking and queue event.

## Consequences

- `analytics/` module (properties, repository, service, controller,
  DTOs, scope-mismatch exception + 422 mapping), V13 migration, 30
  new backend tests (4 properties, 18 service, 8 API), 10 new
  frontend tests (161 total); no new dependencies.
- Known gaps: no exports, no scheduled reports, no preferences, no
  per-customer analytics, no cross-period comparison, plans not
  measured at scale.
- Verification note: no Docker daemon exists in this environment, so
  the committed Testcontainers suites could not execute here. Every
  behavior was proven pre-completion against a real PostgreSQL
  (embedded, V1–V13 applied, Hibernate validate green): 6/6 green,
  including exact metric math, `[from, to)` boundaries, branch-zone
  derivation, DST windows, guards, zero-data nulls, and the HTTP
  contract (401/403/200). The scratch harness was deleted
  afterwards; `pom.xml` is unchanged by it. Verification also caught
  and fixed two real defects before completion: HQL
  `EXTRACT(EPOCH …)` over instant subtraction fails query
  validation, and multi-select Spring Data methods must declare
  `List<Object[]>` (a bare `Object[]` packs rows, not columns).
