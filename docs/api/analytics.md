# Analytics API (Phase 4G)

Operational reporting for managers and admins. See ADR-035 for the
architecture (read-only PostgreSQL aggregation, no second source of
truth).

## Endpoint

All require `Authorization: Bearer <token>` (MANAGER or ADMIN in
their own organization — staff and customers get `403 FORBIDDEN`).

```text
GET /api/v1/analytics/overview?from=2026-10-01T00:00:00&to=2026-10-08T00:00:00&timezone=Asia/Kolkata&branchId=…&serviceId=…
```

| Parameter | Required | Meaning |
|---|---|---|
| `from` | yes | Window start: local date-time (`2026-10-01T00:00:00`) in the effective timezone, **inclusive** |
| `to` | yes | Window end, same shape, **exclusive** |
| `timezone` | org-wide: yes; branch-scoped: no | IANA `ZoneId` (explicit value always wins; otherwise the branch zone) |
| `branchId` | no | Branch scope (scope-safe 404 when unknown/foreign) |
| `serviceId` | no | Service scope (scope-safe 404 when unknown/foreign; 422 `ANALYTICS_SCOPE_MISMATCH` when it is not in the requested branch) |

Rules: `from < to` (else 400 `INVALID_DATE_RANGE`); the window must
not exceed `analytics.max-range-days` (default 366 — overlong ranges
are rejected, never truncated); malformed date-times/timezones are
400 (`INVALID_REQUEST` / `INVALID_TIMEZONE`); no organization id is
accepted — it always comes from the principal.

## Date/time semantics

- One effective `[from, to)` instant window is resolved first
  (`LocalDateTime.atZone` — DST gaps shift forward, overlaps take
  the earlier offset) and shared by **every** aggregate: summary and
  breakdowns cannot disagree.
- Appointments anchor on `startAt`, queue entries on `joinedAt`,
  sessions on `startedAt`. Exactly-`from` rows are included;
  exactly-`to` rows are excluded — for all three domains.
- The response echoes the effective `from`/`to` (UTC instants) and
  `timezone`, plus the applied `branchId`/`serviceId`.

## Model

```json
{
  "from": "2026-09-30T18:30:00Z",
  "to": "2026-10-07T18:30:00Z",
  "timezone": "Asia/Kolkata",
  "branchId": null,
  "serviceId": null,
  "appointments": { "total": 5, "scheduled": 2, "confirmed": 0, "completed": 1, "cancelled": 1, "noShow": 1 },
  "queue": { "joined": 3, "waiting": 1, "called": 0, "serving": 0, "completed": 2, "cancelled": 0, "noShow": 0, "averageWaitMinutes": 30.0 },
  "sessions": { "total": 2, "completed": 2, "averageDurationMinutes": 22.5, "totalDurationMinutes": 45.0 },
  "serviceBreakdown": [
    { "serviceId": "…", "serviceName": "Consultation", "appointmentCount": 5, "completedAppointmentCount": 1, "queueEntryCount": 3, "completedSessionCount": 2, "averageServiceDurationMinutes": 22.5 }
  ],
  "branchBreakdown": [
    { "branchId": "…", "branchName": "Downtown", "appointmentCount": 5, "completedAppointmentCount": 1, "queueEntryCount": 3, "completedSessionCount": 2 }
  ]
}
```

- Status splits always sum to their totals (current status exactly
  as stored — no historical transitions are claimed; see ADR-035).
- `averageWaitMinutes` = mean `session.startedAt − entry.joinedAt`
  over COMPLETED entries joined in the window that have a session
  (measured history, never the live Phase 2I estimate).
- Session counts cover sessions started in the window
  (`total = completed + running`); durations cover ended sessions
  only.
- Breakdowns order by operational volume
  (`appointmentCount + queueEntryCount`, highest first) with the
  name as tie-break. A service/branch filter pins that breakdown to
  the selection.

## Zero-data behavior

Counts are `0`; averages with no observations are `null` (never
`0`, never `NaN`); breakdowns are empty arrays. A valid empty
report is still `200`.
