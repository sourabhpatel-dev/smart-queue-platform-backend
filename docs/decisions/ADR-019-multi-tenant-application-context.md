# ADR-019: Multi-Tenant Application Context & Isolation

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3E — Multi-Tenant Context & Application Isolation)
- Relates to: AGENTS.md §§2/3/4, ADR-007, ADR-009, ADR-012, ADR-016, ADR-018,
  ADR-020, `tenant/`, scope-aware repositories

## Context

The domain already anchors every tenant-owned row to an organization
(directly or transitively via branch), and composite foreign keys reject
invalid cross-tenant *relationships* at write time. But application code
had no tenant boundary: `QueueCallService.callNext`,
`QueueEntryLifecycleService.complete/cancel/markNoShow`, and
`QueueWaitTimeEstimator.estimate` loaded entities by bare id and trusted
the caller; only the two creation paths (`join`, `book`) compared
organizations. A caller holding any valid id could read or mutate another
tenant's rows, and nothing distinguished "missing" from "forbidden".

## Decision

- **Scope model**: organization scope (always present) plus optional branch
  scope (narrows to one branch; absent means organization-wide). Verified
  against the domain: Organization/User are organization-scoped; Branch/
  Service/Counter/Capability/WorkingHour/Holiday are branch-scoped;
  StaffAssignment/Appointment/Queue/QueueEntry/Session/Realtime-state are
  organization+branch. Documented as the scope matrix (§16 of the brief).
- **`TenantContext`** (immutable record, `organizationId` required,
  `branchId` optional): the *where*, never the *who* or *what* — no User,
  no roles, no JWT, no HTTP objects, no entities, no mutable state.
  Tenant context ≠ authentication ≠ authorization ≠ role (each a separate
  concept; future security combines
  `AuthenticatedPrincipal + TenantContext + AuthorizationPolicy`).
- **`TenantContextHolder`** (private ThreadLocal, explicit
  set/get/getOrNull/clear/restore): synchronous request-oriented delivery
  without a static-mutable-field pattern. Fail-closed: `get()` throws
  `TenantContextRequiredException` when unset — null is never "all
  tenants". Boundaries that install their own scope (event bridge,
  controller, scheduler) save the previous context and **restore** it
  afterwards — never blindly clear — so a nested dispatch cannot destroy
  its caller's scope (found by failing tests, fixed in this phase).
- **Repository isolation**: scope-aware finders (`findByIdAndOrganizationId`
  on Queue/QueueEntry/Appointment; `findByIdAndBranchId` on
  Service/ServiceCounter) return empty for out-of-scope ids, so services
  surface scope-safe not-found errors that reveal nothing about other
  tenants' existence. No `@TenantId`, no Hibernate filters, no
  multi-tenancy mode — aggregate boundaries are enforced at the
  repository/service level (shared database, logical isolation; no
  schema-per-tenant or database-per-tenant).
- **Service enforcement**: call/lifecycle/estimate/projection paths resolve
  through the context; creation paths (`join`, `book`) keep their existing
  cross-organization checks. Database constraints (FKs, composite FKs,
  uniques, exclusions) stay untouched — defense in depth.
- **Background execution**: scheduler and event consumers never depend on
  a pre-set thread-local. The scheduler resolves each queue's
  organization/branch from PostgreSQL and installs an explicit per-queue
  context; the bridge installs one from the event payload. The event
  payload remains the scope for async consumers.
- **Boundaries left alone**: Redis key convention unchanged (namespacing,
  not authorization); WebSocket stays unauthenticated by documented
  decision, with path-consistency validation preserved and ready to
  consume tenant scope later. No Spring Security, no JWT, no REST, no
  config system, no migration (V1–V8 untouched).

## Alternatives Considered

1. **Hibernate filters / `@TenantId` / multi-tenancy modes.** Rejected:
   global filters hide scope in magic, complicate the claim/session native
   SQL, and buy nothing over explicit repository methods at this size.
2. **Passing `TenantContext` as a parameter everywhere.** Rejected for
   now: it would churn every service signature and all 233 callers for no
   behavioral gain; the holder gives the same explicitness at boundaries
   with minimal churn. Revisit when the REST layer arrives.
3. **`getOrNull` + skip-validation fallback.** Rejected: violates
   fail-closed; "no context" must error, never silently unfilter.
4. **InheritableThreadLocal for pool threads.** Rejected: pooled-thread
   inheritance is timing-fragile and hides leakage; worker threads install
   their own scope explicitly (proven in the concurrency tests).
5. **A separate `TenantScopeViolationException`.** Deferred: cross-scope
   access surfaces as scope-safe not-found, which is the desired external
   behavior; one exception (`TenantContextRequiredException`) covers the
   fail-closed path.

## Consequences

- Cross-tenant reads/writes by id are rejected at the application layer;
  wrong-tenant ids are indistinguishable from missing ids.
- 30 new tests (11 context lifecycle, 16 cross-tenant isolation incl.
  branch-level and missing-context cases, 3 background without HTTP
  context); full suite 263/263 green; no migration; no new dependencies.
- One existing expectation updated honestly: the cross-branch counter test
  now asserts scope rejection (`InvalidQueueRequestException`) instead of
  empty-claim (`NoWaitingQueueEntryException`) — the counter is outside
  the tenant scope before claiming is even attempted.
- Staff assignment remains the branch-access primitive (org membership
  alone grants no branch access); customer authorization beyond
  tenant isolation is deferred with the rest of security.
