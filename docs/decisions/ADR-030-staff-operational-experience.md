# ADR-030 — Staff Operational Experience

Status: Accepted (Phase 4B, 2026-10-07).

## Context

Phase 4A delivered customer workflows; staff routes were placeholders.
Phase 4B turns the existing operational engine (atomic call-next,
guarded lifecycle, session reads, assignment-scoped policy) into a staff
workspace without touching the backend. Five questions are genuinely
new: how staff discover their branches, where start-serving lives, how
occupancy renders, who sees Cancel, and how realtime fits operations.

## Decision

1. **Branch scope from self-assignments, no invented authority.**
   `GET /users/{ownId}/staff-assignments?status=ACTIVE` yields branch
   ids, resolved through `GET /branches/{id}`; managers/admins list the
   org instead. Unassigned staff get an honest empty state. Selection
   persists per browser with fallback to first.
2. **Counter workspace owns claiming and starting.** Call-next stays
   counter-centric (`POST /counters/{id}/call-next`, empty body —
   frontend never picks from a list). Start-serving lives only here,
   where the counter is known; the queue screen shows CALLED rows with a
   pointer instead of a broken button. Complete/no-show run wherever the
   entry is shown; every command refetches session + entry.
3. **404 is occupancy.** No frontend busy flag: current-session 200 means
   serving, `SERVICE_SESSION_NOT_FOUND` means free. Durations render from
   the backend-derived value only.
4. **Cancel visibility follows the matrix.** No-show/complete/start for
   operational scope; Cancel rendered for MANAGER/ADMIN only (staff
   cancelling others' rows 403s). Appointment cancel offered solely for
   the staffer's own live rows. Stale-row 409s are normal ops copy, with
   automatic state refresh — never silent, never retried blindly.
5. **Realtime reused as signal.** The shared 4A STOMP client subscribes to
   the relevant queue topic; snapshots trigger REST re-reads plus
   anonymous counts. Same degradation contract (banner + manual Refresh;
   30 s polling only on the customer page, manual refresh on ops
   screens to avoid surprising a working counter).
6. **Double-submit guards are UX only.** Pending commands disable their
   buttons with busy labels; races remain the backend's SKIP
   LOCKED/guarded transitions to arbitrate.

## Alternatives considered

- **Queue-centric claim picker:** rejected — misrepresents the atomic
  counter-spanning claim and risks acting on a row another counter
  legally took.
- **Manager "all branches" session view:** omitted — per-branch browsing
  covers oversight with one consistent UI; the unfiltered list adds
  nothing operational.
- **Roster/customer directory in ops:** out of scope — entries carry only
  the backend-authorized truncated customer ref; no names or emails exist
  to show.

## Consequences

- Five operational routes (dashboard, queue, counter, appointments,
  sessions) work for STAFF and MANAGER/ADMIN against existing endpoints;
  CUSTOMER stays denied; zero backend changes.
- 37 new Vitest cases (113 total frontend) pin contracts, branch
  discovery, lifecycle commands, occupancy, double-submit guards,
  authorization, and degradation.
- Known limitation: managers browse per-branch (no combined view);
  start-serving requires the counter workspace context.
