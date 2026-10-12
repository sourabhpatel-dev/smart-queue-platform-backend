# ADR-041 — Organization Onboarding & Platform Administration

Status: Accepted (Phase 4R.4).

> **Implementation status (2026-10-09):** implemented in Phase 4R.4 —
> `V21__organization_onboarding.sql`, `OrganizationStatus`
> (`PENDING_APPROVAL`/`REJECTED`), `Organization` review-audit columns +
> guarded transitions, `registerBusiness` creating `PENDING_APPROVAL` +
> `INACTIVE` owner membership, `PlatformAdminProperties`
> (`app.platform.admin-emails`) + `PlatformAdminPolicy`,
> `OrganizationApplicationService` (owner status),
> `OrganizationReviewService` + platform review API, discovery/booking
> eligibility hardening, business onboarding + review UI. ADR-040 remains
> the identity/membership decision of record; this ADR decides only what
> ADR-040 explicitly deferred (organization approval, `PLATFORM_ADMIN`).

## Context

ADR-040 recorded the onboarding target (business registration creates
`PENDING_APPROVAL`; owner email verification proves inbox ownership only;
platform review decides `ACTIVE`) and named a future `PLATFORM_ADMIN`
concept — but decided neither the lifecycle mechanics nor the
provisioning mechanism. The implementation gap at Phase 4R.4 start:

- `OrganizationStatus` was `ACTIVE`/`INACTIVE` only; `registerBusiness`
  created an instantly usable `ACTIVE` organization with an `ACTIVE`
  owner membership — no approval, no gate.
- No platform-level authority existed (`TenantAccessPolicy` documents
  "there is no `PLATFORM_ADMIN`"); organization `ADMIN` is org-scoped.
- No platform-admin account existed, so the first administrator needs a
  secure bootstrap path that cannot be mistaken for self-registration.

## Decision

### Organization lifecycle

```text
submit application → PENDING_APPROVAL ─┬─► ACTIVE   (approved)
                                       └─► REJECTED  (rejected, owner-visible reason)
```

- `PENDING_APPROVAL` and `REJECTED` extend the existing
  `OrganizationStatus` (string-persisted; no new status table/column).
- Allowed transitions are explicit and exhaustive:
  `PENDING_APPROVAL → ACTIVE | REJECTED`, enforced in
  `Organization.approve()/reject()`; anything else throws and the
  service layer answers `409 INVALID_TRANSITION` (repeat and concurrent
  decisions fail safely — never silently, never by forking state).
- Review audit lives on the row: nullable `rejection_reason`
  (owner-visible by design — there is **no** internal-notes column),
  `decided_at`, `decided_by_user_id` (plain UUID + FK, no tenant
  association). V21 adds these columns plus `idx_organization_status`;
  legacy rows keep `ACTIVE`/`INACTIVE` with `NULL` review columns and
  stay discoverable/bookable.
- Only `ACTIVE` organizations are discoverable/bookable. The existing
  `CustomerScope` gate already enforced this for customers; Phase 4R.4
  extends the same rule to the public directory (`branches`/`services`
  now 404 for non-`ACTIVE` organizations), the domain backstops
  (`AppointmentBookingService`/`QueueJoinService` refuse non-`ACTIVE`
  organizations), and operational scope resolution.

### Application submission

`POST /api/v1/auth/register/business` (unchanged DTO — it already
exposes no status/role fields, and unknown JSON is ignored) now creates:

- organization `PENDING_APPROVAL`,
- the initial branch (status as before; unreachable while the
  organization is not `ACTIVE`),
- the owner account (`ADMIN` role vocabulary, legacy organization
  anchor populated for post-approval login resolution),
- the owner's `ADMIN` membership **`INACTIVE`**,
- the standard verification token + after-commit email.

Verification consumes the token only — it never touches organization
or membership state. Approval requires the verified owner **and** the
platform decision, applied atomically (org `ACTIVE` + membership
`ACTIVE` in one transaction, row-locked). Rejection leaves the
membership `INACTIVE`. Duplicate owner emails still fail `409`
(no accidental activation, no orphan rows).

### Platform administration

- `RoleType.PLATFORM_ADMIN` is platform-level vocabulary: no
  membership representation (like `CUSTOMER`), no tenant scope, review
  authority only.
- Provisioning is the `app.platform.admin-emails` configuration
  (`APP_PLATFORM_ADMIN_EMAILS`, empty by default). A listed address
  grants nothing by itself: the account must exist, authenticate with
  its own credentials, and pass the normal email-verification gate
  before login attaches the role — and `PlatformAdminPolicy`
  re-checks configuration + live account state (exists, `ACTIVE`,
  verified) on **every** review call, so stale tokens, deactivated
  accounts, and config removals fail closed (`403`).
- Unconfigured platforms fail closed: review endpoints deny everyone.
  The first registered user is never crowned silently.
- Registration, business applications, invitations, and organization
  management can never grant the role (no code path maps client input
  to it).

First-administrator setup (operator procedure):

1. Register the admin address as a normal customer account
   (`POST /api/v1/auth/register`, or the `/register` page).
2. Verify its email (Mailpit locally, real relay in production).
3. Set `APP_PLATFORM_ADMIN_EMAILS=<that address>`, restart the backend.
4. Sign in normally — the session carries `PLATFORM_ADMIN` and the
   `/platform/applications` dashboard works.

## Alternatives considered

- **Organization `ADMIN` doubles as reviewer:** rejected — it is
  organization-scoped by design; any org admin could then decide other
  organizations' applications.
- **Database flag / manual SQL grant for the first admin:** rejected —
  an in-application configuration value is reviewable, restart-scoped,
  and needs no ad-hoc production writes; SQL grants also lack the
  per-call liveness re-check.
- **Separate `platform_admin` table:** rejected — a second identity
  store duplicates the account lifecycle (credentials, verification,
  revocation) for one narrow grant; the configuration names an account
  that already lives that lifecycle.
- **`INACTIVE` organization instead of `PENDING_APPROVAL`:** rejected —
  `INACTIVE` means "deactivated formerly-active" throughout the
  codebase and docs; conflating applications with deactivations would
  corrupt listing/audit semantics.
- **Membership created only at approval (no `INACTIVE` row):** rejected —
  the `INACTIVE` row is the server-side proof of who applied (it
  powers the owner-status endpoint without a new applicant column),
  preserves history on rejection, and denies access by the same
  `ACTIVE`-only rule as everything else.

## Consequences

- **Positive:** applications can never become usable without human
  review; email verification and approval are independently visible
  (UI states, owner endpoint); platform authority is explicit,
  minimal, and fail-closed; booking/discovery eligibility is enforced
  at the domain boundary, not just hidden in UI.
- **Costs / risks (accepted):** the bootstrap account is a normal
  credential — its compromise is platform compromise (mitigated by
  normal password hygiene + the operator owning the address; no MFA
  exists yet — recorded limitation); operators who misconfigure the
  email get a closed platform (safe, but silent until someone tries
  review — documented); multi-membership staff context selection is
  still a later phase (owner-status picks one membership).
- **Preserved:** customer registration stays organization-independent;
  existing `ACTIVE` organizations, memberships, appointments, queues,
  and staff assignments are untouched (V21 verified additive on
  PostgreSQL 16); the email infrastructure is reused unchanged
  (single-use tokens, expiry, generic errors, no-token-in-logs).
- **Not done:** OAuth-provisioned platform admins (password login only
  — fail-closed, documented); internal review notes (deliberately no
  column); appeal/re-application flows (a rejected owner may submit a
  new application with a different email — documented behavior, not a
  workflow).
