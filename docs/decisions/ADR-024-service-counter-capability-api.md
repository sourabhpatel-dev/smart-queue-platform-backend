# ADR-024 — Service, Counter & Capability API

Status: Accepted (Phase 3I, 2026-10-06).

## Context

Phase 3H delivered the branch template (matrix, pagination, PUT,
commands, constraint-arbitrated conflicts). Services and counters reuse
that template, but three questions are genuinely new: how collection
URLs express branch ownership, how the `CounterCapability` association
(which already pins same-branch rows via V3 composite foreign keys) is
exposed without weakening it, and which status a well-formed but
branch-violating request deserves.

## Decision

1. **Nested collections, flat items:** lists and creates nest under
   `/api/v1/branches/{branchId}/…` (ownership visible in the path,
   scoped before any row is touched); item reads/writes/commands stay
   flat (`/api/v1/services/{id}`, `/api/v1/counters/{id}`). One
   controller per route root keeps class-level mappings honest
   (`ServiceController` + `BranchServicesController`, and the counter
   equivalents, plus a dedicated `CounterCapabilityController`).
   Future domain APIs copy this split.
2. **Authorization unchanged:** the 3H matrix verbatim (display reads,
   MANAGER/ADMIN org-scoped writes) via the existing
   `requireBranchAccess` / `requireBranchManagement` — no new policy
   methods, no controller checks.
3. **Capability API on the existing model:** no second association
   mechanism. List returns a plain service-name-ordered array (bounded
   per-counter sets don't need pagination); add takes
   `{"serviceId"}` → `201` + `Location`; remove is `DELETE` → `204`,
   missing → `404 CAPABILITY_NOT_FOUND` (a stale client view is worth
   signaling; documented over silent success). Removal touches only the
   association row.
4. **Same-branch invariant, two layers:** the application checks
   counter-branch vs service-branch explicitly; the V3 composite foreign
   keys stay the backstop. Cross-branch attempts (same org) → **`422
   CAPABILITY_BRANCH_MISMATCH`** — well-formed but invariant-violating,
   which neither 400 nor 409 describes honestly. The only new contract
   status; cross-organization rows never reach it (scope-safe `404`s).
5. **Minutes, not durations, at the boundary:** requests carry
   `durationMinutes` (the storage unit), eliminating the entity's
   sub-minute edge at the API layer; responses echo the same integer
   instead of a framework duration structure.
6. **Conflicts constraint-arbitrated throughout:** `saveAndFlush` inside
   the try block for service/counter names and capability pairs —
   `409 SERVICE_NAME_CONFLICT` / `COUNTER_NAME_CONFLICT` /
   `CAPABILITY_ALREADY_EXISTS`, no SQL leakage, no check-then-insert.
7. **Deactivation never cascades:** capabilities survive counter
   deactivation (reactivation restores service); service deactivation
   touches no appointment/queue/session state. No DELETE for services
   or counters.
8. **No migration, no new dependencies, no concurrency changes.**

## Alternatives considered

- **Flat collections with `?branchId=`:** rejected — ownership as a
  query param re-invites client-controlled scope; the path makes it
  structural.
- **Service ids embedded in counter create/update:** rejected — hides
  the association behind an unrelated payload; capabilities stay their
  own concern with their own authorization and errors.
- **`PUT` full capability replacement:** rejected — set-replacement
  invites accidental mass-removal; add/remove are explicit and auditable.
- **Idempotent `204` for missing removals:** rejected — `404` surfaces
  stale views; the state commands (activate/deactivate) stay idempotent
  because repeating a state is harmless, while deleting nothing is a
  client mistake.
- **400 for branch mismatch:** rejected — the request is well-formed;
  422 names the semantic violation (documented in the contract table).
- **Paginated capability lists:** rejected — per-counter sets are tiny;
  an envelope would be ceremony without value.

## Consequences

- The nested-collection/flat-item split, capability pattern, and 422
  become available to appointment/queue APIs.
- 23 black-box HTTP tests; no policy-unit additions (no new policy
  methods — the matrix rides the proven 3H tier).
- Intentionally deferred: deletions, transfers, capability metadata,
  batch writes, all other domain APIs.
