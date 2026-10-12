# ADR-003: PostgreSQL as the Primary Relational Database

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 1 — infrastructure foundation)
- Relates to: AGENTS.md §§9/14, ADR-001, ADR-004 (Flyway), ADR-005 (testing)

## Context

Phase 1 must establish the development database. The domain (appointments,
walk-in queues) demands real ACID transactions: double-booking prevention
and atomic queue claims cannot be proven against a database family that
behaves differently from production. The choice also fixes the local
development environment (Docker Compose) and the integration-test database
for the whole project.

## Decision

**PostgreSQL (16.x for local development, via `postgres:16-alpine`) is the
primary and only relational database.** It is the source of truth for all
core business state. All environments — local development (Docker Compose),
integration tests (Testcontainers), and later production — use the same
database family.

## Alternatives Considered

1. **H2 / HSQLDB for tests, PostgreSQL for dev/prod.** Rejected: different
   SQL dialects, locking semantics, and constraint behavior between test and
   production hide exactly the concurrency bugs this project exists to
   demonstrate (exclusion constraints, `SELECT … FOR UPDATE`, isolation
   levels). No H2 dependency is added — deliberately.
2. **MySQL / MariaDB.** Rejected: no technical driver; PostgreSQL has
   stronger support for the constraints and range/exclusion features the
   appointment engine will need, and the best fit with Flyway + Hibernate +
   Testcontainers in the Spring ecosystem.
3. **SQLite / file-based DB.** Rejected: inadequate concurrency model for a
   booking/queue system; unsuitable as a portfolio backend choice.

## Consequences

- Positive: one database family everywhere — tests prove production-like
  behavior; `SELECT … FOR UPDATE`, unique/exclusion constraints, and
  transaction semantics verified in tests hold in production.
- Positive: connection configuration is environment-based
  (`DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` with safe local
  defaults); Compose and the app share the same variable names.
- Obligations: every developer/CI runner needs Docker (documented in
  `docs/development/`); PostgreSQL version upgrades are explicit
  (`compose.yaml` image tag and test image tag move together).
- Follow-ups: shared-database logical multi-tenancy detail → ADR-019 when
  Organization/Branch modelling starts (Phase 2).

## Compatibility Considerations

- Boot 4.1.1 manages the `postgresql` driver version — no explicit version.
- Local image and test image are both pinned to `postgres:16-alpine`;
  keep them in sync when upgrading majors.
