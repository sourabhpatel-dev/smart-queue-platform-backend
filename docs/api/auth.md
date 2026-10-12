# Auth API (Phase 3F + Phase 4I account lifecycle + Phase 4Q onboarding + Phase 4R.3 identity + Phase 4R.4 onboarding)

Authentication, public registration, email verification, password recovery,
employee invitations, social login, and public discovery. Governing decisions:
[ADR-021](../decisions/ADR-021-authentication-authorization.md) (login/security),
[ADR-036](../decisions/ADR-036-customer-identity-organization.md)
(*superseded* — organization-scoped customers), ADR-037 (account tokens &
session revocation), and
[ADR-040](../decisions/ADR-040-customer-identity-organization-membership.md)
(platform-level customers, active-membership operational authorization —
**implemented in Phase 4R.3** except where noted).

Generic conventions — envelope, versioning, DTOs, JSON shapes — live in
[`rest-foundation.md`](rest-foundation.md) and are not repeated here.

## Current identity model (implemented — ADR-040)

Customers are **platform-level identities**: they register with no
organization, hold no organization membership, and authenticate without any
organization context (the JWT carries no `org` claim for them). Their
organization-specific actions — booking, joining queues, reading their own
rows, notifications, observing queue displays — derive scope from the target
resource (branch/queue/appointment), never from a membership, and customer
reads are owner-scoped across every organization.

Operational access (STAFF/MANAGER/ADMIN) is grounded in an **ACTIVE
`organization_membership` row**: login resolves the organization from the
user's active memberships (the legacy home organization breaks ties), the
JWT carries that organization as the `org` claim, and the authorization
policy re-verifies the ACTIVE membership on every organization-scoped
operation. Inactive or missing memberships authorize nothing. Operational
users are provisioned through invitations (MANAGER/STAFF, organization +
role + branch bound server-side from the token) or the manager/admin user
directory (which creates and syncs membership rows alongside roles) —
never through self-service registration, which creates platform-level
CUSTOMER accounts only.

---

## `POST /api/v1/auth/login`

Public. Exchanges email + password for a short-lived bearer token.

Request (`application/json`):

```json
{ "email": "asha@example.com", "password": "…" }
```

Success — `200 OK`:

```json
{
  "tokenType": "Bearer",
  "accessToken": "<hs256-jwt>",
  "expiresAt": "2026-10-06T05:30:00Z",
  "userId": "…",
  "organizationId": "…",
  "roles": ["STAFF"]
}
```

`organizationId` is the resolved operational organization and is **`null`
for platform-level customers**. The token authenticates subsequent calls
via `Authorization: Bearer <token>` (REST) and `?token=<jwt>` on the
WebSocket handshake (`/ws`). Claims: `sub` (user id), `org` (organization
id — **optional since Phase 4R.3**: present for operational users, absent
for customers), `email`, `roles`, `iat`, `exp` (default 1h,
`security.jwt.expiration`).

Failures (shared `ApiError` envelope; generic in all credential cases — no
enumeration):

| Case | Status | Code |
|---|---|---|
| Unknown user, inactive account, missing credential, wrong password | `401` | `UNAUTHENTICATED` — identical body in all four cases |
| Correct credentials but a verification token is still pending | `403` | `EMAIL_NOT_VERIFIED` (checked only after the password verifies, so verification status is never probeable) |
| Malformed body (bad email, blank/missing password) | `400` | `VALIDATION_ERROR` with `fieldErrors` |

Rate limiting: repeated attempts for one identifier answer `429` (`RATE_LIMITED`).
Passwords are never logged, compared manually, or returned; an unknown user
still pays one BCrypt comparison (timing parity).

## `POST /api/v1/auth/register` — customer self-registration (public)

**Current contract: the customer is a platform-level identity.** The
account is created with no organization (`organization_id = NULL`) and the
`CUSTOMER` role; no membership is created. The customer discovers active
organizations later and selects one per action (booking/queue) — request
context, never membership.

Request:

```json
{
  "displayName": "Asha Patel",
  "email": "asha@example.com",
  "password": "…"
}
```

There is **no `organizationId`** (unknown extra fields are ignored, never
trusted — organization/role self-provisioning is impossible through this
endpoint).

| Field | Rule |
|---|---|
| `displayName` | 1–150 chars |
| `email` | valid, ≤255; globally unique — one email = one account |
| `password` | ≤72 chars; policy: 8–72 chars with at least one letter and one digit |

Response — `200 OK`:

```json
{
  "userId": "…",
  "organizationId": null,
  "branchId": null,
  "email": "asha@example.com",
  "verificationRequired": true
}
```

Errors: `409 USER_EMAIL_CONFLICT` (registration deliberately reports
"already registered"), `400 INVALID_REGISTRATION` (shape/name/email/password
policy, including `WEAK_PASSWORD`), `429 RATE_LIMITED`. The verification email is dispatched after
commit only — transport failures degrade to logs, never to partial accounts.

Email delivery (`app.email.*`, `APP_EMAIL_*`): the `log` provider (default)
records attempts and sends nothing; `smtp` delivers through the configured
relay (host required, auth only with a username, STARTTLS only when
enabled). Local development points `smtp` at the Mailpit relay from
`compose.yaml` — see `docs/development/README.md` ("Account email") for
the inbox walkthrough. The message carries the frontend verification link
(`{frontend-base-url}/verify-email?token=…`, built from configuration —
never from a request host), the enforced expiry (default 1 day), and
ignore-instructions; it carries no password. `verificationRequired: true`
means a token row committed (*pending*), not that an email arrived
(*delivered*) — failures are warning-logged (recipient + subject only)
and every email type has a resend path.

## `POST /api/v1/auth/register/business` — business application (public)

Submits an organization *application* (see ADR-041 and
[`onboarding.md`](onboarding.md)): organization + initial branch +
owner account commit atomically (an ownerless organization or an
organizationless owner must never exist), but nothing becomes usable
yet. This is an *organization application flow*, not a customer
membership flow.

Request:

```json
{
  "organizationName": "City Clinic",
  "ownerName": "Asha Patel",
  "ownerEmail": "asha@example.com",
  "password": "…",
  "branchName": "Main Street",
  "city": "Pune",
  "country": "India",
  "timeZone": "Asia/Kolkata"
}
```

| Field | Rule |
|---|---|
| `organizationName` | 1–150 chars; display names are deliberately **not** globally unique |
| `ownerName` | 1–150 chars |
| `ownerEmail` | valid, globally unique (`409` on conflict); **any provider** — Gmail/Outlook welcome, no corporate domain required or checked |
| `password` | same policy as customer registration |
| `branchName` / `city` / `country` | required; `1`–150/100/100 chars |
| `timeZone` | required IANA id (`Asia/Kolkata`); abbreviations rejected |

The request carries no status/role/authority fields (unknown JSON is
ignored, never trusted). The organization is created
`PENDING_APPROVAL` with the owner's `ADMIN` membership `INACTIVE`:
the applicant verifies their email and watches their status, but
administers nothing and appears nowhere public until platform review
approves (which activates both rows atomically). Email verification
proves inbox ownership only — it never activates an organization.

Response — `200 OK`: same `RegistrationResponse` shape with both
`organizationId` and `branchId` populated, `verificationRequired: true`.
Errors: `400 INVALID_REGISTRATION` / invalid timezone, `409`, `429`.

Owner status and platform review live in [`onboarding.md`](onboarding.md)
(`GET /api/v1/organizations/applications/mine`,
`/api/v1/platform/organizations…`).

## `POST /api/v1/auth/verify-email` (public)

Consumes the single-use verification token emailed at registration.

```json
{ "token": "…" }
```

`200 OK` (`{"message":"Email verified — you can now sign in."}`) or
`400 INVALID_VERIFICATION` for unknown, expired, reused, and already-consumed
tokens alike (one honest state — the token space is unguessable; no signal is
owed). `429 RATE_LIMITED`.

## `POST /api/v1/auth/resend-verification` (public)

```json
{ "email": "asha@example.com" }
```

Always answers `200` generically (no account enumeration): unknown emails,
verified accounts, and throttled repeats look identical. Only an account with
a live (unconsumed) token row receives a replacement; verified accounts
receive nothing. `429 RATE_LIMITED`.

## `POST /api/v1/auth/forgot-password` (public)

```json
{ "email": "asha@example.com" }
```

Always `200` generically (`INVALID_RESET`-safe: no existence signal).
`429 RATE_LIMITED`.

## `POST /api/v1/auth/reset-password` (public)

```json
{ "token": "…", "newPassword": "…" }
```

Consumes the single-use reset token and sets a new password; **all prior
sessions retire immediately** (password-change revocation, ADR-037).
Unknown, expired, and reused tokens share `400 INVALID_RESET`; weak
replacements `400 WEAK_PASSWORD`. `429 RATE_LIMITED`.

## `POST /api/v1/auth/change-password` (authenticated)

```json
{ "currentPassword": "…", "newPassword": "…" }
```

Proves the current secret first; **all other sessions** retire immediately.
`200`; `400 INVALID_PASSWORD` (wrong current) or `WEAK_PASSWORD`; `401`
anonymous. Rate limiting applies.

## OAuth (social login)

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/v1/auth/oauth/providers` | public | Names only (`GOOGLE`/`APPLE` as configured); empty list when unconfigured |
| `POST /api/v1/auth/oauth/{provider}/callback` | public | `{code, redirectUri, organizationId}` → standard `LoginResponse` |

The frontend never sees provider secrets. Callback exchanges the code
server-side, validates the ID token (issuer/audience/signature/expiry), then
links a known identity, links a verified-email match, or **provisions a
CUSTOMER in the supplied `organizationId`** — the current, ADR-036-shaped
behavior. Identity failures are generic (`401`); unknown/disabled providers
and missing organizations are `400`.

## Invitations (employee onboarding)

Management routes require MANAGER/ADMIN in the caller's own organization;
acceptance is public and token-bound.

| Endpoint | Notes |
|---|---|
| `POST /api/v1/invitations` | `{email, role, branchId?}` — ADMIN may invite MANAGER/STAFF; MANAGER may invite STAFF only. Any other role → `400 INVALID_INVITATION` (customers are never invited — they self-register). STAFF invitations require a branch in the organization; MANAGER invitations must omit it. Already-registered email → `409` |
| `GET /api/v1/invitations` | Own organization, newest first; paginated; sort allow-list `createdAt`/`email`/`role` |
| `POST /api/v1/invitations/{id}/revoke` | Idempotent when already revoked; accepted rows `400 INVALID_INVITATION`; foreign ids `404` |
| `POST /api/v1/invitations/{id}/resend` | Revokes live rows for the recipient, issues a fresh token + expiry |
| `POST /api/v1/invitations/accept` | Public. `{token, email, displayName, password}` — **organization, role, and branch come from the invitation, never the request** (transfer prevention: a token for another email is simply invalid). Creates user + credential + ACTIVE staff assignment atomically, consumes the invitation. No session is returned — sign in explicitly. `400 INVALID_INVITATION` / `WEAK_PASSWORD`, `409`, `429` |

## Public discovery

| Endpoint | Auth | Response |
|---|---|---|
| `GET /api/v1/discovery/organizations` | public | ACTIVE organizations only: `id` + `name` |
| `GET /api/v1/discovery/branches?organizationId=…` | public | ACTIVE branches of one **ACTIVE** organization: `id`, `name`, `city`, `country`, `timeZone`; unknown or non-ACTIVE org `404` |
| `GET /api/v1/discovery/services?branchId=…` | public | ACTIVE services of one branch of an **ACTIVE** organization: `id`, `name`, `description`, `durationMinutes`; unknown/inactive branch or non-ACTIVE organization `404` |

Field-minimal by design: no staff, customers, queue state, or internal
detail. Customers need no membership to discover an organization — this is
the entry point for the booking and queue flows (org → branch → service).

---

## Remaining ADR-040 target (not implemented)

ADR-040 (Accepted 2026-10-09, Phase 4R.1) supersedes ADR-036. Phase 4R.3
implements the identity half: platform-level customers (no `organizationId`
on registration), membership-grounded operational authorization, optional
JWT `org` claim, resource-derived customer scope. Phase 4R.4 (ADR-041)
implements organization onboarding: `PENDING_APPROVAL` applications,
verified-owner + platform-review activation, `PLATFORM_ADMIN` bootstrap
— see [`onboarding.md`](onboarding.md). **Still not implemented:**

- **Multi-organization staff context:** an operational user with several
  ACTIVE memberships currently resolves to one organization (the legacy
  home organization if it has an active membership, else the single
  membership, else none). Per-request organization selection for
  multi-membership staff is a later phase.
