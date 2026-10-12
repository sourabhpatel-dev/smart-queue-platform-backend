# ADR-023 — Branch Management API

Status: Accepted (Phase 3H, 2026-10-06).

## Context

Phase 3G built the REST foundation (versioning, DTOs, one error envelope,
two representative reads, OpenAPI) and explicitly deferred pagination code
to the first list endpoint. Phase 3H delivers the first management
surface — organization read (already sufficient) plus branch
list/get/create/update/activate/deactivate — so it must settle the
semantics every later domain API will copy: who may manage, how listing
paginates/sorts, what update means, and how lifecycle states move.

## Decision

1. **Authorization matrix** (extends ADR-021, no second framework):
   reads keep the display rule (managers/admins org-wide, staff by ACTIVE
   assignment at the branch, customers own-org display; listing is
   org-scoped for every role); writes (create/update/activate/deactivate)
   require `MANAGER` or `ADMIN` **in the caller's own organization**.
   Staff and customers are denied management even at their own branches.
   `ADMIN` stays org-scoped, never platform-wide. Enforced by two new
   `TenantAccessPolicy` methods (`requireOrganizationManagement`,
   `requireBranchManagement` — scope first, then role), never by
   controller `if`-statements.
2. **Tenant derivation, not submission:** create/update bodies carry no
   `organizationId`; the organization comes from the principal
   (a supplied value is structurally ignored — no such DTO field).
   Ownership is immutable: no transfer flow exists.
3. **Pagination (first implementation of the 3G convention):**
   `page` (0-based, default 0), `size` (default 20, hard max 100),
   `sort` as repeatable `field[,asc|desc]` against an explicit per-endpoint
   allow-list (`name,status,createdAt,updatedAt` → mapped properties),
   deterministic `id ASC` tiebreak, optional `status` filter
   (case-insensitive). Out-of-range pages succeed empty; faults are
   `400 INVALID_PAGE/INVALID_SIZE/INVALID_SORT/INVALID_STATUS`.
   Reusable parsing/response live in `common/api`
   (`ApiPageRequest`/`ApiPage`/`InvalidRequestException`).
4. **Sort plumbing:** `sort` values are read raw from the servlet request
   because Spring's `List<String>` binding splits `sort=name,asc` on the
   comma into two entries, corrupting the documented shape (found by a
   failing test, fixed in phase). Documented in `docs/api/branches.md`
   so the next list endpoint copies the pattern, not the bug.
5. **Update = PUT full replacement** (no PATCH variant): every mutable
   field required each time; `status` and ownership excluded from the
   payload (supplied values ignored, proven by test). Rationale: one
   clear semantic beats two half-used ones for a small mutable surface.
6. **Lifecycle = explicit commands:** `POST …/activate` and
   `…/deactivate` (both `200`, idempotent) move `BranchStatus`, the
   unchanged canonical enum. No generic status writes, no `DELETE`
   (dependents must never cascade-delete; deactivated rows stay visible).
7. **Conflict arbitration:** no check-then-insert — `saveAndFlush` inside
   the try block lets `uq_branch_organization_name` arbitrate races,
   translated to `409 BRANCH_NAME_CONFLICT` with no SQL leakage.
   Cross-organization duplicates stay legal per the existing schema.
8. **No migration:** the branch schema already holds every field; V1–V9
   untouched. No new dependencies, no concurrency changes
   (no `synchronized`, no new locking — status writes are ordinary
   row updates).

## Alternatives considered

- **PATCH or PUT+PATCH:** rejected — partial semantics under PUT would be
  dishonest; two verbs for one small surface is unused optionality.
- **Status inside the update DTO:** rejected — arbitrary status writes
  bypass explicit lifecycle; commands keep transitions greppable.
- **Pre-check + friendly 409:** rejected as the mechanism (racy) — kept
  only as nothing; the constraint is the arbiter.
- **Globally unique branch names:** rejected — contradicts the existing
  composite unique and the multi-tenant model.
- **DELETE:** rejected — branches anchor services/counters/staff/calendar/
  appointments/queues; deletion is a product decision for a later phase,
  not an accident of CRUD completeness.
- **Generic filter/sort DSL:** rejected — explicit `status` param and a
  four-field allow-list; the next list endpoint copies the pattern.

## Consequences

- The matrix, pagination, PUT, and command patterns become the template
  for service/counter/staff/appointment/queue APIs.
- 28 black-box HTTP tests + 3 policy unit tests; one framework lesson
  recorded (raw sort values).
- Intentionally deferred: branch deletion/transfer, organization
  mutation/listing, all other domain APIs, frontend, notifications,
  analytics.
