# ADR-007: Organization–Branch Ownership and Mapping Decisions

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 2A — Organization & Branch)
- Relates to: AGENTS.md §§3/5, ADR-006, V2 migration

## Context

Organization → Branch is the root of the tenancy hierarchy: all future
authorization must resolve `Branch → Organization`. This ADR pins the
relationship shape, naming constraints, and what was deliberately NOT built.

## Decision

- **Unidirectional `Branch → Organization`** (`@ManyToOne(fetch = LAZY,
  optional = false)`, explicit FK `fk_branch_organization`). No
  `Organization.branches` collection: nothing navigates that direction,
  and a collection would invite EAGER fetching and cascade mistakes.
  **No cascade** on the association: persisting a branch never persists an
  organization — the org must already exist (proven by test).
- **Organization names are NOT globally unique.** Display names repeat
  across tenants ("City Clinic" × 2 persists fine); the UUID is the unique
  business identifier. A global unique constraint would invent a business
  rule that does not exist.
- **Branch names are unique WITHIN an organization**
  (`uq_branch_organization_name` on `(organization_id, name)`): names are
  org-scoped identifiers and a same-org duplicate signals a data error,
  while different orgs may reuse names. **No separate index on
  `organization_id`**: the composite unique's btree already serves
  org-scoped lookups via its leftmost column — a second index would be pure
  duplication.
- **Enums by name, never ordinal** (`ACTIVE`/`INACTIVE` per-feature enums,
  `VARCHAR(20)`): reordering or adding states cannot corrupt stored values.
  App-side `ACTIVE` default; `NOT NULL` in DB.
- **Flat address columns** (`address_line1/2`, `city`, `state`,
  `postal_code`, `country`; only name/city/country required): enough for a
  real location, no address entity until a phase needs one. Required fields
  are constructor-enforced.
- **No services, no REST APIs, no DTOs this phase.** There is no domain
  operation beyond persistence; empty service shells are banned by AGENTS.md
  §22, and a CRUD API is explicitly out of scope. Repositories
  (`OrganizationRepository`, `BranchRepository`) stay bare — no derived
  queries without a caller. Validation happens at the repository level in
  integration tests; controllers/DTOs arrive with the first real consumer.

## Alternatives Considered

1. **Bidirectional OneToMany with `CascadeType.ALL`.** Rejected: the
   classic footgun — accidental org deletes cascading to branches,
   collection-merge surprises, and EAGER defaults. Nothing needs it.
2. **Globally unique organization names.** Rejected: invents a business rule;
   display names are not identifiers.
3. **Globally unique branch names.** Rejected: over-constrains tenants for
   no reason; scoping matches the domain.
4. **Minimal CRUD controllers "to validate the domain".** Rejected:
   repository-level integration tests validate persistence without opening
   update/delete HTTP surface the task forbids.

## Consequences

- `Branch.organization` (non-null, lazy, FK-enforced) is the anchor all
  future tenant checks build on; `Organization` stays free of
  branch knowledge.
- First real API consumer adds DTOs + controllers per AGENTS.md layering;
  entities are never exposed directly.
- Reserved numbers move: multi-tenancy detail → ADR-019, queue priority/aging
  fulfilled by ADR-014.
