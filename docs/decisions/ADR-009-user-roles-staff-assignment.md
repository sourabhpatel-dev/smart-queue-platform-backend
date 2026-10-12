# ADR-009: User Identity, Role Grants & Staff Assignment Integrity

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 2C — Users, Roles & Staff Assignments)
- Relates to: AGENTS.md §§2/3, ADR-006, ADR-007, ADR-008, V4 migration

## Context

Phase 2C adds people to the tenancy tree: accounts owned by an organization,
a role vocabulary for future authorization, and proof that a user works at a
branch. The traps: ambiguous login identities across tenants, a role model
that cannot grow to multi-role, and staff links that cross organizations.

## Decision

- **User owned by one Organization** (lazy `ManyToOne`, no cascade,
  explicit FK). Table is `app_user` — `USER` is reserved in PostgreSQL.
  No `Organization.users` collection: nothing navigates it.
- **Email globally unique** (`UNIQUE`, normalized to lowercase on the way
  in). Rationale: email is the future login identity, and login happens
  *before* any tenant is known — org-scoped emails would make "which
  account am I signing into?" ambiguous (including password-reset
  ambiguity). One email → one account; organization membership and roles
  resolve after authentication. Display names carry no uniqueness.
- **Roles: no Role entity.** Grants live in a `user_role (user_id, role)`
  join table with the `RoleType` enum (`CUSTOMER/STAFF/MANAGER/ADMIN`)
  persisted by name, mapped via `@ElementCollection` — multi-role from day
  one. Rationale: roles are a fixed *platform vocabulary*, not tenant data,
  so an entity would buy seed rows, an extra join, and an org-scoping
  puzzle for zero current benefit. Tenant scoping comes from
  `User.organization` + `StaffAssignment`, never from the role. Each grant
  row maps directly to one future Spring Security authority. No default
  role is assigned — who grants what is a registration/admin-flow decision
  for a later phase, not an invented invariant.
- **StaffAssignment as an association entity** (`staff_assignment`):
  (organization, user, branch) + `ACTIVE`/`INACTIVE` status. Separate
  entity (not a bare join) because it carries its own lifecycle state and
  may later carry scheduling metadata — the same reasoning as ADR-008's
  capability. Customers need no assignment; staff hold one or more;
  managers/admins build on the same primitives later. No authorization
  rules are encoded — only the primitives future security builds on.
- **Cross-organization integrity in the DB** (Phase 2B pattern):
  the row pins `organization_id` with composite FKs
  `(user_id, organization_id)` and `(branch_id, organization_id)` (backed
  by `UNIQUE (id, organization_id)` targets, the branch one added to the
  existing table via V4 — V2 untouched). A user from A assigned to a
  branch from B is rejected by the database on every path, proven by a
  raw-SQL test; a constructor guard fails fast. JPA maps simple joins —
  the migration owns the composites.
- **Uniqueness:** `uq_staff_user_branch (user_id, branch_id)` — one row per
  user/branch. Organization is deliberately NOT in the key: the branch
  already pins it.
- **Indexes:** `idx_user_organization` ("users in an organization"),
  `idx_staff_branch` ("assignments for a branch"); `user_role`'s PK
  serves "roles for a user" leftmost. Nothing else.
- **No passwords, no Security, no APIs.** Password hashes, tokens, login
  flows, and endpoint authorization belong to the security phase — adding
  hash columns now would be speculative schema. No services/controllers/
  DTOs: persistence-only, validated by repository tests. No runtime staff
  states (`SERVING`, `BUSY`): those are queue-phase operational modeling.

## Alternatives Considered

1. **Org-scoped emails.** Rejected: ambiguous login/reset identity before
   tenant resolution; the project has no workspace-aware login flow.
2. **Single role enum column on User.** Rejected: hard-codes single-role
   and makes the inevitable multi-role migration a data migration; the
   requirement explicitly warns against this dead end.
3. **Role entity + seed rows.** Rejected: over-engineering — an extra table,
   join, and seeding ceremony for a fixed four-value vocabulary with no
   per-role metadata.
4. **App-only cross-org check.** Rejected for the critical invariant: the
   task mandates database enforcement, and raw-SQL bypass must fail.

## Consequences

- Security phase inherits: unambiguous email lookup, `User.organization`
  for tenant scoping, grant rows → authorities, assignments → branch
  access. Nothing to unpick.
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019, queue
  priority/aging strategy → fulfilled by ADR-014.
