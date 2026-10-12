# AGENTS.md — Backend Governance (Smart Queue Platform)

> Scope: everything under `backend/`. All paths below are relative to
> `backend/`. Frontend work is governed by `../frontend/AGENTS.md`;
> repo-wide pointers live in the root `AGENTS.md`.
> If implementation changes an architectural decision, API, schema, or project
> structure, the corresponding documentation MUST be updated in the same change.

## 1. Project Goals

Smart Queue backend: production-quality, portfolio-worthy Spring Boot
engineering — concurrent-safe appointments, fair queues, honest waits,
strict multi-tenancy.

### Demonstrates (target stack)

- Java 21
- Spring Boot 4.1.1 (Boot 4.x line — decided in ADR-002; `pom.xml` is
  authoritative, never claim a version that is not in `pom.xml`)
- Spring Web (MVC)
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Spring Security
- JWT authentication
- Redis
- WebSocket / STOMP
- Bean Validation
- JUnit 5
- Mockito
- Spring Boot Test
- Testcontainers
- Docker / Docker Compose
- OpenAPI / Swagger

### Architecture priorities (in order)

1. Correctness
2. Security
3. Maintainability
4. Simplicity
5. Testability
6. Interview value
7. Scalability where appropriate

Do not introduce technologies simply to appear complex.

### Shape

- The application is a **modular monolith**. See ADR-001.
- Do NOT introduce the following unless explicitly requested:
  - Microservices
  - Kafka
  - Kubernetes
  - Elasticsearch
  - GraphQL
  - Event-driven distributed architecture

## 2. Architecture Rules

### Modular monolith, package-by-feature

Prefer package-by-feature over global `controller / service / repository`
folders. Current shape (`src/main/java/com/sourabh/smartqueue/`): `auth`,
`user`, `staff`, `organization`, `branch`, `service`, `counter`,
`workinghour`, `holiday`, `appointment`, `queue`, `notification`,
`analytics`, `membership`, `platform`, `discovery`, `email`, `tenant`,
`infrastructure`, `common`.

- The exact package structure may evolve when justified. Document the change.
- Each feature module owns its controllers, services, domain logic,
  repositories, DTOs, and mappers. `common` holds only genuinely shared code
  (exception handling, API envelope, auditing base classes, security principals).
- No module may reach into another module's JPA entities or repositories
  directly to bypass its service/API boundary without a documented reason.

### Layering

```text
Controller (thin: HTTP, validation, auth wiring)
    ↓
Application / Service layer (@Transactional boundaries live here)
    ↓
Domain / business logic (pure rules, state transitions)
    ↓
Repository / data access (Spring Data JPA)
```

- Business logic MUST NOT live in controllers.
- Controllers: map HTTP ↔ DTOs, trigger validation, delegate to services.
- Transactions belong at the application/service boundary, not in controllers
  or repositories.
- Do NOT expose JPA entities through REST APIs.
- Use request/response DTOs (immutable `record`s preferred) with explicit
  mapping between persistence models and API models. Avoid reflection-heavy
  generic mappers for core domains.

## 3. Multi-Tenant Architecture

Hierarchy:

```text
Organization
    ↓
Branch
    ↓
Services / Counters / Staff / Appointments / Queues
```

- Logical multi-tenancy uses a **shared PostgreSQL database** (ADR-019).
- Customers are platform-level identities; `STAFF`/`MANAGER`/`ADMIN` act
  through `OrganizationMembership` — the sole authorization fact (ADR-040).
- Every organization-owned resource must ultimately be associated with an
  organization (directly or transitively via branch/service/counter).
- NEVER trust an `organizationId`, `branchId`, or resource ID supplied by a
  client without checking authorization and ownership.
- Tenant isolation is enforced at the **business/application layer**
  (service checks + query scoping), not merely by hoping the client sends the
  right IDs. Every query/mutation on tenant-owned data must be scoped to the
  authorized organization/branch.

## 4. Roles

Roles: `CUSTOMER`, `STAFF`, `MANAGER`, `ADMIN` (+ derived `PLATFORM_ADMIN`
for onboarding review, ADR-041).

- Role-based authorization is necessary but NOT sufficient.
- The application must also verify whether the authenticated user is actually
  allowed to access the requested organization, branch, counter, service,
  appointment, or queue (resource-level / ownership authorization).
- Deny by default. Every endpoint declares who may call it and on which
  resources.

## 5. Core Domain (implemented — extend, don't re-decide)

`User`, roles, `Organization`, `Branch`, services, counters, capability
links, `WorkingHour`, `Holiday`, `Appointment`, `Queue`, `QueueEntry`,
`ServiceSession`, `Notification`, membership and onboarding rows all exist
with migrations V1–V21. Preserve clear ownership and relationships
(e.g. an appointment belongs to exactly one organization + branch + service;
a queue entry belongs to exactly one queue/branch). New domain concepts
still require an explicit phase and, where architectural, an ADR.

## 6. Appointment Requirements

Booking, confirmation, cancellation, rescheduling rules, no-show handling,
capacity management, working hours, holidays, service duration, and
availability calculation live in the booking engine.

- **Double-booking prevention is a major engineering requirement.**
- Concurrency MUST be handled with proper database transactions, locking
  (`PESSIMISTIC_WRITE` / optimistic versioning where appropriate) and/or
  database constraints (exclusion / unique constraints where they fit).
- NEVER use Java `synchronized` as the solution to database concurrency
  problems. It does not work across instances or transactions.

## 7. Queue Requirements

Tokens, priority handling, call-next, service start/completion, skip,
no-show, cancellation, transfer/reassignment where appropriate, plus
estimated waiting time.

- Queue processing must be safe under concurrent requests.
- Two staff members must never receive the same waiting customer
  (atomic claim semantics required).
- Priority must not cause indefinite starvation of normal customers.
  The capped-aging approach (ADR-014) is the decided algorithm; changes to
  its fairness properties require an ADR.

## 8. Waiting Time

Estimates consider customers ahead, priority, active counters, historical
service duration, current progress, and queue position (ADR-015).

- Actual service duration is captured via `ServiceSession` data.
- Do NOT invent performance metrics or fake benchmark results. Only report
  measured values and label estimates as estimates.

## 9. PostgreSQL and Redis

- **PostgreSQL is the source of truth.** Redis is a supporting technology.
- Do NOT use Redis as the authoritative store for core business state.
- Redis holds queue-state snapshots, wait-time caches, short-lived
  availability caches, and rate-limit budgets where implemented.
- Caching must never compromise correctness. Every cached value needs a
  defined invalidation/expiry story. On doubt, read from PostgreSQL.

## 10. Real-Time Communication

WebSocket/STOMP carries live queue snapshots: subscribe
`/topic/organizations/{o}/branches/{b}/queues/{q}`, snapshot via
`/app/…/snapshot`, notification echo on `/user/queue/notifications`.

- Follow the existing subscription topology, message envelope, and
  authorization model (ADR-018, ADR-021). Changes require an ADR.
- Anything a client can read must be safe to broadcast — snapshot payloads
  carry tokens/priority/status/waits only, never customer or staff identity.

## 11. Notifications

- Notifications use the `NotificationSender` / `EmailSender` abstractions so
  delivery mechanisms can evolve.
- Do NOT tightly couple business logic directly to a specific provider
  (no `JavaMailSender` / SMS SDK calls inside appointment or queue services).

## 12. Security

Spring Security + JWT + BCrypt. Flow:

```text
Login → JWT → Spring Security authentication → role authorization
    → organization/branch/resource authorization → business operation
```

- Security is implemented deliberately and tested (happy path + forbidden +
  unauthenticated + cross-tenant access cases).
- NEVER log passwords, JWT secrets, tokens, or other sensitive credentials.
- Secrets come from environment variables / mounted secrets, never hard-coded.
- JWT secret and expiry are documented as configuration (without values).
- Do not change the platform-admin fail-closed behavior (ADR-041) or
  tenant-isolation protections without an explicit phase.

## 13. API Rules

- REST APIs are domain-oriented under `/api/v1`: auth, users, organizations,
  branches, services, counters, appointments, queues, staff, notifications,
  analytics, discovery, onboarding, invitations.
- Do NOT create speculative endpoints in advance.
- Follow the versioning/pagination/error-envelope conventions in
  `docs/api/rest-foundation.md` everywhere.

## 14. Database Rules

- PostgreSQL is the database. Flyway owns schema evolution.
- NEVER manually modify production-style database structure without a
  versioned Flyway migration (`V1__...`, `V2__...`). Never edit an applied
  migration — always add a new version.
- Use appropriate primary keys, foreign keys, unique constraints, indexes,
  NOT NULL constraints, and CHECK constraints where useful.
- Do NOT blindly index every column. Index for documented query patterns and
  concurrency needs (e.g. availability lookups, queue claim queries).
- Database design must consider concurrency (locking, constraints) and query
  patterns from day one.

## 15. Testing Rules

Every meaningful business feature gets tests at the appropriate level:

- unit tests (domain rules, calculators, mappers)
- integration tests (service + DB)
- repository tests where useful
- controller/API tests (including validation + security)
- security tests (auth + cross-tenant denial)
- concurrency tests where the feature claims concurrency safety
- Testcontainers for realistic PostgreSQL/Redis integration (no H2, no infra mocks)

Do NOT write meaningless tests solely to increase coverage. Tests verify
actual behavior: state transitions, invariants, authorization denials, and
concurrent-claim safety. Do not remove tests or weaken assertions to make a
reorganized project pass.

## 16. Documentation Is Mandatory

Documentation is part of the implementation. On every meaningful change,
update the appropriate docs (paths relative to `backend/`):

```text
README.md
docs/
├── architecture/     // system, modules, flows, strategies
├── decisions/        // ADRs
├── api/              // endpoint contracts per domain
├── database/         // schema, migrations, indexing rationale
├── development/      // setup, workflows, conventions
└── PROJECT_STATUS.md // current state, phases, debt
```

- Documentation must never intentionally go stale.
- Implementation change ⇒ update the affected doc in the same change:
  architecture decision → ADR; API change → `docs/api/`; schema change →
  `docs/database/` + migration; structure change → `docs/architecture/`.

## 17. README.md Rules

`backend/README.md` stays useful to a new developer and sufficient to run
the backend without reading the frontend README. It contains: purpose,
features, architecture, stack (actual, from `pom.xml`), prerequisites,
environment configuration, infrastructure startup, Flyway behavior, build
and test commands, run instructions, API/Postman pointers, auth overview,
tenant-isolation overview, email verification + Mailpit, platform-admin
configuration and limitations, honest limitations, links to `docs/`.

Do NOT fill it with fictional claims. If a feature is planned, label it
`Planned`, never `Available`.

## 18. PROJECT_STATUS.md

`docs/PROJECT_STATUS.md` is the single source of truth for project state.
It tracks: completed phases, current phase, next planned phase, implemented
vs. pending features, known limitations, technical debt, important decisions,
and tests currently available.

Update it whenever a phase or meaningful feature completes. It must always
match reality — verify against `pom.xml`, migrations, code, and test reports
before writing.

## 19. Architecture Documentation

Maintain under `docs/architecture/`: system architecture, module boundaries,
request flow, authentication flow, appointment flow, queue flow, concurrency
strategy, Redis usage, WebSocket flow, notification flow.

- Use Mermaid diagrams where they improve understanding.
- Keep diagrams synchronized with the actual implementation.
- NEVER present a diagram of unimplemented functionality without clearly
  marking it `Planned`.

## 20. Architecture Decision Records

Maintain under `docs/decisions/` as `ADR-NNN-short-title.md`. The series
(ADR-001–ADR-041, including frontend-experience ADRs 028–031) stays intact
in this one place — do not split it across projects.

- Create ADRs for meaningful architectural decisions only, not trivial details.
- Each ADR contains: Context, Decision, Alternatives considered, Consequences.
- ADR numbers are never reused. Superseded ADRs stay in place with a
  `Status: Superseded by ADR-XXX` header.

## 21. Coding AI Behavior

Before modifying code:

1. Inspect the existing repository.
2. Read this `AGENTS.md`.
3. Inspect relevant existing code.
4. Identify affected modules.
5. Explain the proposed approach briefly.
6. Implement only the requested scope.
7. Run relevant tests/build checks.
8. Review the resulting changes (`git status` / `git diff`, when Git is available).
9. Update documentation.
10. Summarize what changed.
11. Clearly state what remains to be done.

- Do NOT implement future phases unless explicitly requested.
- Do NOT silently introduce major architectural decisions. If one is
  necessary, explain it before implementation and record it as an ADR.

## 22. Incremental Development

Develop in small logical increments. Do NOT generate the entire application
at once. Each task ideally follows:

```text
Understand → Plan → Implement → Test → Document → Review
```

NEVER create large amounts of speculative code for future features. Scaffolds
with no behavior are not progress.

## 23. Code Quality

Prefer:

- meaningful names; small cohesive classes; clear responsibilities
- constructor injection (no field injection)
- immutable DTOs where appropriate (`record`s)
- explicit validation (Bean Validation at API boundary + domain invariants)
- centralized exception handling (`@ControllerAdvice` + stable error envelope)
- consistent API responses where appropriate
- `@Transactional` at appropriate application/service boundaries
- clear separation of concerns

Avoid:

- god classes; unnecessary abstractions; premature generic frameworks
- magic numbers; duplicated business logic
- exposing entities directly
- Lombok usage that obscures behavior (prefer explicit code in domain models)
- swallowing exceptions; logging sensitive information

## 24. Portfolio Quality

This project is interview material. Favor decisions explainable in an
interview. For important decisions, documentation answers:

- What problem were we solving?
- Why did we choose this approach?
- What alternatives did we consider?
- What are the trade-offs?
- How does it handle failure / concurrency / security?

NEVER fabricate performance numbers, users served, production deployments,
business impact, benchmark results, or real customers. Claim only what is
implemented and measured.

## 25. Git Discipline

- No destructive Git operations (`push --force`, history rewrites, mass
  deletes) without explicit instruction.
- Do NOT delete working code merely to restructure it unless requested.
- Prefer small, logical commits when performing Git operations.
- Suggested commit prefixes: `feat:`, `fix:`, `refactor:`, `test:`,
  `docs:`, `chore:`.
- Do NOT create commits unless requested by the user or explicitly instructed.
- This directory is currently not a Git checkout — do not fabricate Git
  results; report that instead.

## 26. Current Development Strategy

Phases through 4T (restructuring) are complete — see `docs/PROJECT_STATUS.md`
for the full order and what each phase delivered. Upcoming: Postman API
handover under `backend/postman/`, then interview preparation. Changes to
the order MUST be documented in `docs/PROJECT_STATUS.md` (and via ADR if
the change is architectural).

## 27. Definition of Done

Code + config + README + architecture docs + ADRs + project status evolve
together. A feature is NOT complete merely because its Java code works.

A feature is complete when:

- [ ] implementation exists
- [ ] validation exists where appropriate
- [ ] tests exist where appropriate
- [ ] configuration is documented
- [ ] API behavior is documented where applicable
- [ ] architecture documentation is updated where applicable
- [ ] database documentation/migrations are updated where applicable
- [ ] `docs/PROJECT_STATUS.md` reflects the current state

## 28. What To Do Right Now (Scope Guard)

Unless the user explicitly opens a new phase, stay in the requested scope.
Do NOT implement the Postman collection yet (reserved at `backend/postman/`
for the API handover phase), do NOT begin interview preparation, and do NOT
rewrite business logic, endpoints, schema, or authorization as part of
organizational changes.
