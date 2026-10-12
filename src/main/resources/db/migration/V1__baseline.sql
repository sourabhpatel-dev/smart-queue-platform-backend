-- V1__baseline.sql — migration-pipeline baseline (Phase 1).
--
-- Purpose: prove that Flyway discovers, applies, and records migrations
-- against PostgreSQL. This migration intentionally creates NO domain tables.
-- Domain tables arrive with Phase 2 (Core Domain & Database Model), each in
-- its own versioned migration.
--
-- Convention (see docs/database/README.md):
--   V<version>__<snake_case_description>.sql   (e.g. V2__create_organization.sql)
-- Migrations are immutable once applied to any shared database. Never edit
-- an applied migration; add a new version instead.

SELECT 1;
