# Notifications API (Phase 4D)

Persistent in-app notification center. See ADR-032 for the architecture
(persist-first, best-effort realtime echo, recipient scoping).

## Model

`NotificationResponse`:

```json
{
  "id": "…",
  "type": "QUEUE_CALLED",
  "title": "Your number was called",
  "message": "Please proceed to Counter 1 at Downtown.",
  "read": false,
  "readAt": null,
  "createdAt": "2026-10-07T00:00:00Z",
  "referenceType": "QUEUE_ENTRY",
  "referenceId": "…"
}
```

- `type`: `APPOINTMENT_BOOKED`, `APPOINTMENT_REMINDER`,
  `QUEUE_JOINED`, `QUEUE_CALLED`, `QUEUE_COMPLETED`, `QUEUE_CANCELLED`,
  `QUEUE_NO_SHOW`.
- `referenceType`: `APPOINTMENT` | `QUEUE_ENTRY` | `null`;
  `referenceId` is the referenced row id (navigation hint only).
- No organization id, no recipient identity, no entities, no
  credentials. The caller is always the recipient.

## Endpoints

All require `Authorization: Bearer <token>`.

```text
GET /api/v1/notifications?page=0&size=20&sort=createdAt,desc&unread=true
GET /api/v1/notifications/unread-count            → {"unreadCount": 3}
GET /api/v1/notifications/{id}
POST /api/v1/notifications/{id}/read              (idempotent)
POST /api/v1/notifications/read-all               → {"markedRead": 3}
```

- List: page 0-based, size 1–100, sort allow-list `createdAt,readAt`
  (default `createdAt,desc`, ties break by id). `unread=true` (unread
  only) / `false` (read only) / absent (all). Invalid values → `400`.
- Unknown-or-foreign ids → scope-safe `404 NOTIFICATION_NOT_FOUND`
  (same shape for both — ids cannot enumerate other tenants).
- `read-all` affects only the caller's rows; already-read rows are
  untouched and uncounted.

## Realtime

Same DTO over STOMP user destination `/user/queue/notifications`
(server sends with `convertAndSendToUser(userId, "/queue/notifications")`).
Subscribe after CONNECT; no snapshot-request cycle (the server pushes
after each committed fact). Malformed frames never occur server-side;
clients must ignore them defensively. Delivery is best-effort: the
persisted row is the notification, the socket is the echo.

## Triggers

| Source event | Type | Recipient |
|---|---|---|
| `AppointmentBooked` | `APPOINTMENT_BOOKED` | event customer |
| `QueueEntryJoined` | `QUEUE_JOINED` | event customer |
| `QueueEntryCalled` | `QUEUE_CALLED` | event customer |
| `QueueEntryCompleted` | `QUEUE_COMPLETED` | event customer |
| `QueueEntryCancelled` | `QUEUE_CANCELLED` | event customer |
| `QueueEntryNoShow` | `QUEUE_NO_SHOW` | event customer |
| upcoming-appointment sweep (no event) | `APPOINTMENT_REMINDER` | appointment customer |

Serving/session events, staff broadcasts, and appointment
lifecycle transitions produce nothing (documented in ADR-032).

## Retention (Phase 4E, ADR-033)

Rows do not live forever. Read notifications older than
`notifications.retention.read-after` (default P90D) and unread ones
older than `notifications.retention.unread-after` (default P365D) —
both anchored on notification creation time — are deleted by a
scheduled sweep (default every PT6H, disableable). Removed rows
simply disappear from lists and read as scope-safe 404s by id; there
is no "expired" state, no retention metadata in responses, and no
delete endpoint.

## Appointment reminders (Phase 4F, ADR-034)

`APPOINTMENT_REMINDER` rows are written by the reminder sweep, not by
a domain event. Title is always `Appointment reminder`; the message is
rendered server-side from authoritative appointment data
(`Your appointment for <service> at <branch> is on <local date/time>.`
in the branch timezone) and carries no internal ids.
`referenceType` is `APPOINTMENT` with `referenceId` the appointment
id, so customers deep-link to `/appointments/{id}`; only the
appointment customer ever receives one. Delivery semantics:
at-least-once ticks, at-most-once rows (database-arbitrated, no
exactly-once claim); realtime echo stays best-effort like every other
type; unreminded cancelled/completed/no-show appointments simply
never produce this row.
