-- V10__create_notifications.sql - Persistent in-app notifications (Phase 4D).
--
-- One row per recipient per source fact: the notification center reads
-- PostgreSQL, never Redis, and realtime delivery is a best-effort echo
-- after the row commits. See ADR-032.
--
-- Decisions recorded here:
-- - app-owned table `notification` (NOTIFICATION is not reserved in
--   PostgreSQL, but the app_ prefix convention is unnecessary here:
--   `app_user` exists only because USER is reserved).
-- - fk_notification_organization -> organization (id), no cascade
--   (project convention: explicit lifecycle, loud failures).
-- - fk_notification_recipient -> app_user (id), no cascade: a
--   notification outlives nothing silently; recipient deletion is not
--   a supported operation, so a dangling reference fails loudly.
-- - event_id UUID NOT NULL UNIQUE (uq_notification_event): the source
--   domain event's id. AFTER_COMMIT handling is at-least-once by nature;
--   the constraint makes re-handling a logged no-op instead of a
--   duplicate row (no distributed locks, no broker).
-- - read_at NULL means unread; marking read stamps it (idempotent).
-- - reference_type/reference_id carry a navigation hint only
--   (APPOINTMENT/QUEUE_ENTRY plus the row id) - never entities, never
--   payloads that could go stale.
-- - Indexes serve exactly the two read paths: the recipient timeline
--   (recipient + newest first) and the unread badge
--   (recipient + unread only, partial index).
-- - No retention/cleanup job: rows stay persisted; retention is a
--   documented future operational concern.

CREATE TABLE notification (
    id                  UUID          NOT NULL,
    organization_id     UUID          NOT NULL,
    recipient_user_id   UUID          NOT NULL,
    type                VARCHAR(40)   NOT NULL,
    title               VARCHAR(150)  NOT NULL,
    message             VARCHAR(1000) NOT NULL,
    reference_type      VARCHAR(20),
    reference_id        UUID,
    event_id            UUID          NOT NULL,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notification_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);

CREATE INDEX idx_notification_recipient_created
    ON notification (recipient_user_id, created_at DESC);

CREATE INDEX idx_notification_recipient_unread
    ON notification (recipient_user_id)
    WHERE read_at IS NULL;
