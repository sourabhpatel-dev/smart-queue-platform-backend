# ADR-002: Adopt Spring Boot 4.1.1 with Java 21 (No Downgrade to 3.x)

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (pre-Phase-2 version decision)
- Relates to: AGENTS.md §1, `pom.xml`, docs/PROJECT_STATUS.md, ADR-001

## Context

The project was originally planned around Spring Boot 3.x, but the generated
Spring Initializr skeleton declares `spring-boot-starter-parent:4.1.1` with
`java.version=21`. Before any domain work (Phase 2) begins, the Boot line
must be fixed unambiguously: dependency coordinates, Spring Security
configuration style, testing starters, and documentation claims all depend
on it.

## Current Situation

- `backend/pom.xml` (verified 2026-10-05): parent
  `org.springframework.boot:spring-boot-starter-parent:4.1.1`,
  `<java.version>21</java.version>`.
- Present starters: `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
  `spring-boot-starter-webmvc`, `spring-boot-devtools` (runtime),
  `postgresql` (runtime), plus Boot 4.x `-test` starters
  (`data-jpa-test`, `validation-test`, `webmvc-test`).
- No business code exists; only `SmartQueuePlatformApplication` + the
  template `contextLoads` test (which currently errors for lack of a
  datasource — a separate, pre-existing Phase-2 item, unrelated to this
  version decision).
- Official Spring Boot system-requirements documentation (docs.spring.io,
  checked 2026-10-05): **Spring Boot 4.1.1 is a stable release; it requires
  at least Java 17 and is compatible with versions up to and including
  Java 26** (backed by Spring Framework 7.0.9+). Java 21 is therefore a
  supported combination, and 4.1.1 appears on the official stable-versions
  list alongside the 4.0.x / 3.5.x / 3.4.x maintenance lines.

## Decision

Adopt **Spring Boot 4.1.1** with **Java 21** as the project baseline.
Do NOT downgrade to Spring Boot 3.x.

## Alternatives Considered

1. **Downgrade to Spring Boot 3.x (e.g. latest 3.4/3.5 patch).**
   Rejected: the project starts from scratch, so there is no legacy code
   pinning it to 3.x; a downgrade would trade a verified-stable current
   release for an older line with a shorter remaining support window, purely
   to match an early planning note. No technical driver justified it.
2. **Defer the decision and start Phase 2 on the undeclared mix.**
   Rejected: Phase 2 (Flyway, datasource, Security config style) depends on
   the Boot line; building on an undecided version invites rework and
   contradictory docs.

## Why Boot 4.1.1 Was Selected

- The project starts from scratch — no migration cost, no 3.x lock-in.
- 4.1.1 is currently a stable release per official Spring documentation.
- Java 21 is officially supported (min Java 17, max Java 26 for Boot 4.1.1).
- Starting on the current line maximises the support window and portfolio
  relevance; Boot 4.x Security/JPA/test-starter coordinates are the ones the
  team will actually verify against during implementation.

## Consequences

- Positive: single declared baseline (`pom.xml` authoritative); all future
  dependency additions and Security configuration follow Boot 4.x /
  Spring Framework 7.x conventions; docs are now consistent (AGENTS.md §1,
  README.md, PROJECT_STATUS.md, architecture docs).
- Negative / obligations: contributors must consult Boot 4.x (not 3.x)
  documentation — notably Spring Security configuration style, test-starter
  coordinates (`-test` artifacts), and Framework 7.x behavior. Portfolio
  reviewers expecting Boot 3.x need the one-line explanation: greenfield
  project, stable 4.1.1, supported Java 21 (this ADR).
- Follow-ups: none required for the version itself. Numbers assigned later:
  Phase 1 took ADR-003 PostgreSQL, ADR-004 Flyway, ADR-005 Testcontainers;
  Phase 2A took ADR-006 identity/timestamps and ADR-007 ownership;
  Phase 2B took ADR-008 service/counter capability;
  Phase 2C took ADR-009 user/roles/staff assignment;
  Phase 2D took ADR-010 working hours & holidays;
  Phase 2E took ADR-011 appointment booking;
  Phase 2F took ADR-012 queue engine;
  Phase 2G took ADR-013 service session;
  Phase 2H took ADR-014 queue aging;
  Phase 2I took ADR-015 wait-time estimation;
  Phase 3A took ADR-016 application events;
  Phase 3B took ADR-017 Redis foundation;
  Phase 3C took ADR-018 real-time queue + WebSocket.
  ADR-019 shared-DB multi-tenancy remains future.

## Compatibility Considerations

- Language/runtime: Java 21 satisfies Boot 4.1.1 (≥17, ≤26); local build
  verified with Java 21 (Temurin 21.0.12.1).
- Framework: Boot 4.1.1 manages Spring Framework 7.0.9+; Jakarta EE baseline
  follows Boot 4.x (not 3.x) — check Framework 7.x docs when touching
  persistence, validation, or security.
- Build: Maven wrapper build works (`compile` succeeds); the `contextLoads`
  datasource error is orthogonal (no datasource configured yet) and is
  addressed in Phase 2, not here.
- Ecosystem additions (Flyway, Security/JWT, Redis, Testcontainers,
  OpenAPI) must be added in their proper phases using Boot 4.1.x-managed
  versions — never copy 3.x-era coordinates or config snippets blindly.
