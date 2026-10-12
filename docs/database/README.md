# Database — PostgreSQL + Flyway (through Phase 4R.4: organization onboarding, V21)

> Phase 4R.2 (2026-10-09) implemented the ADR-040 persistence foundation:
> V20 created `organization_membership`, backfilled operational users, and
> un-pinned the customer organization legs (detail below). Authentication,
> JWT, `TenantContext`, registration, and authorization behavior are
> **unchanged** — those migrate in later 4R phases.

## PostgreSQL as the primary database

PostgreSQL is the source of truth for all core business state (ADR-003).
One database family is used everywhere:

| Environment | How PostgreSQL is provided | Version |
|---|---|---|
| Local development | `compose.yaml` (`smartqueue-postgres`) | `postgres:16-alpine` |
| Integration tests | Testcontainers (`PostgresInfrastructureTests`) | `postgres:16-alpine` |
| Production | TBD (later phase) | TBD |

Keep the Compose image tag and the test image tag in sync when upgrading
majors. There is deliberately **no H2** dependency — tests must prove
production-like locking/constraint behavior.

## Flyway as the migration mechanism

Flyway owns all schema evolution (ADR-004). Dependencies (all Boot-managed,
no explicit versions):

- `spring-boot-starter-flyway` (Flyway 12.4.0 via Boot 4.1.1)
- `org.flywaydb:flyway-database-postgresql` — required database-specific
  module for non-embedded databases (per official Spring Boot 4.1 docs;
  `flyway-core` alone does not support PostgreSQL)

Effective settings (`application.yml`):

- `spring.flyway.enabled: true`
- `spring.flyway.locations: classpath:db/migration`
- `spring.flyway.baseline-on-migrate: true`

## Migration naming convention

```text
V<version>__<snake_case_description>.sql
```

Examples: `V1__baseline.sql` (pipeline baseline, no domain tables),
`V2__create_organization_and_branch.sql` (Phase 2A — first domain tables).
Double underscore between version and description; Flyway default.
Multi-part versions use underscores (`V2_1__...`).

## Applied migrations

| Version | File | Content |
|---|---|---|
| 1 | `V1__baseline.sql` | Pipeline proof (`SELECT 1`); no tables |
| 2 | `V2__create_organization_and_branch.sql` | `organization` + `branch` tables |
| 3 | `V3__create_service_and_counter.sql` | `service` + `service_counter` + `counter_capability` |
| 4 | `V4__create_users_roles_and_staff_assignments.sql` | `app_user` + `user_role` + `staff_assignment` |
| 5 | `V5__create_working_hours_and_holidays.sql` | `working_hour` + `holiday` |
| 6 | `V6__create_appointments.sql` | `branch.time_zone` + `appointment` |
| 7 | `V7__create_queue_and_queue_entries.sql` | `queue` + `queue_entry` + `queue_token_counter` |
| 8 | `V8__create_service_session.sql` | `service_session` (below) |
| 9 | `V9__create_user_credential.sql` | `user_credential` (below) |
| 10 | `V10__create_notifications.sql` | `notification` (recipient timeline + unread partial indexes, `event_id` uniqueness backstop) |
| 11 | `V11__notification_retention_index.sql` | `idx_notification_created_at` (global timestamp sweep; V10 indexes lead with recipient) |
| 12 | `V12__appointment_reminders.sql` | `appointment_reminder` (below) + `idx_appointment_status_start` |
| 13 | `V13__analytics_reporting_indexes.sql` | Three reporting indexes only, no tables (below) |
| 14 | `V14__credential_password_changed_at.sql` | `user_credential.password_changed_at` (nullable, no backfill) — password-change session revocation (ADR-037); no index (reads by the credential unique) |
| 15 | `V15__account_verification_token.sql` | `account_verification_token` — one live row per user (`uq_verification_user`), digest-unique (`uq_verification_token`), kept-after-use as the reuse proof, `idx_verification_expiry` for the cleanup sweep (ADR-037) |
| 16 | `V16__password_reset_token.sql` | `password_reset_token` — same single-use shape as verification (one live row per user, digest-unique, 1h policy in code, expiry index for the sweep) (ADR-037) |
| 17 | `V17__organization_invitation.sql` | `organization_invitation` — names organization, email, role, and (for staff) branch server-side; token-bound acceptance; accept/revoke/expiry state columns (ADR-037) |
| 18 | `V18__oauth_identity.sql` | `oauth_identity` — provider + subject linked to `app_user`; email-match linking, no cascade (social login) |
| 19 | `V19__backfill_service_queues.sql` | No schema change: one `ACTIVE` queue per existing `(branch, service)` pair lacking one (`WHERE NOT EXISTS` + `ON CONFLICT DO NOTHING`; existing rows untouched). Establishes the Phase 4K invariant for pre-provisioning data (see ADR-038). |
| 20 | `V20__organization_membership.sql` | `organization_membership` table + backfill from legacy (user, organization, operational-role) grants; customer legs of `appointment`/`queue_entry` un-pinned to plain `customer_id → app_user(id)` FKs; `app_user.organization_id` made nullable (ADR-040, Phase 4R.2 — detail below). |
| 21 | `V21__organization_onboarding.sql` | Review-audit columns on `organization` (`rejection_reason`, `decided_at`, `decided_by_user_id` + FK, all nullable, no backfill) + `idx_organization_status`; legacy rows keep their status with NULL review columns (ADR-041, Phase 4R.4). |

**Latest applied version: V21.** No V22+ migration exists.

> Phase 4R.3 (2026-10-09) made **no schema change**: the identity
> migration there is application-level (nullable principal, optional JWT
> `org` claim, membership-grounded authorization, resource-derived
> customer scope). The V20 objects below are the complete persistence
> story.

## organization_membership (Phase 4R.2 — implemented, ADR-040 foundation)

V20 is the persistence foundation for ADR-040 (decided in Phase 4R.1). It
changes schema and data only — authentication, JWT, `TenantContext`,
registration, and authorization behavior are unchanged and arrive in later
4R phases.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_organization_membership`), app-assigned (ADR-006) |
| `user_id` | `uuid` | NOT NULL, FK `fk_membership_user` → `app_user(id)` |
| `organization_id` | `uuid` | NOT NULL, FK `fk_membership_organization` → `organization(id)` |
| `role` | `varchar(20)` | NOT NULL — `STAFF`/`MANAGER`/`ADMIN` by name (`MembershipRole`; `CUSTOMER` is a platform-level role and has no membership representation) |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name (`MembershipStatus`). Only ACTIVE grants operational access |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed (ADR-006) |

Constraints and indexes: `uq_membership_user_organization UNIQUE (user_id,
organization_id)` — one membership per user+organization, never keyed by
email. `idx_membership_organization (organization_id)` serves
"members of an organization"; user-first lookups are served leftmost by the
unique.

Backfill (deterministic, `WHERE NOT EXISTS`-guarded — a genuine duplicate
violates the unique and fails the migration rather than being swallowed):
every pre-V20 user holding STAFF/MANAGER/ADMIN with a legacy
`app_user.organization_id` gets exactly one membership row; the role is the
highest-privilege grant (ADMIN > MANAGER > STAFF) when a legacy user held
several; the status mirrors `UserStatus`; `created_at` inherits the
account's creation. **Customers receive no membership rows** and keep their
populated legacy `organization_id` value — the running application still
reads it (principal/JWT) until Phase 4R.3, so existing customer logins keep
working.

Customer decoupling: `fk_appointment_customer_organization` (V6) and
`fk_entry_customer_organization` (V7) were replaced by plain
`customer_id → app_user(id)` FKs (`fk_appointment_customer`,
`fk_entry_customer`). Rows keep their own `organization_id`/`branch_id`
columns, so historical transactions stay attached to the organization where
they happened. `staff_assignment`'s `(user_id, organization_id)` composite
FK is deliberately kept — it now enforces that an assignment's user has that
organization as their primary one, and platform-level customers
(`organization_id` NULL) can never be assigned.
`app_user.organization_id` dropped NOT NULL; the column is retained as a
legacy/primary-organization anchor and scheduled for removal once
`staff_assignment` re-points at membership.

Verified in Phase 4R.2 (real PostgreSQL + Testcontainers): the V1→V20 chain
from an empty database; backfill counts/roles/statuses over representative
legacy data; multi-role collapse; customer non-assignment; historical
appointment/queue-entry integrity; platform-customer (org NULL) persistence
and cross-organization booking capability; duplicate-membership rejection;
customer staff-assignment rejection. See `OrganizationMembershipPersistenceTests`
and `OrganizationMembershipMigrationTests`.

## Tables (Phase 2A)

### organization

Top of the tenancy hierarchy (ADR-007). One row per tenant.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_organization`), app-assigned (ADR-006) |
| `name` | `varchar(150)` | NOT NULL — deliberately NOT unique (display names repeat across tenants) |
| `status` | `varchar(20)` | NOT NULL — `PENDING_APPROVAL`/`ACTIVE`/`REJECTED`/`INACTIVE` persisted by name (ADR-007, ADR-041) |
| `rejection_reason` | `varchar(500)` | nullable — owner-visible rejection feedback, set on `REJECTED`, cleared on approval; no internal-notes column exists by design (V21, ADR-041) |
| `decided_at` | `timestamptz` | nullable — when platform review decided (V21) |
| `decided_by_user_id` | `uuid` | nullable, FK `fk_organization_decided_by` → `app_user(id)`, no cascade — reviewing admin, audit only (V21) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed (ADR-006) |

Indexes: `idx_organization_status (status)` — serves the platform
review listing (V21). Only `ACTIVE` organizations are publicly
discoverable/bookable (ADR-041).

### branch

A physical location; belongs to exactly one organization (FK-enforced).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_branch`) |
| `organization_id` | `uuid` | NOT NULL, FK `fk_branch_organization` → `organization(id)` |
| `name` | `varchar(150)` | NOT NULL; unique WITHIN org (see below) |
| `address_line1/2` | `varchar(255)` | nullable — street detail optional for now |
| `city` / `country` | `varchar(100)` | NOT NULL — required location identity |
| `state` | `varchar(100)` | nullable |
| `postal_code` | `varchar(20)` | nullable |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Constraints/indexes: `uq_branch_organization_name UNIQUE (organization_id,
name)` — its btree also serves org-scoped lookups via the leftmost column,
so no separate `organization_id` index exists (no duplicated indexes).
No other indexes: none justified yet.

### service (Phase 2B)

Something a customer requests, owned by exactly one branch (ADR-008).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_service`) |
| `branch_id` | `uuid` | NOT NULL, FK `fk_service_branch` → `branch(id)` |
| `name` | `varchar(150)` | NOT NULL; unique WITHIN branch (below) |
| `description` | `varchar(1000)` | nullable — customer-facing text for future booking/queue screens |
| `standard_duration_minutes` | `integer` | NOT NULL, `>= 1` by convention — whole minutes (see duration note) |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Constraints: `uq_service_branch_name UNIQUE (branch_id, name)` (doubles as
the branch lookup index); `uq_service_id_branch UNIQUE (id, branch_id)`
exists solely as the target of the capability composite FK.

Duration representation: whole minutes in an explicitly named column.
Hibernate maps Java `Duration` to an opaque `NUMERIC` of nanoseconds and
rejects `INTERVAL` under `validate` (verified empirically) — the minutes
column is readable, sortable, and honest about units.
`Service.getStandardDuration()` is the single `Duration` conversion point.

### service_counter (Phase 2B)

A service point at a branch; supports services only via capabilities —
never by belonging to one service (ADR-008).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_service_counter`) |
| `branch_id` | `uuid` | NOT NULL, FK `fk_counter_branch` → `branch(id)` |
| `name` | `varchar(100)` | NOT NULL; unique WITHIN branch (below) |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE`/`MAINTENANCE` by name (administrative states only; no `BUSY`/`SERVING` — those are future service-session runtime states) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Constraints: `uq_counter_branch_name UNIQUE (branch_id, name)` (doubles as
the branch lookup index); `uq_counter_id_branch UNIQUE (id, branch_id)`
as composite-FK target.

### counter_capability (Phase 2B)

"Counter X handles Service Y", pinned to the branch both sides share.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_counter_capability`) |
| `branch_id` | `uuid` | NOT NULL, FK `fk_capability_branch` → `branch(id)` |
| `service_id` | `uuid` | NOT NULL, part of composite FK below |
| `counter_id` | `uuid` | NOT NULL, part of composite FK below |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity: `fk_capability_service FOREIGN KEY (service_id, branch_id)
REFERENCES service (id, branch_id)` and the mirror for counters — a
cross-branch link is impossible at the database level on any code path.
`uq_capability_service_counter UNIQUE (service_id, counter_id)` rejects
duplicate links and serves service-first reads;
`idx_capability_counter (counter_id)` serves counter-first reads (future
queue-engine pattern: "services supported by a counter").

### app_user (Phase 2C)

An application account owned by exactly one organization (ADR-009). Table
is `app_user` because `USER` is reserved in PostgreSQL.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_app_user`) |
| `organization_id` | `uuid` | NOT NULL, FK `fk_user_organization` → `organization(id)` |
| `email` | `varchar(255)` | NOT NULL, globally `UNIQUE` (`uq_user_email`) — one email → one account so future login is unambiguous; normalized lowercase in the entity |
| `display_name` | `varchar(150)` | NOT NULL — no uniqueness |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Constraints: `uq_user_id_organization UNIQUE (id, organization_id)` exists
solely as the composite-FK target for staff assignments (Phase 2B pattern).
Index: `idx_user_organization (organization_id)` serves "users in an
organization". No password/authentication columns — future security phase.

### user_role (Phase 2C)

Platform-level role grants, no Role entity (ADR-009): the four values are
fixed vocabulary, not tenant data.

| Column | Type | Constraints |
|---|---|---|
| `user_id` | `uuid` | NOT NULL, FK `fk_user_role_user` → `app_user(id)`, part of PK |
| `role` | `varchar(20)` | NOT NULL, part of PK — `CUSTOMER`/`STAFF`/`MANAGER`/`ADMIN` by name |

`PRIMARY KEY (user_id, role)` rejects duplicate grants and serves
"roles for a user" via its leftmost column. No other indexes. Multi-role
from day one; each row maps to one future Security authority.

### staff_assignment (Phase 2C)

"User U staffs Branch B", pinned to the organization both sides share.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_staff_assignment`) |
| `organization_id` | `uuid` | NOT NULL, FK `fk_staff_organization` → `organization(id)` |
| `user_id` | `uuid` | NOT NULL, part of composite FK below |
| `branch_id` | `uuid` | NOT NULL, part of composite FK below |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name (administrative only; no `SERVING`/`BUSY` — future queue runtime states) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity: `fk_staff_user_branch_organization FOREIGN KEY
(user_id, organization_id) REFERENCES app_user (id, organization_id)` and
`fk_staff_branch_organization FOREIGN KEY (branch_id, organization_id)
REFERENCES branch (id, organization_id)` (the branch target added via V4 —
V2 untouched). A cross-organization assignment is impossible at the
database level on any code path.
Uniqueness: `uq_staff_user_branch UNIQUE (user_id, branch_id)` — one row
per user/branch; organization excluded because the branch already pins it.
Index: `idx_staff_branch (branch_id)` serves "assignments for a branch";
the unique serves "branches for a user" leftmost.

### working_hour (Phase 2D)

One continuous operating interval of a branch on a weekday (ADR-010).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_working_hour`) |
| `branch_id` | `uuid` | NOT NULL, FK `fk_working_hour_branch` → `branch(id)` |
| `day_of_week` | `varchar(10)` | NOT NULL — `MONDAY`…`SUNDAY` via converter (JPA has no built-in `DayOfWeek` mapping) |
| `start_time` / `end_time` | `time` | NOT NULL — wall-clock, minute-granular (guarded); timezone interpretation deferred |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity beyond FKs:
- `chk_working_hour_range CHECK (start_time < end_time)` — equal and
  reversed ranges impossible, even via raw SQL.
- `no_working_hour_overlap EXCLUDE USING gist (branch_id =, day_of_week =,
  int4range(start_minutes, end_minutes) &&)` — same-branch/day overlaps,
  duplicates, and containment rejected by the database on every path.
  `[start, end)` bounds: adjacent intervals (13:00 end / 13:00 start) are
  correctly allowed. Requires the `btree_gist` extension (ships with
  standard PostgreSQL; `CREATE EXTENSION IF NOT EXISTS` in V5), verified
  empirically before adoption. No application-level overlap check exists
  or could honestly exist — an entity cannot see sibling rows — so the
  exclusion is the single enforcement point.
- Deliberately NO `UNIQUE(branch_id, day_of_week)` (would forbid split
  days) and no start-uniqueness (the exclusion already rejects every
  same-start pair). Index: `idx_working_hour_branch_day (branch_id,
  day_of_week)` serves the future "hours for branch + weekday" read as a
  plain btree.

### holiday (Phase 2D)

One explicit full-day branch closure (ADR-010).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_holiday`) |
| `branch_id` | `uuid` | NOT NULL, FK `fk_holiday_branch` → `branch(id)` |
| `holiday_date` | `date` | NOT NULL — the closed date |
| `name` | `varchar(150)` | NOT NULL — e.g. "Christmas Day" |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

`uq_holiday_branch_date UNIQUE (branch_id, holiday_date)`: one row per
branch/date, serving branch/date lookups leftmost; same date across
branches stays allowed. Full-day closures only — no partial days,
recurrences, or national-calendar automation (deferred product decisions).

### branch.time_zone (Phase 2E)

V6 adds `time_zone VARCHAR(100) NOT NULL` to `branch` (IANA id, e.g.
`Asia/Kolkata`). Booking converts instants to branch wall-clock for
working-hour checks; without a per-branch zone that conversion is
ambiguous, and a silent UTC default would book wrong local times — hence
REQUIRED with no lying default. Bare offsets are rejected by design (they
lose DST rules).

### appointment (Phase 2E)

A booked `[start, end)` service interval for a customer (ADR-011).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_appointment`) |
| `organization_id` | `uuid` | NOT NULL, FK → `organization(id)` |
| `branch_id` | `uuid` | NOT NULL, part of composite FK below |
| `service_id` | `uuid` | NOT NULL, part of composite FK below |
| `customer_id` | `uuid` | NOT NULL, part of composite FK below |
| `start_at` / `end_at` | `timestamptz` | NOT NULL — unambiguous instants; `end_at` snapshotted at booking (later service-duration edits never rewrite history) |
| `status` | `varchar(20)` | NOT NULL — `SCHEDULED`/`CONFIRMED`/`COMPLETED`/`CANCELLED`/`NO_SHOW` by name |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Ownership composites (2B/2C pattern — wrong-branch services and foreign
customers rejected by the DB on every path):
`fk_appointment_branch_organization (branch_id, organization_id)`,
`fk_appointment_service_branch (service_id, branch_id)`,
`fk_appointment_customer_organization (customer_id, organization_id)`.
Plus `chk_appointment_range CHECK (end_at > start_at)`.

Temporal protection (capacity assumption: one appointment per service per
interval; no customer in two places at once — documented, not silently
exceeded):
- `no_service_double_booking EXCLUDE USING gist (service_id =,
  tstzrange(start_at, end_at, '[)') &&) WHERE (status IN
  ('SCHEDULED','CONFIRMED'))` — concurrent inserts serialize on the index;
  exactly one wins. `service_id` already implies its branch: no
  branch-wide serialization.
- `no_customer_double_booking` — mirror on `customer_id`.
- Partial predicates mean terminal rows never block re-booking;
  `[start, end)` bounds mean adjacency is not overlap.
- `btree_gist` (from V5) covers uuid equality; `tstzrange` is gist-native.

Index: `idx_appointment_branch_time (branch_id, start_at)` — the one read
path no exclusion covers. Service/customer lookups are served leftmost by
their exclusion indexes.

### queue (Phase 2F)

One logical waiting line per branch + service (ADR-012).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_queue`) |
| `organization_id` | `uuid` | NOT NULL, FK → `organization(id)` |
| `branch_id` | `uuid` | NOT NULL, part of composite FK below |
| `service_id` | `uuid` | NOT NULL, part of composite FK below |
| `status` | `varchar(20)` | NOT NULL — `ACTIVE`/`INACTIVE` by name (administrative only) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

`uq_queue_branch_service UNIQUE (branch_id, service_id)` — exactly one
logical queue per pair (counters share it; history lives on entries).
Ownership composites (2B/2C pattern): `(service_id, branch_id)` and
`(branch_id, organization_id)` — cross-branch service attachment rejected
by the DB. `uq_queue_id_organization` exists as the entry composite
target.

### queue_entry (Phase 2F)

One waiting customer — a walk-in unless a later phase links appointments.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_queue_entry`) |
| `organization_id` | `uuid` | NOT NULL, FK → `organization(id)` |
| `queue_id` | `uuid` | NOT NULL, part of composite FK below |
| `customer_id` | `uuid` | NOT NULL, part of composite FK below |
| `token_number` | `integer` | NOT NULL — daily sequence per queue (see counter) |
| `business_date` | `date` | NOT NULL — branch-local date |
| `priority` | `varchar(20)` | NOT NULL — `NORMAL`/`PRIORITY`/`EMERGENCY` by name |
| `status` | `varchar(20)` | NOT NULL — `WAITING`/`CALLED`/`SERVING`/`COMPLETED`/`CANCELLED`/`NO_SHOW` by name |
| `joined_at` | `timestamptz` | NOT NULL — FIFO ordering key |
| `called_at` / `completed_at` | `timestamptz` | nullable — set by transitions |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity: composites `(queue_id, organization_id)` and
`(customer_id, organization_id)` — cross-org customers rejected by the DB.
`uq_active_entry UNIQUE (queue_id, customer_id) WHERE status IN
('WAITING','CALLED','SERVING')` — one active entry per customer, re-joinable
after terminal states (partial index; JPA maps no equivalent).
`uq_entry_token UNIQUE (queue_id, business_date, token_number)` — token
backstop. `idx_entry_queue_waiting (queue_id) WHERE status='WAITING'` —
per-queue waiting reads (the claim locks via its own plan).

### queue_token_counter (Phase 2F)

Next token number per (queue, business date): `queue_id` FK, `business_date`
DATE, `next_number` INTEGER; `UNIQUE(queue_id, business_date)`. Join
protocol: conflict-free `INSERT … ON CONFLICT DO NOTHING` then
`SELECT … FOR UPDATE` — never `MAX()+1`, never JVM locks (see ADR-012 for
why the first-attempted REQUIRES_NEW design was removed: the isolated
insert cannot see the caller's uncommitted queue row).

### Queue aging (Phase 2H — query only, no migration)

Aging changes no schema: ranking derives from `joined_at` plus
`queue.aging.*` configuration inside the claim query
(`EMERGENCY-first`, then base weight + capped steps, then `joined_at`,
then id). No `V9`: an empty migration for numbering's sake would be
ceremony, not schema evolution.

Index evaluation: `idx_entry_queue_waiting (queue_id) WHERE WAITING` still
drives candidate access (branch/service equality first; the ORDER BY sorts
the small per-queue waiting set). No `joined_at` index: it would only pay
if the query scanned by time across queues, which it never does
(branch + service equality always lead). No speculative indexes added.

### Wait-time estimation (Phase 2I — queries only, no migration)

Estimation stores nothing: three read-only counts feed the position model
— WAITING-ahead via the shared rank ORDER BY (position by `indexOf`),
CALLED in scope, open sessions on capable counters. No V9: an empty
migration for numbering's sake would be ceremony, not schema evolution.
The tuple-comparison alternative was prototyped and rejected with
evidence: PostgreSQL evaluates row-value `<` with a UUID bind parameter
incorrectly (silently wrong counts, no error) — ordered ids plus Java
positioning replaced it while keeping the identical ORDER BY text.

### service_session (Phase 2G)

One measured service interaction per queue entry (ADR-013).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_service_session`) |
| `branch_id` | `uuid` | NOT NULL, FK → `branch(id)`; tenant anchor |
| `queue_entry_id` | `uuid` | NOT NULL, FK → `queue_entry(id)`; `UNIQUE` (`uq_session_entry`) — one session per entry, the concurrent-start backstop |
| `service_counter_id` | `uuid` | NOT NULL, part of composite FK below |
| `service_id` | `uuid` | NOT NULL, part of composite FK below |
| `started_at` | `timestamptz` | NOT NULL — interaction start (from injectable Clock) |
| `ended_at` | `timestamptz` | nullable — NULL = running |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity: `(service_counter_id, branch_id)` and `(service_id, branch_id)`
composites pin counter and service to the entry's branch. Entry↔session
*service* equality (same service, not just same branch) is
application-enforced — exposing it would require denormalizing onto the
closed `queue_entry` table — guarded in the constructor, validated by the
lifecycle service, and proven by a test asserting the DB's permissiveness
there. `chk_session_range CHECK (ended_at IS NULL OR ended_at > started_at)`.
No status column (timestamps encode the three states); no duration column
(always derived); no counter-scoped index (nothing reads "session for
counter" yet).

### user_credential (Phase 3F)

One BCrypt hash per user, separate from `app_user` (ADR-021).

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_user_credential`) |
| `user_id` | `uuid` | NOT NULL, FK → `app_user(id)` (`fk_credential_user`, no cascade); `UNIQUE` (`uq_credential_user`) — one row per user |
| `password_hash` | `varchar(255)` | NOT NULL — BCrypt (60 chars today, headroom for rotation) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Integrity: no plaintext, no token, no history columns — the hash is the
whole row. No status column: `UserStatus` on `app_user` remains the source
of truth for whether login works. No backfill: pre-existing users have no
row and fail login with the same generic error as a wrong password (no
existence signal, no fake passwords).

### appointment_reminder (Phase 4F)

Durable proof that one reminder was processed for one appointment
(ADR-034). Creation-only: the row's existence is the SENT state.

| Column | Type | Constraints |
|---|---|---|
| `id` | `uuid` | PK (`pk_appointment_reminder`) |
| `organization_id` | `uuid` | NOT NULL, FK `fk_appointment_reminder_organization` → `organization(id)`, no cascade |
| `appointment_id` | `uuid` | NOT NULL, FK `fk_appointment_reminder_appointment` → `appointment(id)`, no cascade (appointment deletion is unsupported — dangling references fail loudly) |
| `reminder_type` | `varchar(20)` | NOT NULL — `UPCOMING` by name (the column exists so a second kind is a new value, not a schema change) |
| `sent_at` | `timestamptz` | NOT NULL — project-Clock instant of processing (audit/testability, never scheduling) |
| `created_at` / `updated_at` | `timestamptz` | NOT NULL, lifecycle-managed |

Concurrency: `uq_appointment_reminder UNIQUE (appointment_id,
reminder_type)` is the arbitration mechanism — concurrent schedulers
race the insert and exactly one wins; the winner's reminder row and
`APPOINTMENT_REMINDER` notification commit atomically. No stored due
column (due is derived per tick as `start_at − policy.before`,
so policy changes apply immediately and no wall-clock value can drift
across DST). Index: `idx_appointment_status_start (status, start_at)`
serves the bounded discovery window (`startAt IN (now, now + lead]`);
the existing `idx_appointment_branch_time` leads with `branch_id`
and cannot serve the global sweep.

### Analytics reporting indexes (Phase 4G)

V13 creates indexes only — no tables, no columns (ADR-035). Each
serves one overview-report predicate shape (organization plus an
instant window, with branch/service as equalities):

- `idx_appointment_analytics (organization_id, start_at)` — the
  appointment aggregates (`GROUP BY` status/service/branch over a
  `start_at` range). Branch/service filter the bounded set; no
  per-column variants.
- `idx_queue_entry_analytics (organization_id, joined_at)` — same
  shape for walk-in activity anchored on `joinedAt`. No `queue_id`
  index: entry→queue→branch/service legs resolve against the tiny
  bounded set (V7 guarantees one queue row per branch+service).
- `idx_service_session_branch_started (branch_id, started_at)` —
  sessions carry no `organization_id` (V8 branch anchoring), so
  org-wide reports expand to the organization's few branches and
  scan each range; branch-filtered reports hit equality plus range
  directly. Denormalizing `organization_id` onto the closed session
  table was rejected.

Deliberately absent: status indexes (`GROUP BY` runs over the
window-bounded set the range indexes already delimit). Plans were
not measured at scale — the justification is leftmost-column
predicate shape, honestly recorded in ADR-035, not a benchmark.

## Schema ownership and rules

- Application schema changes happen ONLY through versioned Flyway
  migrations. Never `CREATE/ALTER TABLE` by hand against a shared database.
- Migrations are **immutable once applied** to any shared database: never
  edit an applied migration, always add a new version.
- Only `organization`, `branch`, `service`, `service_counter`,
  `counter_capability`, `app_user`, `user_role`, `staff_assignment`,
  `organization_membership`,
  `working_hour`, `holiday`, `appointment`, `queue`, `queue_entry`,
  `queue_token_counter`, `service_session`, `user_credential`,
  `notification`, `appointment_reminder`, `account_verification_token`,
  `password_reset_token`, `organization_invitation`, `oauth_identity`, and
  the `user_credential.password_changed_at` column exist. Do not create
  speculative tables — each future entity arrives with its own migration.

## JPA/Hibernate relationship to Flyway

- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate checks that mapped
  entities agree with the Flyway-managed schema at startup and fails fast on
  drift. It never creates, updates, or drops tables.
- Forbidden everywhere (including tests): `create`, `create-drop`, `update`.
- `spring.jpa.open-in-view: false`.

## Local PostgreSQL setup

```powershell
# Run from backend/ (compose.yaml lives here).
docker compose up -d     # start (creates smartqueue-pgdata volume)
docker compose down      # stop, keep data
docker compose down -v   # stop and RESET local database
```

Connection defaults (`DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`,
see `.env.example`): `localhost:5432/smartqueue` as user `smartqueue`.

## Test database strategy

`PostgresInfrastructureTests` and `OrganizationBranchPersistenceTests` each
start a fresh `postgres:16-alpine` container (own instance per test class —
a shared static container's lifecycle races between classes) via
Testcontainers, wired with `@ServiceConnection` (no hardcoded
URLs). Flyway migrates the test database on context startup exactly as in
production. See ADR-005. Docker daemon required — documented in
`docs/development/README.md` (paths relative to `backend/`).

## Environment configuration

All credentials via environment variables; `application.yml` holds only
placeholders with safe local defaults. `.env` (from `.env.example`) is
git-ignored. Never commit passwords or production credentials.

## Future database design conventions (Phase 2+)

- Primary keys, foreign keys, NOT NULL, unique/check constraints as the
  domain requires; indexes justified by query/concurrency patterns
  (availability lookups, queue claims) — never blind indexing.
- Concurrency from day one: transactions + `PESSIMISTIC_WRITE`/versioning
  and/or DB constraints (unique/exclusion) for double-booking prevention
  and atomic queue claims. Never Java `synchronized` for DB concurrency.
- Every migration documented here alongside the domain tables it creates.
