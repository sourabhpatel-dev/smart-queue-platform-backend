-- V15__account_verification_token.sql - Email verification tokens (Phase 4I).
--
-- One live verification row per user: registration creates it, the
-- verify endpoint consumes it, resend replaces it (see ADR-037).
--
-- Decisions recorded here:
-- - uq_verification_user UNIQUE (user_id): at most one row per user,
--   so resend is upsert-by-replacement and "pending verification"
--   (the login gate) is simply row existence with used_at IS NULL.
-- - uq_verification_token UNIQUE (token_hash): the digest is the
--   lookup key and the reuse backstop in one constraint.
-- - expires_at NOT NULL (24h policy, enforced in code); used_at NULL
--   means live. Used rows are KEPT as the reuse proof (tiny table:
--   roughly one row per registered user ever) and purged by the
--   lifecycle cleanup sweep.
-- - fk_verification_user -> app_user (id), no cascade (project
--   convention: explicit lifecycle, loud failures).
-- - idx_verification_expiry (expires_at): the cleanup sweep's
--   predicate.
-- - No change to V1–V14 (project convention: migrations are immutable
--   once applied).

CREATE TABLE account_verification_token (
    id                  UUID          NOT NULL,
    user_id             UUID          NOT NULL,
    token_hash          VARCHAR(64)   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_account_verification_token PRIMARY KEY (id),
    CONSTRAINT fk_verification_user FOREIGN KEY (user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_verification_user UNIQUE (user_id),
    CONSTRAINT uq_verification_token UNIQUE (token_hash)
);

CREATE INDEX idx_verification_expiry
    ON account_verification_token (expires_at);
