# ADR-037 — Account Lifecycle Tokens and Session Revocation

Status: Accepted (Phase 4I).

## Context

Registration verification, password reset, and invitations all need
single-use secrets, and stateless JWTs need a revocation story when
passwords change. The constraints: no Redis authority for account
state, no new infrastructure, Clock-driven tests, generic responses
against enumeration, and zero fixture churn for the existing suite.

## Decision

1. **Opaque random tokens, hashed at rest.** 256-bit
   `SecureRandom`, URL-safe base64; only the SHA-256 hex digest is
   stored (unique). The raw value exists transiently at creation and
   travels exactly once — inside the email body. Never logged, never
   returned by APIs, never stored.
2. **One row per live token, consumed not deleted.**
   `account_verification_token`, `password_reset_token`,
   `organization_invitation` each carry `token_hash` (unique),
   `expires_at`, `used_at`/`accepted_at`. Verification/reset tables
   are unique per user (resend replaces); invitations are unique per
   token (resend revokes-then-issues). Used rows are kept as the
   reuse proof, purged by the bounded scheduled cleanup.
3. **Lifetimes:** verification 24h, reset 1h, invitation 7d
   (validated properties). Expired means expired — resend, don't
   extend.
4. **Verification gates login by token presence, not by a flag.**
   Login returns `403 EMAIL_NOT_VERIFIED` while an unconsumed,
   unexpired verification token exists for the account (expired
   tokens gate too — the message says to resend). No `User` model
   change, no migration backfill dilemma, zero existing-fixture
   churn: legacy and test users have no token rows. Invitation
   acceptance and password reset prove inbox access, so both consume
   live verification tokens as a side effect.
5. **Revocation via `password_changed_at`.** One nullable column on
   `user_credential`, stamped by the single `setPassword` funnel on
   every set. The authentication filter rejects tokens with `iat`
   before the stamp (truncated to seconds — JWT `iat` has second
   precision; same-second race stays valid, ≤1s exposure,
   documented). Costs one unique-key credential read per
   authenticated request — stated honestly. Credential-less
   (OAuth-only) users are unaffected. Logout stays client-side
   session clear; reset/change retires all prior tokens
   immediately.
6. **Password policy:** 8–72 chars (BCrypt truncates past 72 —
   enforced, not silently cut), at least one letter and one digit.
   BCrypt via the existing encoder; dummy-hash timing discipline
   unchanged.

## Alternatives considered

- **`email_verified_at` flag + login gate:** rejected — backfill
  cannot distinguish legacy-verified from legacy-unverified, and
  every existing test fixture would need the flag set.
- **Token denylist (`jti`) table:** rejected — needs a new claim,
  a write on every logout, and unbounded growth for the same effect
  as the single timestamp.
- **Shorter JWT expiry instead of revocation:** rejected — shrinks
  but never closes the window after a compromise.
- **Numeric OTPs:** rejected — 43-char unguessable tokens in links
  beat short codes that need attempt counters and lockout state.

## Consequences

- V14 (revocation column) + token/invitation tables; one funnel
  (`setPassword`) nobody can bypass; filter change covered by unit
  tests; full-suite green required before merge.
- Known gaps: ≤1s same-second revocation race; logout is
  client-side; OAuth-only accounts have no password revocation.
