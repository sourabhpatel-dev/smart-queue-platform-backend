# ADR-034 — Appointment Reminders

Status: Accepted (Phase 4F, 2026-10-08).

## Context

Appointments could be booked, listed, and cancelled — but nothing told
a customer an appointment was coming up. The building blocks already
existed: stored appointment instants with branch time zones (ADR-011),
a project Clock (ADR-013), a scheduler convention (ADR-020), a
tenant-safe background pattern (ADR-019), and the persistent in-app
notification center with best-effort realtime echo (ADR-032) plus its
retention sweep (ADR-033). The genuinely new questions: when a
reminder is due across time zones and DST, what durable state proves
it was handled, how concurrent schedulers avoid double reminders, and
where the transaction boundaries sit.

## Decision

1. **In-app is the first (and only) channel.** The reminder is a
   persisted `APPOINTMENT_REMINDER` notification read through the
   unchanged center (`GET /api/v1/notifications`, badge, realtime
   echo). No email/SMS/push, no providers, no brokers, no new
   infrastructure — the center already reaches the customer, and a
   second channel would double the delivery semantics to document
   before the first one is proven.
2. **One reminder, 24 hours ahead, configurable.** Policy
   `appointment.reminders` (`enabled`, `before` default PT24H,
   `interval` default PT10M): ISO-8601 durations, `@AssertTrue`
   positivity, fail fast at startup, disableable (both beans vanish;
   booking/center/retention unaffected). Overridable as
   `APPOINTMENT_REMINDERS_*`. The 24-hour value lives only in
   configuration — never as a constant in scheduling logic.
3. **Eligibility on stored instants.** Due when
   `reminderDueAt = startAt − before ≤ now`, with the appointment
   still upcoming (`startAt > now`), still active (`SCHEDULED` or
   `CONFIRMED` — the same definition the booking exclusions use), and
   with no reminder row of type `UPCOMING` yet. Cancelled, completed,
   and no-show rows are excluded by status; the appointment lifecycle
   is reused, never duplicated.
4. **Instants schedule, zones render.** The sweep compares
   `TIMESTAMPTZ` instants only — never wall-clock arithmetic, never
   the server/JVM zone. The branch `ZoneId` is used solely to render
   the customer message (`EEE, d MMM yyyy, h:mm a (z)` in
   `Locale.ENGLISH`, e.g. `Thu, 8 Oct 2026, 4:30 AM (IST)`), so DST
   transitions cannot shift the due moment while the displayed time
   stays branch-local and honest (EST vs EDT). No due-time column is
   stored — a stored wall-clock due would go stale on policy change.
5. **Creation-only delivery record, database-arbitrated.**
   `appointment_reminder` rows are proof of processing: presence is
   the SENT state (no PENDING/SENT lifecycle — a larger model has no
   failure to describe yet). `uq_appointment_reminder
   (appointment_id, reminder_type)` admits exactly one winner per
   appointment; the loser classifies the outcome by re-reading
   committed state (row present → expected duplicate; absent → real
   failure). The application never relies on check-then-insert.
6. **One transaction per candidate.** Discovery pages a bounded batch
   (200, oldest start first) with no surrounding transaction; each
   candidate then commits its reminder row plus its notification row
   in one transaction (TransactionTemplate, Phase 4E shape). When
   persistence does not commit, nothing is recorded and the next tick
   retries. The existing `NotificationService#create` is deliberately
   *not* reused — its `REQUIRES_NEW` boundary (correct for
   AFTER_COMMIT listeners in Phase 4D) would split this atomic unit
   in two. Entity, repository, sender, DTO, and tenant-verification
   rules are reused; only the boundary differs, and the difference is
   why. Realtime echo stays best-effort inside the same rules as
   every other notification.
7. **Bounded discovery with a naturally bounded window.** The query
   selects active appointments with `startAt IN (now, now + before]`
   minus already-reminded rows, oldest first. The window is at most
   one lead duration wide, so long-past appointments can never cause
   work; the predicate is plain instant comparison, served by the new
   `idx_appointment_status_start (status, start_at)` (the existing
   branch-time index leads with `branch_id` and cannot serve a global
   sweep). Anything beyond the batch cap waits for the next tick —
   still before its appointment starts.
8. **Catch-up, not exact ticks.** A tick processes everything due
   since the last one (`reminderDueAt ≤ now`), so downtime, restarts,
   and disabled-then-enabled stretches deliver late reminders while
   appointments stay upcoming. Exact execution at the due timestamp
   is explicitly not promised.
9. **Honest races.** Eligibility is finalized inside each candidate
   transaction against the freshly loaded row: a cancellation that
   commits first is visible and skips the reminder. The reverse order
   (reminder commits, then cancellation lands) can still deliver a
   reminder for a then-cancelled appointment — inherent without
   distributed locking, documented, not hidden. No `synchronized`, no
   ShedLock, no Quartz.
10. **Tenant-safe without a user context.** The sweep carries no
    principal and invents none: organization and recipient come from
    the appointment row itself
    (`appointment.organization = customer.organization =
    notification.organization`, re-verified before persisting, never
    guessed). A mismatch skips rather than notifies wrongly (proven
    by test against a state the constructor and composite FKs already
    make unreachable through persisted paths).
11. **At-least-once ticks, at-most-once rows, no exactly-once claim.**
    Two instances may sweep concurrently; both read committed state
    and the constraint arbitrates every shared appointment. The
    notification `event_id` is the reminder row id, tying the two
    rows of one delivery together. WebSocket delivery remains
    best-effort — the persisted rows are the guarantee.
12. **Customer-only, appointment-linked, server-rendered copy.**
    Recipient is always the appointment customer (no staff
    reminders); `referenceType = APPOINTMENT`,
    `referenceId = appointmentId`, so the existing customer UI
    deep-links to `/appointments/{id}` with no frontend change beyond
    a label and glyph. Message wording is neutral ("is on <local
    time>") rather than hardcoding "tomorrow", so it stays truthful
    under catch-up and custom lead times. No internal ids in copy.
13. **No reminder REST, no settings UI.** Nothing manually triggers
    reminders; the appointment and notification APIs are unchanged.

## Alternatives considered

- **Email/SMS/push now:** rejected — new providers, credentials, and
  delivery semantics before the in-app path is proven; the port
  (`NotificationSender`) keeps that door open without touching
  business workflows.
- **Quartz/ShedLock/distributed locks:** rejected — the constraint
  already serializes the only shared decision (who claims an
  appointment); locks would add infrastructure to protect a race the
  database wins for free (same call Phases 2F/3D/4E made).
- **PENDING/SENT/FAILED state machine:** rejected — no failure mode
  in this design needs an intermediate state (uncommitted work simply
  retries next tick); states will arrive when a retry/backoff policy
  needs them, not before.
- **Reusing `NotificationService#create` (REQUIRES_NEW):** rejected —
  splitting claim and notification across two transactions opens a
  crash window that loses reminders permanently (claim commits,
  notification fails, retry sees the claim and stops). One atomic
  unit or honest duplicates; the design chose atomic.
- **Separate `reminder_due_at` column + index:** rejected — derived
  per tick from `startAt − before`, so policy changes apply
  immediately and no stored wall-clock can drift across DST.
- **Wall-clock "tomorrow" copy:** rejected — lies under catch-up
  (hours before start) and custom lead times; the full local date
  and time is always truthful.

## Consequences

- `appointment/reminder/` module (type, entity, repository,
  properties, service, thin scheduler), `APPOINTMENT_REMINDER`
  notification type, `findReminderCandidates` on the existing
  appointment repository, V12 migration (reminder table + status/start
  index), `application.yml` block; 30 new backend tests (7
  properties, 18 behavior incl. TZ/DST, 2 disabled, 1 concurrency, 2
  end-to-end); 1 new frontend test (151 total); no new dependencies.
- Known gaps: one reminder kind only, customer-only, no per-user
  reminder preferences, no external channels, no exactly-once
  delivery claims, no SLA reporting. The previously observed
  appointment concurrency deadlock was not encountered here (the
  sweep never takes booking locks; booking suites untouched).
- Verification note: no Docker daemon exists in this environment, so
  the committed Testcontainers suites could not execute here. Every
  behavior above was additionally proven pre-completion against a
  real PostgreSQL (embedded, V1–V12 applied, Hibernate validate
  green): 9/9 green, including the two-thread claim race yielding
  exactly one reminder and one notification. The scratch harness was
  deleted afterwards; `pom.xml` is unchanged by it.
