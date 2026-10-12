# ADR-010: Branch Operating Calendar (Working Hours & Holidays)

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 2D — Working Hours & Holidays)
- Relates to: AGENTS.md §§2/3, ADR-006, V5 migration

## Context

The future appointment engine needs a branch calendar to compute
availability, but availability itself is a later phase. Phase 2D must store
the calendar facts — weekly intervals and closure dates — with invariants
strong enough that no code path (or raw SQL client) can record nonsense like
`18:00 → 09:00` or two overlapping Monday-morning shifts.

## Decision

- **One row per continuous interval** (`working_hour`: branch, `DayOfWeek`,
  start/end `LocalTime`). Split days are multiple rows; a weekday with no
  rows means normally closed — no `CLOSED` rows, no `isClosed` flag, no
  single open/close pair on Branch. Intervals are immutable after creation
  (no setters for bounds — changing an interval is delete + recreate, which
  re-runs every check).
- **Range validity in two layers:** constructor guard (`start < end`, fail
  fast) plus `CHECK (start_time < end_time)` — raw SQL inserting
  `18:00 → 09:00` fails.
- **Overlap prevention by PostgreSQL exclusion constraint** (the notable
  decision): `EXCLUDE USING gist (branch_id =, day_of_week =,
  int4range(start_minutes, end_minutes) &&)` with `int4range` `[start, end)`
  semantics, so touching intervals (13:00 end / 13:00 start) are correctly
  allowed. Requires the `btree_gist` extension (ships with standard
  PostgreSQL; `CREATE EXTENSION IF NOT EXISTS` in V5). Verified empirically
  against the project's Postgres 16 before committing to it: overlaps,
  duplicates, and containment rejected; adjacency and cross-day rows
  accepted.
- **Why not application-level overlap checks:** an entity cannot see its
  sibling rows without a repository/service, and no service layer exists in
  this phase — any Java check would be advisory. The exclusion is the
  single enforcement point; JPA deliberately maps simple joins while the
  migration owns the composite logic (the Phase 2B lesson).
- **Minute-granular schedules enforced:** the exclusion compares minute
  expressions, and sub-minute values would truncate into empty ranges that
  never conflict — so the constructor rejects seconds/nanos. Branch
  schedules genuinely live at minute precision; this makes the constraint
  exact rather than approximate.
- **No `UNIQUE(branch, day)`** (it would forbid split days) and no extra
  start-uniqueness: the exclusion already rejects every same-start pair,
  since equal starts necessarily overlap. `idx_working_hour_branch_day`
  serves the future "hours for branch + weekday" read as a plain btree.
- **Holiday = explicit full-day branch closure** (`holiday`: branch, date,
  name; `UNIQUE(branch_id, holiday_date)`). Same date across branches
  stays allowed. No partial days, recurrences, or national-calendar
  automation — product decisions for later, not missing engineering.
- **Types:** `DayOfWeek` via a small `AttributeConverter` to `VARCHAR`
  (JPA has no built-in mapping; names stay readable and stable),
  `LocalTime ↔ TIME`, `LocalDate ↔ DATE`, `Instant ↔ TIMESTAMPTZ` for
  audits. Never `Instant` for opening hours, never strings for dates.
- **Timezone deferred deliberately:** `Branch` has no timezone field, and
  adding one now would be speculative — nothing reads or converts these
  wall-clock times yet. The appointment phase will add branch timezone
  (`zone_id`-style) plus conversion rules and document them; until then
  `09:00` means "09:00 at the branch", full stop.
- **No branch `organization_id` on either table:** the branch FK already
  fixes the tenant boundary (`WorkingHour/Holiday → Branch →
  Organization`). Unlike 2B/2C there is no second same-level entity to
  cross-check, so a redundant org column with composite FKs would add
  ceremony with zero additional integrity.
- **No services, APIs, DTOs, availability logic** — persistence and
  invariants only, validated by repository tests.

## Alternatives Considered

1. **Single open/close pair on Branch.** Rejected: cannot express lunch
   breaks or split periods — the core requirement.
2. **`isClosed` boolean / CLOSED rows.** Rejected: absence of rows already
   means closed; a flag would create two sources of truth (flag vs rows).
3. **Application-only overlap validation.** Rejected: unenforceable from an
   entity and bypassable via SQL; the task forbids accepting that
   casually — hence the exclusion constraint.
4. **Hibernate `@Version`/locking for calendar edits.** Rejected: no
   concurrent state transition exists here; admin calendar edits are
   last-write-wins by nature.
5. **Adding `Branch.timezone` now.** Rejected as speculative: no reader,
   no conversion, no requirement — deferred with an explicit note instead
   of a guess column.

## Consequences

- The appointment phase inherits a calendar it can trust: every stored
  interval is well-formed and non-overlapping by construction, so slot math
  never defends against corrupt inputs.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
