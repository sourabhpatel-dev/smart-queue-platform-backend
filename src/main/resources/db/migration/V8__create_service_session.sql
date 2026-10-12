-- V8__create_service_session.sql — Actual service interactions (Phase 2G).
--
-- One session per queue entry: who was served, where, by which counter,
-- from when to when. Facts for the later waiting-time phase — no estimates
-- here. See ADR-013.
--
-- Decisions recorded here:
-- - uq_session_entry UNIQUE (queue_entry_id): at most one session per
--   entry, enforced by the database on every path (the concurrent-start
--   backstop behind row locking).
-- - branch_id carried on the session so composites pin counter and service
--   to the entry's branch: (service_counter_id, branch_id) and
--   (service_id, branch_id). Entry↔session SERVICE equality (same service,
--   not just same branch) stays application-enforced — exposing it would
--   require denormalizing onto the closed queue_entry table.
-- - chk_session_range CHECK (ended_at IS NULL OR ended_at > started_at):
--   running sessions (NULL end) coexist with validated finished ones.
-- - No (service_counter_id, ended_at) index: nothing queries "active
--   session for a counter" yet — completion reaches its session through
--   the locked entry. No speculative indexes.

CREATE TABLE service_session (
    id                  UUID          NOT NULL,
    branch_id           UUID          NOT NULL,
    queue_entry_id      UUID          NOT NULL,
    service_counter_id  UUID          NOT NULL,
    service_id          UUID          NOT NULL,
    started_at          TIMESTAMPTZ   NOT NULL,
    ended_at            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_service_session PRIMARY KEY (id),
    CONSTRAINT fk_session_branch FOREIGN KEY (branch_id)
        REFERENCES branch (id),
    CONSTRAINT fk_session_entry FOREIGN KEY (queue_entry_id)
        REFERENCES queue_entry (id),
    CONSTRAINT fk_session_counter_branch
        FOREIGN KEY (service_counter_id, branch_id)
        REFERENCES service_counter (id, branch_id),
    CONSTRAINT fk_session_service_branch
        FOREIGN KEY (service_id, branch_id)
        REFERENCES service (id, branch_id),
    CONSTRAINT uq_session_entry UNIQUE (queue_entry_id),
    CONSTRAINT chk_session_range CHECK (ended_at IS NULL OR ended_at > started_at)
);
