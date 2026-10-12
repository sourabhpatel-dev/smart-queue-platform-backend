-- V9__create_user_credential.sql — Password credentials (Phase 3F).
--
-- One BCrypt hash per user, deliberately separate from app_user: identity
-- (email, roles, status) and authentication secret have different
-- lifecycles, and the domain model stays free of security fields.
-- See ADR-021.
--
-- Decisions recorded here:
-- - uq_credential_user UNIQUE (user_id): at most one credential row per
--   user, enforced by the database on every path.
-- - fk_credential_user → app_user (id), no ON DELETE cascade (project
--   convention: no cascade — credential lifecycle is managed explicitly;
--   deleting a user with a credential fails loudly instead of silently
--   dropping the secret).
-- - password_hash VARCHAR(255): BCrypt hashes are 60 chars; 255 leaves
--   headroom for a future algorithm without a schema change.
-- - No plaintext column, no token columns, no login-history columns:
--   nothing here but the hash. UserStatus on app_user remains the source
--   of truth for whether the account may authenticate.
-- - No backfill: existing users have no credentials and therefore cannot
--   log in until one is provisioned (same generic failure as a bad
--   password — no existence signal, no fake passwords).

CREATE TABLE user_credential (
    id              UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    password_hash   VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_user_credential PRIMARY KEY (id),
    CONSTRAINT fk_credential_user FOREIGN KEY (user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_credential_user UNIQUE (user_id)
);
