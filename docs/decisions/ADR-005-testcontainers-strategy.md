# ADR-005: Testcontainers PostgreSQL Integration-Test Strategy

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 1 — infrastructure foundation)
- Relates to: AGENTS.md §15, ADR-003 (PostgreSQL), ADR-004 (Flyway)

## Context

The template `contextLoads` test failed because JPA required a datasource
and none existed. The fix had to be realistic: the context test should prove
the app actually starts against a PostgreSQL-compatible database with
migrations applied — not mock the datasource away or dumb the stack down to
an in-memory impostor (ruled out in ADR-003).

## Decision

**Integration tests run against real PostgreSQL via Testcontainers**
(Boot-managed Testcontainers 2.0.5), wired with Spring Boot's
`@ServiceConnection` — no hardcoded JDBC URLs, no manual property plumbing.
`spring-boot-testcontainers` + `testcontainers-junit-jupiter` +
`testcontainers-postgresql` are test-scoped; the Testcontainers BOM is
imported with Boot's managed `${testcontainers.version}` (Boot 4.1 manages
only the BOM, not individual modules — verified against
`spring-boot-dependencies:4.1.1`).

Testcontainers 2.x renamed modules with a `testcontainers-` prefix
(`testcontainers-junit-jupiter`, `testcontainers-postgresql`) and moved the
container to `org.testcontainers.postgresql.PostgreSQLContainer`
(non-generic). Code follows the 2.x coordinates — do not copy 1.x-era
snippets blindly.

## Alternatives Considered

1. **H2 for tests.** Rejected in ADR-003 (dialect/locking drift hides the
   bugs this project demonstrates). No H2 dependency exists.
2. **Shared local PostgreSQL for tests (fixed `localhost:5432`).**
   Rejected: order-dependent, state-leaking between runs, breaks parallel
   and CI execution, couples tests to a developer's machine state.
3. **`@DynamicPropertySource` manual wiring.** Rejected: more boilerplate
   for the same outcome; `@ServiceConnection` is the Boot-native mechanism
   and keeps connection details out of test code entirely.

## Consequences

- Positive: `PostgresInfrastructureTests` proves context startup, real
  PostgreSQL reachability, Flyway V1 applied, and JPA bootstrap — the same
  database family as production, fresh per run.
- Obligations: Docker is a hard test prerequisite (documented in
  `docs/development/` and `README.md`); image pulls require network on
  first run (`postgres:16-alpine`, shared with Compose).
- The pre-existing `contextLoads` failure is resolved without touching the
  template test: with a datasource available, it passes (it reuses the
  Bootstrapped context configuration).
