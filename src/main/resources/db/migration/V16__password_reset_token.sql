-- V16__password_reset_token.sql - Password reset tokens (Phase 4I).
--
-- Same single-use shape as verification (see ADR-037): one live row
-- per user, digest-unique, expiry-enforced, kept-after-use as the
-- reuse proof, purged by the lifecycle cleanup sweep.
--
-- Decisions recorded here:
-- - uq_reset_user UNIQUE (user_id): a new request replaces the live
--   row, so at most one reset link works at a time (no oldest-wins
--   ambiguity, no token enumeration across requests).
-- - uq_reset_token UNIQUE (token_hash): lookup key + reuse backstop.
-- - expires_at NOT NULL (1h policy, enforced in code); used_at NULL
--   means live.
-- - fk_reset_user -> app_user (id), no cascade (project convention).
-- - idx_reset_expiry (expires_at): the cleanup sweep's predicate.
-- - No change to V1–V15 (project convention: migrations are immutable
--   once applied).

CREATE TABLE password_reset_token (
    id                  UUID          NOT NULL,
    user_id             UUID          NOT NULL,
    token_hash          VARCHAR(64)   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),
    CONSTRAINT fk_reset_user FOREIGN KEY (user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_reset_user UNIQUE (user_id),
    CONSTRAINT uq_reset_token UNIQUE (token_hash)
);

CREATE INDEX idx_reset_expiry
    ON password_reset_token (expires_at);
