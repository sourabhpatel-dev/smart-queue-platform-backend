# ADR-040 — Customer Identity and Organization Membership Model

Status: Accepted (Phase 4R.1). **Supersedes ADR-036.**

> **Implementation status (2026-10-09):** Phase 4R.2 landed the
> *persistence foundation* — `V20__organization_membership.sql`
> (`organization_membership` table, operational-user backfill, customer
> legs of `appointment`/`queue_entry` un-pinned to plain customer FKs,
> `app_user.organization_id` nullable), the `membership/` package
> (entity + role/status enums + minimal repository), and the optional
> `User.organization` mapping. Phase 4R.3 implements the *identity and
> authorization* half: org-less customer registration (frontend +
> backend), null-org principals with an optional JWT `org` claim,
> membership-grounded operational authorization (login resolution +
> policy re-verification + invitation/user-directory provisioning),
> resource-derived customer scope with owner-scoped reads plus a bounded
> tenant-context bridge, and discovery-driven org/branch selection in
> the booking/queue UI. Remaining for later phases: organization
> approval (`PENDING_APPROVAL`/`PLATFORM_ADMIN`), multi-membership staff
> context selection, and OAuth provisioning decoupled from organization
> selection. This ADR remains the decision of record for the whole
> target.

## Context

Phase 4I chose organization-scoped customers (ADR-036): `app_user.organization_id`
is `NOT NULL` (V4), every user — customer, staff, manager, admin — belongs to
exactly one organization, and every appointment, queue entry, notification, and
staff assignment pins the user to that organization with composite foreign keys
`(customer_id, organization_id) → app_user(id, organization_id)` (V6, V7).
Registration is organization-scoped: `POST /api/v1/auth/register` requires a
`@NotNull organizationId` chosen from the public ACTIVE directory; the JWT
carries a required `org` claim; `TenantContextFilter` installs
`TenantContext(principal.organizationId(), null)` on every authenticated
request; and `TenantAccessPolicy.requireOrganizationAccess` enforces
`principal.organizationId == requested organizationId` everywhere.
`QueueJoinService.join` explicitly rejects a customer whose organization
differs from the queue's.

The Phase 4Q.1 architecture audit (completed 2026-10-09, read-only) confirmed
this is an architectural constraint, not a UI bug: **one customer identity
cannot interact with more than one organization, and cannot do so without
schema, JWT, tenant-context, authorization, and repository changes.** The
product requirement is the opposite: one SmartQueue account that discovers
active organizations and books / joins queues at any of them, without becoming
a member. The audit also confirmed the invitation boundary already matches the
desired product behavior (admins invite MANAGER/STAFF only — customers
self-register) and that business registration currently creates an ACTIVE,
immediately discoverable organization with no approval mechanism, so email
ownership is the only verification that exists.

This ADR records the approved target. **It changes no code, schema, endpoint,
JWT, or authorization behavior; implementation follows in later Phase 4R
stages.**

## Decision

**Customers become platform-level SmartQueue identities. Organization-scoped
operational access moves to an explicit membership model. The customer role
never implies organization membership.**

### Customer identity (target)

A `CUSTOMER` is a platform-level identity: one account per person, globally
unique email, self-registered, email-verified, **owned by no organization**. A
customer:

- self-registers (no organization selector);
- verifies their email;
- has no organization membership row — ever, by design;
- discovers ACTIVE organizations through the public directory;
- books appointments at multiple organizations;
- joins queues at multiple organizations;
- owns their appointments and queue entries across organizations.

Selecting an organization for a booking or queue action is **request
context**, never membership: viewing an organization, selecting a branch,
booking, or joining creates no membership row. This is what makes

```text
One customer account
    ├── Appointment  → Organization A
    ├── Queue entry  → Organization A
    ├── Appointment  → Organization B
    └── Appointment  → Organization C
```

correct by construction instead of an exception carried through the codebase.

### Operational membership (target)

`OrganizationMembership` is the authorization fact for organization-scoped
operational access:

```text
OrganizationMembership
- id
- user
- organization
- role        // STAFF | MANAGER | ADMIN
- status      // ACTIVE | INACTIVE
```

- Roles STAFF, MANAGER, ADMIN are granted **per organization** through
  membership. No membership row → no operational access to that organization:
  a staff member of Organization A gains nothing at Organization B, and a
  customer holds no operational privileges anywhere.
- `Organization ADMIN` remains organization-scoped: it manages its own
  organization, branches, services, and MANAGER/STAFF invitations. It never
  becomes platform-wide.
- The branch-level `StaffAssignment` model is unchanged in concept
  (assignment at a branch within the member's organization).
- The membership table is **not created yet** (Phase 4R.2 owns the schema).

### Tenant model (target)

Two distinct scopes replace today's single principal-derived context:

- **Operational tenant context** (STAFF/MANAGER/ADMIN): the organization is
  resolved from membership. `TenantContext` remains exactly what it is today —
  an operational authorization context with fail-closed semantics.
- **Customer resource context** (CUSTOMER): customers receive **no global
  tenant context**. Every customer action resolves the owning organization
  from the target resource:

```text
branchId / queueId / appointmentId
        ↓
resolve owning organization
        ↓
validate resource is ACTIVE / accessible
        ↓
perform owner-scoped operation
```

A customer-supplied organization ID is never an authority for access; the
backend derives organization from the resource and validates ownership/state.
Customer reads are **owner-scoped** (`customer = principal`), which is what
replaces today's same-organization equality.

### Principal / JWT direction (target, not implemented)

The future principal conceptually carries:

```text
userId
email
roles                       // [CUSTOMER] for platform customers
memberships[{organizationId, roles[]}]
activeOrganizationId        // resolved operational scope, null for customers
```

For customers: `roles = [CUSTOMER]`, `organization = null`. For operational
users: organization context is resolved from membership. The JWT `org` claim
becomes optional instead of required. No implementation detail beyond this
contract is prescribed here.

### Registration contracts (target)

Customer registration drops the organization entirely:

```http
POST /api/v1/auth/register
{ "displayName": "…", "email": "…", "password": "…" }
```

There is **no `organizationId`**. Business registration stays a separate,
already-existing route — an organization *application*, not a customer
membership:

```http
POST /api/v1/auth/register/business
```

Neither endpoint changes in this phase; the current contracts (including the
existing `organizationId` on customer registration) remain in force until the
implementation phases land.

### Organization onboarding (target)

```text
Register organization
        ↓
PENDING_APPROVAL
        ↓
owner email verification (proves inbox ownership, gates owner login)
        ↓
platform review
        ↓
ACTIVE (only ACTIVE organizations are discoverable/bookable)
```

**Email ownership ≠ organization legitimacy.** Email-domain verification is
explicitly rejected as the legitimacy mechanism: local businesses legitimately
use Gmail, Outlook, Yahoo, and other shared providers. The realistic
portfolio-grade model is owner-email verification plus human platform review
before ACTIVE.

### Platform administration (target, not implemented)

A `PLATFORM_ADMIN` concept — distinct from organization `ADMIN` — may review
pending organizations, approve or reject them, and view onboarding status.
Organization `ADMIN` manages only its own organization. `PLATFORM_ADMIN` does
not exist yet and must be the one narrowly-scoped principal kind that is not
organization-bound; deny everything else by default.

### Discovery (unchanged contract)

`GET /api/v1/discovery/organizations` remains the public, unauthenticated,
ACTIVE-only, field-minimal directory. Customers need no membership to
discover an organization, book at it, or join its queue. No
marketplace/search functionality is introduced.

## Security invariants (recorded, binding on implementation)

1. **Customer isolation.** A customer reads their own appointments and queue
   entries across organizations. A customer cannot read another customer's
   data — owner checks are independent of organization.
2. **Organization isolation.** A staff/manager/admin accesses only
   organizations for which they hold membership.
3. **No forged customer scope.** A customer cannot gain access by supplying
   another organization ID: the backend derives organization from the target
   resource and validates state/ownership.
4. **No privilege crossover.** The CUSTOMER role never grants
   staff/manager/admin privileges; operational roles require membership.
5. **Invitation security.** Organization, role, and branch of an invitation
   are determined server-side from the token; the frontend cannot choose them.

## Alternatives considered

- **Keep organization-scoped customers (status quo, ADR-036):** rejected —
  the product requirement is explicitly one account across many
  organizations; the constraint is in the schema, not the UI.
- **Nullable `organization_id` for customers only:** rejected — a null
  organization makes the tenant filters and the global role grants ambiguous
  (`user_role` keeps granting STAFF/MANAGER/ADMIN platform-wide while the org
  anchor is null), and every staff path would need to defend against an
  org-less operational principal. Membership makes "no access" the default
  instead of a special case.
- **Membership rows for customers too (org-scoped customers with
  indirection):** rejected — customers would still be members; it reproduces
  the current model with an extra table and complicates appointment
  ownership (per-membership owner resolution) for no product gain.
- **Email-domain verification as organization legitimacy:** rejected —
  local businesses legitimately use shared email providers; domain checks
  would lock out exactly the customers the product targets. Owner-email
  verification plus platform review is the honest mechanism.

## Consequences

- **Positive:** one customer account works at any number of ACTIVE
  organizations; customer authorization pivots from same-organization
  equality to resource ownership + ACTIVE validation; operational access
  gains a single, explicit authorization fact (membership); organization
  legitimacy gains a real onboarding state instead of instant ACTIVE;
  interview-quality model (the membership table explains itself).
- **Costs / risks (accepted):** a real migration — membership table with
  backfill from existing (user, organization, role) rows; composite FKs
  `(customer_id, organization_id)` on `appointment`/`queue_entry` replaced by
  plain customer FKs; `app_user.organization_id` becomes nullable
  (operational users retain it as their primary organization until
  `staff_assignment` re-points at membership); JWT/principal, tenant-context
  installation, owner-scoped customer reads, notifications, OAuth
  provisioning, and STOMP subscription authorization all follow in later 4R
  stages, each with its own tests.
- **Preserved (unchanged by this decision):** all concurrency guarantees
  (exclusion constraints, SKIP LOCKED claims, token allocation), staff /
  manager / admin authorization behavior, invitation boundaries, global email
  uniqueness, PostgreSQL authority, and the discovery contract.
- **Not implemented in this phase:** no schema change, no migration, no
  endpoint change, no JWT change, no frontend change. Everything above is the
  approved target only.
