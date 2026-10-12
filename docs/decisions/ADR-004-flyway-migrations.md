# ADR-004: Flyway as the Schema Migration Mechanism

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 1 — infrastructure foundation)
- Relates to: AGENTS.md §14, ADR-003 (PostgreSQL), `src/main/resources/db/migration/`

## Context

With PostgreSQL adopted, schema evolution needs exactly one mechanism.
Hibernate can generate DDL (`ddl-auto`), and Spring can run `schema.sql` —
both are tempting shortcuts that silently diverge environments and cannot
express the locking-relevant constraints (unique, exclusion, check) the
appointment/queue engines will depend on.

## Decision

**Flyway owns all schema evolution.** Versioned SQL migrations live in
`src/main/resources/db/migration/` under the convention
`V<version>__<snake_case_description>.sql` (e.g. `V1__baseline.sql`,
`V2__create_organization.sql`). Hibernate is set to `ddl-auto: validate`
— it checks entity/schema agreement but never creates or alters tables.
`spring-boot-starter-flyway` plus the database-specific
`org.flywaydb:flyway-database-postgresql` module (required by Flyway for
non-embedded databases per official Spring Boot 4.1 docs) are dependencies;
all versions Boot-managed (Flyway 12.4.0 via Boot 4.1.1).

## Alternatives Considered

1. **Hibernate `ddl-auto: update/create` as the evolution mechanism.**
   Rejected: not versioned, not reviewable, order-unsafe, and incapable of
   expressing exclusion constraints and careful index design; explicitly
   forbidden by AGENTS.md §14 philosophy.
2. **Liquibase.** Rejected: no functional driver for this project; the team
   knows SQL-first Flyway, and SQL migrations keep constraint intent visible
   for interview walkthroughs.
3. **Spring `schema.sql`/`data.sql` init scripts.** Rejected: officially
   discouraged alongside Flyway/Liquibase by Spring Boot docs; no versioning
   or repeatability story across environments.

## Consequences

- Positive: every schema change is a reviewable, versioned, immutable
  artifact; dev/test/prod converge by construction; `validate` mode fails
  fast on entity/schema drift instead of silently masking it.
- Rules (also in `docs/database/README.md`): migrations are immutable once
  applied to any shared database — never edit, always add a new version;
  no `create`/`create-drop`/`update` anywhere, including tests.
- Phase 1 ships `V1__baseline.sql` — a pipeline-establishing migration that
  creates no domain tables (domain schema arrives in Phase 2). Its success
  is asserted by `PostgresInfrastructureTests#flywayAppliedBaselineMigration`.

## Compatibility Considerations

- Flyway 12.x (managed by Boot 4.1.1) requires the per-database module:
  without `flyway-database-postgresql`, PostgreSQL support is absent even
  with `flyway-core` on the classpath. Both dependencies are present.
- `baseline-on-migrate: true` is set so adopting a pre-existing database
  later does not fail the first run; on empty databases it is a harmless
  no-op (Flyway logs an informational note when schemas stay empty).
