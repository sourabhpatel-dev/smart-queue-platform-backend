# ADR-026 — Appointment Booking API

Status: Accepted (Phase 3K, 2026-10-06).

## Context

The appointment engine (2E: exclusions, calendar, DST, race-proof
booking) predates any HTTP. Phase 3K exposes it, and three questions
are genuinely new: who the customer is when roles differ, how much a
non-owner may learn from an id, and whether lifecycle commands stay
idempotent when the domain is not.

## Decision

1. **No delegated booking, in any role.** The customer is always the
   principal; the body carries no customer id. Managers and staff book
   for themselves like customers. Rationale: no existing flow models
   acting for another user, and inventing one would create an
   impersonation primitive beside authentication.
2. **Booking delegates, never duplicates.** The REST service checks
   only what HTTP owns (future start via the project Clock,
   path-branch/service agreement) and calls `AppointmentBookingService`
   for calendar, DST, snapshot, and overlap arbitration. Races stay
   exclusion-arbitrated (proven by the untouched 2E race test).
3. **Privacy-first reads.** Owners and operationally authorized
   staff/managers read; everyone else — including same-org customers —
   gets the scope-safe `404`. Appointment rows carry personal identity,
   so unlike branch reads (403 on same-org denial), existence itself is
   hidden. Listing is role-routed to match (customers pinned to self
   with explicit `403` on foreign ids, staff naming assigned branches,
   managers roaming with scope-checked facets).
4. **Lifecycle honesty over command convention.** Cancel is owner-or-
   branch-management (staff front-desk cancellation deferred);
   complete/no-show reuse the operational tier. Terminal rows refuse
   with `409 APPOINTMENT_INVALID_TRANSITION` — deliberately not
   idempotent, because the domain's one-way transitions win over the 3H
   command habit. No status-write endpoint, no confirm flow (the domain
   has none — not added here), no DELETE.
5. **One conjunctive list query** (nullable facets, overlap window,
   default chronological sort) with presence-flagged window binds:
   PostgreSQL cannot type a bare null `Instant` parameter, so the edges
   travel with boolean flags rather than `IS NULL` checks. The same phase
   also fixed a latent shared-infrastructure bug the appointment tests
   exposed: `ApiPageRequest` defaulted to an arbitrary `Map.of` entry
   (undefined iteration order), so endpoints without a `name` field sorted
   unpredictably — defaults are now explicit per endpoint (`startAt`
   here, `name`/`email`/`createdAt` elsewhere).
6. **No migration, no new dependencies, no concurrency changes.**

## Alternatives considered

- **Manager book-for-customer:** rejected — unmodeled impersonation
  primitive; documented limitation.
- **403 on hidden reads:** rejected — confirms existence of personal
  rows to guessers; 404 hides.
- **Idempotent cancel-always-200:** rejected — contradicts the guarded
  transitions; 409 tells the truth.
- **Generic status PUT:** rejected — would bypass transition guards.
- **Past-start tolerance:** rejected — backdated appointments are
  nonsense rows; the boundary requires future starts.
- **Separate branch-nested list:** rejected — one list route only, with
  facets; no duplicate styles.

## Consequences

- The privacy-first read pattern is available to future personal-data
  surfaces; the flag-bound nullable-parameter pattern to future range
  queries.
- 15 black-box HTTP tests; 2E booking + race suites untouched and green.
- Intentionally deferred: delegation, rescheduling, recurrence,
  waitlists, reminders, availability search, confirm flow, check-in
  integration, staff cancellation for others.
