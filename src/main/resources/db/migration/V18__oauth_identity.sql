-- V18__oauth_identity.sql - Social login linkage (Phase 4I).
--
-- Links a verified external identity to exactly one local user. The
-- row is created only after the provider's ID token validates
-- (issuer, audience, signature, expiry) AND the email matches the
-- account under the documented linking policy (see the OAuth
-- decision notes). Login itself never trusts frontend-supplied
-- identity data.
--
-- Decisions recorded here:
-- - provider VARCHAR(20): 'GOOGLE' or 'APPLE' (validated in code;
--   the table carries no other provider semantics).
-- - subject VARCHAR(255): the provider's stable `sub`, opaque to us
--   (never parsed, never displayed).
-- - uq_oauth_provider_subject UNIQUE (provider, subject): one local
--   account per external identity — no silent second accounts, no
--   takeover by re-registration.
-- - uq_oauth_user_provider UNIQUE (user_id, provider): one linked
--   identity per provider per user.
-- - fk_oauth_user -> app_user (id), no cascade (project convention).
-- - No email column: the link is (provider, subject); email matching
--   happens once at link time from the verified token, then the row
--   stands on its own (provider email changes cannot drift it).
-- - No change to V1–V17 (project convention: migrations are immutable
--   once applied).

CREATE TABLE oauth_identity (
    id                  UUID          NOT NULL,
    user_id             UUID          NOT NULL,
    provider            VARCHAR(20)   NOT NULL,
    subject             VARCHAR(255)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_oauth_identity PRIMARY KEY (id),
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_oauth_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_oauth_user_provider UNIQUE (user_id, provider)
);
