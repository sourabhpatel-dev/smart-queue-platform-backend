-- V14__credential_password_changed_at.sql — Password-change session revocation (Phase 4I).
--
-- Stateless HS256 JWTs cannot be individually revoked, so password
-- changes must invalidate previously issued tokens another way. This
-- migration adds the single server-side fact the revocation check
-- needs (see ADR-037):
--
-- Decisions recorded here:
-- - password_changed_at TIMESTAMPTZ, NULLABLE, no default, no
--   backfill: NULL means "never changed since this column existed",
--   which is exactly true for every pre-existing row. No migration
--   can distinguish legacy states, and none needs to — the check
--   only rejects tokens older than a recorded change.
-- - No index: revocation reads one row by user_id through the
--   existing uq_credential_user unique constraint (equality on a
--   unique key needs no second index).
-- - No change to V1–V13 (project convention: migrations are immutable
--   once applied).

ALTER TABLE user_credential
    ADD COLUMN password_changed_at TIMESTAMPTZ;
