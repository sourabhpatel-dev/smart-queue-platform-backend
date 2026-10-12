# ADR-001: Modular Monolith with Package-by-Feature

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (foundation phase)
- Relates to: AGENTS.md §§1–2, docs/architecture/system-architecture.md

## Context

The Smart Queue & Appointment Management Platform needs real ACID guarantees
(double-booking prevention, atomic queue claims), organization/branch tenant
isolation, and a codebase a single developer (and later a small team) can
build incrementally while remaining interview-explainable. The project starts
from a bare Spring Initializr skeleton (Boot entry point only, no domain
code). We must choose the service decomposition before domain modelling
begins (Phase 2).

## Decision

Build a **modular monolith**: one deployable Spring Boot application,
internally organized **by feature** (`auth`, `user`, `organization`,
`branch`, `service`, `counter`, `appointment`, `queue`, `notification`,
`analytics`, plus `common` for genuinely shared code), with thin controllers
→ transactional service layer → domain logic → Spring Data JPA repositories,
DTOs at the API boundary (never entities), and inter-module access through
owning-module services — not direct entity/repository reaches.

Explicitly defer: microservices, Kafka, Kubernetes, Elasticsearch, GraphQL,
and event-driven distributed architecture unless explicitly requested later.

## Alternatives Considered

1. **Layered monolith with global controller/service/repository packages.**
   Rejected: low cohesion, blurred ownership, easy to leak tenant scoping
   and business rules across domains; harder to explain module boundaries in
   an interview.
2. **Microservices from the start.** Rejected: no scale or team-size driver;
   distributed transactions would make double-booking prevention and atomic
   queue claims substantially harder; deployment/observability overhead is
   unjustified for a portfolio incremental build.
3. **Microservices-ready "distributed monolith" (shared libs + remote-call
   shapes).** Rejected: speculative complexity with no current consumer;
   violates incremental-development and simplicity priorities.

## Consequences

- Positive: single transaction scope for booking/claim invariants; simple
  build, test (Testcontainers), and Docker story; clear module ownership for
  tenant checks; modules remain extractable later if a real scale driver
  appears.
- Negative / obligations: discipline is required to keep module boundaries
  honest (code review + package-visibility conventions); must avoid the
  monolith decaying into a big ball of mud — `common` must stay minimal and
  cross-module entity access must be documented when it occurs.
- Follow-ups: ADR-003 (PostgreSQL, accepted Phase 1); ADR-004 (Flyway,
  accepted Phase 1); ADR-005 (Testcontainers strategy, accepted Phase 1);
  ADR-006 (entity identity/timestamps, accepted Phase 2A);
  ADR-007 (Organization–Branch ownership, accepted Phase 2A);
  ADR-008 (service/counter capability, accepted Phase 2B);
  ADR-009 (user/roles/staff assignment, accepted Phase 2C);
  ADR-010 (working hours & holidays, accepted Phase 2D);
  ADR-011 (appointment booking, accepted Phase 2E);
  ADR-012 (queue engine, accepted Phase 2F);
  ADR-013 (service session, accepted Phase 2G);
  ADR-014 (queue aging, accepted Phase 2H);
  ADR-015 (wait-time estimation, accepted Phase 2I);
  ADR-016 (application events, accepted Phase 3A);
  ADR-017 (Redis foundation, accepted Phase 3B);
  ADR-018 (real-time queue + WebSocket, accepted Phase 3C);
  ADR-019 (shared-DB multi-tenancy detail) when Organization/Branch modelling
  needs it.
