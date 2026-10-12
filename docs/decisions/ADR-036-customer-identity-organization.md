# ADR-036 — Customer Identity and Organization Membership

Status: Superseded by ADR-040.

> **Historical record.** This ADR was Accepted in Phase 4I and governed
> implementation from Phase 4I through Phase 4Q. The Phase 4Q.1 architecture
> audit (2026-10-09) confirmed its constraints are the reason one customer
> identity cannot interact with multiple organizations, and the product
> decision changed: customers are now platform-level identities and
> operational organization access moves to an explicit membership model.
> ADR-036 stays in place unchanged as history — do not read its Decision
> section as current guidance; read [ADR-040](ADR-040-customer-identity-organization-membership.md).

## Context

Phase 4I needs public registration: customers must self-register and
businesses must create organizations. The existing model says every
`User` belongs to exactly one `Organization` (`organization_id NOT
NULL`), email is globally unique, and composite foreign keys pin
every appointment, queue entry, and staff assignment to the user's
organization. The principal, JWT, tenant context, and every
authorization policy carry exactly one organization. The phase brief
(§21) asks whether a customer could belong to multiple organizations.

## Decision

**Organization-scoped customers. No user-model change.**

- Every user — customer, staff, manager, admin — continues to belong
  to exactly one organization. No nullable membership, no membership
  table, no org-switching.
- Customer self-registration happens in the context of an explicitly
  chosen organization (public discovery lists active organizations;
  the register call carries that `organizationId`). The account is
  created there with the `CUSTOMER` role.
- One email address yields one account in one organization (global
  email uniqueness is unchanged). A customer using two clinics holds
  two accounts — a documented limitation, not a silent merge.
- Business registration creates organization + initial branch +
  owner `ADMIN` user atomically in one transaction.
- Invitations, password recovery, verification, and OAuth linking
  all resolve against the global email identity first, then enforce
  organization agreement — never the reverse.

## Alternatives considered

- **Nullable `organization_id` for customers:** rejected — every
  composite FK (`appointment`, `queue_entry`, `staff_assignment`)
  requires the row's organization to equal the user's; a NULL member
  could never book or queue without redesigning those constraints.
- **User↔organization membership with org-switching:** rejected —
  it would redesign the principal, JWT claims, tenant context, every
  policy, and every scoped query for a need the product does not yet
  have. Disproportionate and high-risk for this phase.
- **Single global customer pool with cross-org booking:** rejected —
  same FK breakage as nullable membership, plus tenant-context
  redesign.

## Consequences

- Zero migration on `app_user`; all composite FKs keep working
  unchanged; principal/JWT/tenant model untouched.
- Registration UX must always carry an explicit organization choice
  (customer) or create one (business) — enforced server-side, never
  defaulted silently.
- Password reset and email verification operate on the global email
  identity (one account per email), then scope effects to that
  account's organization.
- OAuth account linking matches on verified email → existing user;
  new OAuth users still choose (customer org) or create (business)
  an organization — social login never invents tenancy.
- Known limitation (documented in UI copy where relevant): one
  account per clinic per email address.
