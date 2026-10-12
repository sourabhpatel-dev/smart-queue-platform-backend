-- V17__organization_invitation.sql - Employee invitations (Phase 4I).
--
-- An invitation names an organization, an email, a role, and (for
-- staff) a branch — the invitee cannot change any of it at accept
-- time (see ADR-037 and the invitation decision notes).
--
-- Decisions recorded here:
-- - uq_invitation_token UNIQUE (token_hash): the digest is the lookup
--   key and the reuse backstop. No unique on (organization, email):
--   history repeats (re-invites, expired rows) and liveness is a
--   service-level predicate (accepted_at IS NULL AND revoked_at IS
--   NULL AND expires_at > now), with resend revoking prior live rows
--   for the same (organization, email).
-- - role VARCHAR(20) holds MANAGER or STAFF only (enforced in code;
--   ADMIN invitations are rejected — creating admins stays an
--   out-of-band operation, documented, not an oversight).
-- - branch_id NULLABLE: required for STAFF (the assignment needs it),
--   ignored for MANAGER (organization-wide by the existing role
--   rule). Both legs are composite-pinned like every other
--   cross-entity link in this schema.
-- - email stored normalized (trim + lowercase, the single User rule)
--   so acceptance matching cannot drift by case or whitespace.
-- - created_by_user_id NULLABLE FK -> app_user (id), no cascade:
--   audit trail for who invited, surviving nothing silently.
-- - accepted_at / revoked_at NULL means live-toward-acceptance; both
--   set semantics are one-way (accepted rows never reopen).
-- - idx_invitation_organization (organization_id, email): the
--   management listing ("who did we invite") and the resend lookup.
-- - idx_invitation_expiry (expires_at): the cleanup sweep's predicate.
-- - No change to V1–V16 (project convention: migrations are immutable
--   once applied).

CREATE TABLE organization_invitation (
    id                  UUID          NOT NULL,
    organization_id     UUID          NOT NULL,
    branch_id           UUID,
    email               VARCHAR(255)  NOT NULL,
    role                VARCHAR(20)   NOT NULL,
    token_hash          VARCHAR(64)   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    accepted_at         TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_by_user_id  UUID,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_organization_invitation PRIMARY KEY (id),
    CONSTRAINT fk_invitation_organization FOREIGN KEY (organization_id)
        REFERENCES organization (id),
    CONSTRAINT fk_invitation_branch_organization
        FOREIGN KEY (branch_id, organization_id)
        REFERENCES branch (id, organization_id),
    CONSTRAINT fk_invitation_created_by FOREIGN KEY (created_by_user_id)
        REFERENCES app_user (id),
    CONSTRAINT uq_invitation_token UNIQUE (token_hash)
);

CREATE INDEX idx_invitation_organization
    ON organization_invitation (organization_id, email);

CREATE INDEX idx_invitation_expiry
    ON organization_invitation (expires_at);
