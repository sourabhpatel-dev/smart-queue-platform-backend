# ADR-025 — User & Staff Assignment API

Status: Accepted (Phase 3J, 2026-10-06).

## Context

Users, roles, and staff assignments exist since Phase 2C/3F, but only
login consumed them. Phase 3J exposes them for organization
administration — the most security-sensitive surface so far (emails are
personal data, roles gate authority, credentials neighbor the API).
The 3H/3I template covers mechanics; five decisions are genuinely new.

## Decision

1. **Directory visibility tiers.** The user list and foreign user records
   are management data (MANAGER/ADMIN only) — customers and staff cannot
   enumerate org emails. Two narrow exceptions, both new policy methods:
   operational roster visibility (managers/admins + staff assigned to
   that branch; customers denied, unlike token-only queue displays) and
   self-or-manager reads (own record, own assignment list). No controller
   role checks anywhere.
2. **ADMIN stays organization-scoped.** Granting `ADMIN` creates an
   organization admin — there is no platform admin to grant, and the new
   admin still gets scope-safe `404`s outside its org (proven by test).
   Roles replace wholesale on PUT; absent roles default to `CUSTOMER`.
3. **Email is immutable identity.** Set once (normalized, globally
   unique, constraint-arbitrated `409`s), never changed afterwards —
   login keys must not drift under clients. PUT carries no email field.
4. **Passwords at creation only.** An optional plaintext password is
   BCrypt-hashed immediately by the existing credential service (never
   stored/returned); omitting it leaves a credential-less user that fails
   login generically. No change/reset workflow and no self-service profile
   editing in this phase — openly deferred, not missing. Deactivation
   preserves all history and fails login generically (regression-tested
   end to end through real HTTP login).
5. **Assignments require the STAFF role** (rows for other roles would be
   inert — the policy only consults assignments for STAFF holders — so
   they are rejected, not stored). The `(user, branch)` unique ignores
   status by design: reactivation, not a second row, is the path back
   (`409` otherwise). Deactivation revokes branch access immediately
   while preserving the row; removal deletes nothing.
6. **Mechanics follow 3H/3I unchanged:** nested user-assignment routes
   plus flat assignment items, branch roster nested under branches,
   paginated allow-list listing (users add an explicit role filter via one
   nullable JPQL search; assignments add nested `user.email` sorting),
   PUT replacement, idempotent commands, `201` + `Location`, no DELETE,
   no migration, no new dependencies, no concurrency changes.

## Alternatives considered

- **Staff-wide user directory:** rejected — org email enumeration to
  every staff member is a PII leak without an operational need; rosters
  cover the legitimate case.
- **Customer roster access:** rejected — queue displays are token-only
  precisely so customers learn nothing personal; rosters carry emails.
- **Email mutability:** rejected — login identity must be stable; support
  flows can recreate accounts instead.
- **Password change/reset now:** rejected as scope expansion (explicitly
  out of phase); creation-time provisioning plus the deferred flow cover
  the lifecycle honestly.
- **Pre-check uniqueness:** rejected (racy) — constraints arbitrate, the
  API translates.
- **Paginated capability-style arrays for rosters:** rosters are
  unbounded per branch — paginated envelope, unlike tiny capability sets.

## Consequences

- The visibility-tier pattern (management directory + operational roster
  + self reads) is available to future sensitive surfaces.
- 22 black-box HTTP tests + 2 policy unit tests; auth-lifecycle
  regression through real login.
- Intentionally deferred: deletions, transfers, email change, password
  workflows, self-service editing, platform admin, multi-org users.
