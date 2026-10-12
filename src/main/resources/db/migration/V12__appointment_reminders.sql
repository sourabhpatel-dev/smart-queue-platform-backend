-- V12__appointment_reminders.sql - Durable upcoming-appointment reminders (Phase 4F).
--
-- One row per (appointment, reminder type): the scheduler inserts this
-- row and the reminder notification in one transaction, and the unique
-- constraint arbitrates concurrent schedulers. See ADR-034.
--
-- Decisions recorded here:
-- - app-owned table `appointment_reminder` (creation-only: the row's
--   existence is the SENT state - no status column, no PENDING/FAILED
--   lifecycle until a failure mode requires one).
-- - fk_appointment_reminder_organization -> organization (id), no cascade
--   (project convention: explicit lifecycle, loud failures).
-- - fk_appointment_reminder_appointment -> appointment (id), no cascade:
--   appointment deletion is not a supported operation, so a dangling
--   reference fails loudly like the notification recipient FK in V10.
-- - uq_appointment_reminder UNIQUE (appointment_id, reminder_type): THE
--   concurrency mechanism. Two schedulers discovering the same
--   appointment race this constraint; exactly one insert wins and only
--   the winner creates the notification (same transaction). No
--   distributed locks, no broker, no Java synchronization.
-- - sent_at TIMESTAMPTZ NOT NULL: when the project Clock said the
--   reminder was processed (audit/testability, not scheduling - due
--   comparison always derives from appointment.start_at).
-- - No reminder_due_at column: the due moment is derived
--   (start_at - policy.before) at sweep time, never stored - a stored
--   wall-clock due time would go stale when the policy changes and
--   would invite DST arithmetic bugs.
-- - idx_appointment_status_start ON appointment (status, start_at): the
--   discovery predicate filters active statuses over a bounded start
--   window (now, now + lead]. The existing idx_appointment_branch_time
--   leads with branch_id and cannot serve this global sweep; the
--   service/customer exclusion indexes serve overlap arbitration, not
--   range scans.
-- - No change to V1-V11 (project convention: migrations are immutable
--   once applied).

CREATE TABLE appointment_reminder (
    id                  UUID          NOT NULL,
    organization_id     UUID          NOT NULL,
    appointment_id      UUID          NOT NULL,
    reminder_type       VARCHAR(20)   NOT NULL,
    sent_at             TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_appointment_reminder PRIMARY KEY (id),
    CONSTRAINT fk_appointment_reminder_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_appointment_reminder_appointment FOREIGN KEY (appointment_id)
        REFERENCES appointment (id),
    CONSTRAINT uq_appointment_reminder UNIQUE (appointment_id, reminder_type)
);

CREATE INDEX idx_appointment_status_start
    ON appointment (status, start_at);
