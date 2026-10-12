# Organization Onboarding API (Phase 4R.4 — ADR-041)

Business owners apply to list their organization; platform
administrators approve or reject. Customer registration
(`POST /api/v1/auth/register`, no organization) is the separate,
organization-independent flow — this document covers only the
business path. Governing decisions: ADR-040 (identity/membership),
ADR-041 (onboarding lifecycle + platform administration), ADR-037
(account tokens).

Generic conventions — envelope, versioning, DTOs — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.

## Concepts (read this before integrating)

- **Email verification ≠ platform approval.** Verification proves the
  applicant owns an inbox (and gates sign-in); approval is a human
  platform review. Either one alone leaves the organization unusable.
- **Email verification ≠ business legitimacy.** Any email the owner
  controls works (Gmail/Outlook welcome — no corporate domain is
  required or checked). The platform does not verify legal business
  status; review is an operational decision, not a legal attestation.
- **Only `ACTIVE` organizations are public.** Pending, rejected, and
  deactivated organizations are absent from discovery and refuse
  bookings/queue joins at the domain boundary (direct service calls
  included) — not just hidden in UI.

## `POST /api/v1/auth/register/business` — submit a business application (public)

Creates the application atomically: `PENDING_APPROVAL` organization,
initial branch, owner account (`ADMIN` vocabulary), **`INACTIVE`**
owner membership, verification token + email.

Request (`application/json` — no status/role/authority fields exist;
unknown fields are ignored, never trusted):

```json
{
  "organizationName": "Harbor Clinic",
  "ownerName": "Mira Shah",
  "ownerEmail": "mira@example.com",
  "password": "…",
  "branchName": "Downtown",
  "city": "Bhopal",
  "country": "India",
  "timeZone": "Asia/Kolkata"
}
```

| Field | Rule |
|---|---|
| `organizationName` | 1–150 chars (names are not globally unique) |
| `ownerName` | 1–150 chars |
| `ownerEmail` | valid, ≤255; globally unique — one email = one account; any provider |
| `password` | ≤72 chars; policy: 8–72 chars with at least one letter and one digit |
| `branchName` / `city` / `country` | required (≤150/100/100) |
| `timeZone` | required IANA id (`Asia/Kolkata`, never abbreviations) |

Response — `200 OK` (same `RegistrationResponse` envelope as customer
registration; `organizationId` + `branchId` populated,
`verificationRequired: true`):

| Case | Status | Code |
|---|---|---|
| Submitted (verification pending) | `200` | — |
| Invalid input / weak password / bad timezone | `400` | `INVALID_REGISTRATION` / `VALIDATION_ERROR` / branch 400 |
| Owner email already registered | `409` | `USER_EMAIL_CONFLICT` (nothing is created) |
| Too many attempts | `429` | `RATE_LIMITED` |

Frontend: `/register/business` (public, linked from sign-in and
customer registration — never confused with it).

## `POST /api/v1/auth/verify-email` — unchanged, reused

The existing single-use verification (`INVALID_VERIFICATION` for
unknown/expired/reused tokens alike). Consuming the owner's token
**does not change** the organization or membership — the application
stays `PENDING_APPROVAL` until review decides.

## `GET /api/v1/organizations/applications/mine` — my application (authenticated)

The caller's own application, resolved server-side from their
membership row. Callers with no application get the scope-safe `404`
(`APPLICATION_NOT_FOUND`) — applications are never enumerable here.

```json
{
  "organizationId": "…",
  "organizationName": "Harbor Clinic",
  "branchId": "…",
  "status": "PENDING_APPROVAL",
  "emailVerified": true,
  "rejectionReason": null,
  "decidedAt": null
}
```

`rejectionReason` is applicant-facing feedback (present only for
`REJECTED`); there are no internal-only notes on this row by design.

Frontend: `/business/status` (authenticated) renders
verification-required → pending → approved → rejected distinctly.

## Platform review — `/api/v1/platform/organizations` (platform administrators only)

Every call re-authorizes through the platform-admin policy
(configuration + live account state). Customers, staff, managers,
organization admins, unauthenticated callers, unverified configured
accounts, and unconfigured platforms all get `401`/`403` — never data.

- `GET /api/v1/platform/organizations?status=PENDING_APPROVAL` —
  list (default pending, oldest first; `ACTIVE`/`REJECTED`/`INACTIVE`
  nameable; anything else `400 INVALID_STATUS`).
- `GET /api/v1/platform/organizations/{id}` — one application
  (unknown id `404 ORGANIZATION_NOT_FOUND`).
- `POST /api/v1/platform/organizations/{id}/approve` — approves an
  eligible, verified, pending application: organization **and**
  owner membership become `ACTIVE` together. Unverified owners and
  non-pending organizations get `409 INVALID_TRANSITION`.
- `POST /api/v1/platform/organizations/{id}/reject` —
  `{ "reason": "…" }` (1–500 chars, applicant-facing; blank/over-long
  is `400`). Non-pending organizations get `409 INVALID_TRANSITION`.

Item shape (`PlatformApplicationResponse`): organization + branch
names, `status`, `ownerEmail`, `emailVerified`, `rejectionReason`,
`decidedAt`, `decidedByUserId`, `createdAt`. No customer/staff data,
no credentials.

Frontend: `/platform/applications` (behind `PLATFORM_ADMIN`,
hidden from every other role's navigation).

## Platform-administrator bootstrap (operator procedure)

`app.platform.admin-emails` (`APP_PLATFORM_ADMIN_EMAILS`,
comma-separated, empty by default) names the accounts holding review
authority. Empty/missing fails **closed** — review denies everyone;
the first registered user is never crowned silently.

1. Register the admin address as a normal customer account
   (`POST /api/v1/auth/register` or `/register`).
2. Verify its email (Mailpit inbox locally — see
   `docs/development/README.md` "Account email"; real relay in
   production).
3. Set `APP_PLATFORM_ADMIN_EMAILS=<that address>` and restart.
4. Sign in normally — the session carries `PLATFORM_ADMIN`; the
   review dashboard works.

Being listed alone grants nothing: the account must exist, sign in
with its own credentials, and be verified. Removing an address (or
deactivating/unverifying the account) locks it out on the next call —
stale tokens fail closed.

## Status transitions (normative)

```text
PENDING_APPROVAL → ACTIVE    (approve: verified owner + platform decision, atomic)
PENDING_APPROVAL → REJECTED  (reject: platform decision + applicant-facing reason)
```

Everything else — approving `ACTIVE`, rejecting `REJECTED`, deciding
twice, deciding concurrently — answers `409 INVALID_TRANSITION` with
no state change. Decisions serialize on a row lock; concurrent
approvals yield exactly one `ACTIVE` organization and one `ACTIVE`
membership.
