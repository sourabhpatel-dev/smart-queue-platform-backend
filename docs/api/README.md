# API Documentation

Versioned REST surface under **`/api/v1`** (see
[`rest-foundation.md`](rest-foundation.md) for the shared conventions and
[ADR-022](../decisions/ADR-022-rest-api-contract-foundation.md) for the
decisions).

| Endpoint | Purpose | Details |
|---|---|---|
| `POST /api/v1/auth/login` | Email + password → bearer token | [`auth.md`](auth.md) |
| `GET /api/v1/organizations/{organizationId}` | Read the caller's own organization | [`rest-foundation.md`](rest-foundation.md) |
| `GET /api/v1/branches` | List own-organization branches (paginated, sortable, status filter) | [`branches.md`](branches.md) |
| `GET /api/v1/branches/{branchId}` | Read a branch of the caller's organization | [`branches.md`](branches.md) |
| `POST /api/v1/branches` | Create a branch (MANAGER/ADMIN, org from principal) | [`branches.md`](branches.md) |
| `PUT /api/v1/branches/{branchId}` | Replace mutable branch fields (MANAGER/ADMIN) | [`branches.md`](branches.md) |
| `POST /api/v1/branches/{branchId}/activate` | Activate (MANAGER/ADMIN, idempotent) | [`branches.md`](branches.md) |
| `POST /api/v1/branches/{branchId}/deactivate` | Deactivate — not deletion (MANAGER/ADMIN, idempotent) | [`branches.md`](branches.md) |
| `GET /api/v1/branches/{branchId}/services` | List branch services (paginated, sortable, status filter) | [`services-and-counters.md`](services-and-counters.md) |
| `POST /api/v1/branches/{branchId}/services` | Create a service (MANAGER/ADMIN → `201` + `Location`) | [`services-and-counters.md`](services-and-counters.md) |
| `GET /api/v1/services/{serviceId}` | Read a service | [`services-and-counters.md`](services-and-counters.md) |
| `PUT /api/v1/services/{serviceId}` | Replace service fields (MANAGER/ADMIN) | [`services-and-counters.md`](services-and-counters.md) |
| `POST /api/v1/services/{serviceId}/activate\|deactivate` | Lifecycle, not deletion (MANAGER/ADMIN) | [`services-and-counters.md`](services-and-counters.md) |
| `GET /api/v1/branches/{branchId}/counters` | List branch counters (paginated, sortable, status filter) | [`services-and-counters.md`](services-and-counters.md) |
| `POST /api/v1/branches/{branchId}/counters` | Create a counter (MANAGER/ADMIN → `201` + `Location`) | [`services-and-counters.md`](services-and-counters.md) |
| `GET /api/v1/counters/{counterId}` | Read a counter (capabilities not embedded) | [`services-and-counters.md`](services-and-counters.md) |
| `PUT /api/v1/counters/{counterId}` | Rename a counter (MANAGER/ADMIN) | [`services-and-counters.md`](services-and-counters.md) |
| `POST /api/v1/counters/{counterId}/activate\|deactivate` | Lifecycle, keeps capabilities (MANAGER/ADMIN) | [`services-and-counters.md`](services-and-counters.md) |
| `GET /api/v1/counters/{counterId}/capabilities` | List served services (plain array) | [`services-and-counters.md`](services-and-counters.md) |
| `POST /api/v1/counters/{counterId}/capabilities` | Add capability (MANAGER/ADMIN → `201`) | [`services-and-counters.md`](services-and-counters.md) |
| `DELETE /api/v1/counters/{counterId}/capabilities/{serviceId}` | Remove association only (→ `204`) | [`services-and-counters.md`](services-and-counters.md) |
| `GET /api/v1/users` | List organization users, MANAGER/ADMIN (paginated, status/role filter) | [`users-and-staff.md`](users-and-staff.md) |
| `POST /api/v1/users` | Create a user, org from principal (→ `201` + `Location`) | [`users-and-staff.md`](users-and-staff.md) |
| `GET /api/v1/users/{userId}` | Read a user (self or MANAGER/ADMIN) | [`users-and-staff.md`](users-and-staff.md) |
| `PUT /api/v1/users/{userId}` | Replace profile + roles (MANAGER/ADMIN) | [`users-and-staff.md`](users-and-staff.md) |
| `POST /api/v1/users/{userId}/activate\|deactivate` | Lifecycle, login follows status (MANAGER/ADMIN) | [`users-and-staff.md`](users-and-staff.md) |
| `GET /api/v1/users/{userId}/staff-assignments` | List a user's assignments (self or MANAGER/ADMIN) | [`users-and-staff.md`](users-and-staff.md) |
| `POST /api/v1/users/{userId}/staff-assignments` | Assign user to branch (MANAGER/ADMIN → `201`) | [`users-and-staff.md`](users-and-staff.md) |
| `GET /api/v1/branches/{branchId}/staff` | Branch roster (managers/admins + assigned staff) | [`users-and-staff.md`](users-and-staff.md) |
| `GET /api/v1/staff-assignments/{assignmentId}` | Read an assignment | [`users-and-staff.md`](users-and-staff.md) |
| `POST /api/v1/staff-assignments/{assignmentId}/activate\|deactivate` | Lifecycle, history preserved (MANAGER/ADMIN) | [`users-and-staff.md`](users-and-staff.md) |
| `POST /api/v1/branches/{branchId}/appointments` | Book for self (any role → `201` + `Location`) | [`appointments.md`](appointments.md) |
| `GET /api/v1/appointments` | Role-routed list (paginated, facets, overlap window) | [`appointments.md`](appointments.md) |
| `GET /api/v1/appointments/{appointmentId}` | Privacy-first read (owner/operational; else 404) | [`appointments.md`](appointments.md) |
| `POST /api/v1/appointments/{appointmentId}/cancel` | Owner or branch management | [`appointments.md`](appointments.md) |
| `POST /api/v1/appointments/{appointmentId}/complete\|no-show` | Operational scope (assigned staff + managers) | [`appointments.md`](appointments.md) |
| `POST /api/v1/branches/{branchId}/queues/{queueId}/entries` | Walk-in join, self-service (→ `201` + `Location`) | [`queues.md`](queues.md) |
| `GET /api/v1/branches/{branchId}/queues` | Branch queues (one row per service) | [`queues.md`](queues.md) |
| `GET /api/v1/queues/{queueId}` | Live snapshot (ranked, positions + waits) | [`queues.md`](queues.md) |
| `GET /api/v1/queues/{queueId}/entries` | Paged entries (filters, no live positions) | [`queues.md`](queues.md) |
| `GET /api/v1/queue-entries/{entryId}` | Privacy-first read (owner/operational; else 404) | [`queues.md`](queues.md) |
| `POST /api/v1/counters/{counterId}/call-next` | Atomic claim (→ claimed entry) | [`queues.md`](queues.md) |
| `POST /api/v1/queue-entries/{entryId}/start-serving` | CALLED → SERVING + session (operational) | [`queues.md`](queues.md) |
| `POST /api/v1/queue-entries/{entryId}/complete` | SERVING → COMPLETED + session end | [`queues.md`](queues.md) |
| `POST /api/v1/queue-entries/{entryId}/cancel` | Owner or branch management | [`queues.md`](queues.md) |
| `POST /api/v1/queue-entries/{entryId}/no-show` | Operational scope | [`queues.md`](queues.md) |
| `GET /api/v1/service-sessions` | Operational list (facets, newest-first) | [`service-sessions.md`](service-sessions.md) |
| `GET /api/v1/service-sessions/{sessionId}` | Privacy-first read (operational; else 404) | [`service-sessions.md`](service-sessions.md) |
| `GET /api/v1/counters/{counterId}/current-session` | Running session, or 404 when idle | [`service-sessions.md`](service-sessions.md) |
| `GET /api/v1/counters/{counterId}/service-sessions` | Paged history, newest-first | [`service-sessions.md`](service-sessions.md) |

The organization/branch reads are the Phase 3G representative endpoints:
they prove the secured-controller pattern (JWT → principal → tenant scope
→ policy → service → DTO). They are not the start of a CRUD surface.

Phase 3H adds the first management surface: branch list/get/create/update/
activate/deactivate ([`branches.md`](branches.md), [ADR-023](../decisions/ADR-023-branch-management-api.md)).

Phase 3I adds services, counters, and capabilities
([`services-and-counters.md`](services-and-counters.md),
[ADR-024](../decisions/ADR-024-service-counter-capability-api.md)).

Phase 3J adds users and staff assignments
([`users-and-staff.md`](users-and-staff.md),
[ADR-025](../decisions/ADR-025-user-staff-assignment-api.md)).

Phase 3K adds appointment booking and lifecycle
([`appointments.md`](appointments.md),
[ADR-026](../decisions/ADR-026-appointment-booking-api.md)).

Phase 3L adds walk-in queue operations
([`queues.md`](queues.md),
[ADR-027](../decisions/ADR-027-queue-management-api.md)).

Phase 3M adds service-session operational reads
([`service-sessions.md`](service-sessions.md) — read-only by design, no
new ADR: it applies the established templates without a new
architectural decision).

Do not add speculative contracts in advance (AGENTS.md §13). When each
domain arrives, its contract documents request/response shapes, status
codes, auth rules, and pagination/error-envelope conventions here.

Implemented contracts: `auth.md`, `rest-foundation.md`, `branches.md`,
`services-and-counters.md`, `users-and-staff.md`, `appointments.md`,
`queues.md`, `service-sessions.md`, `notifications.md`,
`analytics.md`. Planned areas: none currently — each future domain
arrives with its contract.

## Postman Handover & Testing

- Postman Collection and Environment: `../../postman/` (`SmartQueue.postman_collection.json`, `SmartQueue.local.postman_environment.json`)
- Comprehensive Guide: [`POSTMAN_GUIDE.md`](POSTMAN_GUIDE.md)

## OpenAPI

Generated contract: `GET /v3/api-docs` (JSON). Interactive UI:
`/swagger-ui/index.html` (local development; disable in production with
`springdoc.swagger-ui.enabled=false`). The document carries the `bearerAuth`
(JWT) scheme; public endpoints render unlocked, authenticated endpoints
carry the requirement.
