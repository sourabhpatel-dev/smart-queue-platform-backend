# ADR-032 — In-App Notifications

Status: Accepted (Phase 4D, 2026-10-07).

## Context

Customers had no durable record of what happened to their bookings and
queue entries; staff/manager screens showed only current state. The
building blocks already existed: committed domain facts (ADR-016),
a STOMP broker with authenticated sessions (ADR-018/021), and REST
conventions (ADR-022). The questions were genuinely new: what
persists where, which facts deserve a row, who the recipient is, how
live delivery stays scoped, and what may fail without corrupting
business flows.

## Decision

1. **PostgreSQL persists, realtime echoes.** One `notification` row
   per recipient per source fact (V10). The center reads rows; the
   socket only prepends arrivals. A dead socket degrades to manual
   refresh with zero data loss.
2. **Six customer types, no broadcasts.** `APPOINTMENT_BOOKED` plus
   `QUEUE_JOINED/CALLED/COMPLETED/CANCELLED/NO_SHOW` — each source
   event already carries its recipient (`customerId`). Serving/session
   events add no new customer meaning; staff broadcasts would be spam
   (no event names a safe staff recipient); appointment
   cancel/complete/no-show flows publish no events, so they stay
   silent. Small on purpose.
3. **AFTER_COMMIT plus REQUIRES_NEW.** Listeners fire only on commit
   (rollback never notifies, proven). Persistence runs in its own
   transaction: `afterCompletion` callbacks execute while the business
   transaction is completing, and joining it flushes against a dead
   session — found by failing tests, fixed with `REQUIRES_NEW`, which
   is safe here because the business rows already committed.
4. **At-least-once handling, exactly-once rows.** `event_id` is unique;
   a repeated handling is a logged no-op (pre-check plus constraint
   backstop for races). No delivery guarantees are claimed beyond the
   persisted row.
5. **Failure isolation.** Listener exceptions are caught and logged;
   sender failures keep the row. Business flows never wait on, and can
   never be rolled back by, notification work.
6. **User destinations, session-named.** The handshake binds a token
   whose `getName()` is the user id (type/principal/authorities
   unchanged — queue-scope authorization untouched); the server sends
   with `convertAndSendToUser`, the client subscribes to the fixed
   `/user/queue/notifications`, and the channel interceptor allows
   exactly that destination. No id in any subscribable path.
7. **Recipient-scoped REST, no PUT.** List (newest-first pages,
   unread filter), item read, idempotent read command, bulk read-all,
   and a database-computed unread count. The principal is the
   recipient; foreign ids are scope-safe 404s.
8. **Small sender port.** `NotificationSender` with one in-app
   implementation; email/SMS/push explicitly deferred, and the
   interface must stay this small so adding one never touches
   business workflows.

## Alternatives considered

- **Redis persistence:** rejected — Redis is derived-state only
  (ADR-017); the center needs durability across restarts.
- **Per-user topics (`/topic/notifications/{userId}`):** rejected —
  puts the recipient id in a subscribable path; the `/user`
  mechanism makes the session the scope instead.
- **Notifying on every event (serving, sessions):** rejected — noise
  without user value; CALLED already covers the call.
- **Appointment lifecycle notifications:** deferred — those flows
  publish no events, and touching lifecycle code was out of scope.
- **Retention job:** deferred — rows persist; cleanup is a future
  operational concern, documented, not built.

## Consequences

- V10 migration; `notification/` feature module (entity, types,
  repository, service, sender, listener, controller, DTO,
  destinations); three minimal WebSocket touch-points (session name,
  `/queue` broker prefix, one allowed destination); 18 new backend
  tests; frontend center (bell/panel/page/live client, 20 new tests,
  150 total); no new dependencies, no brokers, no email/SMS.
- Known gaps: no staff notifications, no appointment
  cancel/complete/no-show notifications, no retention, no
  exactly-once delivery claims.
