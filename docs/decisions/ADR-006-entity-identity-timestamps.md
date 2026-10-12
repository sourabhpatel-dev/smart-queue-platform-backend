# ADR-006: Entity Identity and Timestamp Conventions (UUID + Auditable Base)

- Status: Accepted
- Date: 2026-10-05
- Deciders: Project lead (Phase 2A — Organization & Branch)
- Relates to: AGENTS.md §§2/14, `common/BaseAuditableEntity`, V2 migration

## Context

The first entities need identity and timestamp rules every future entity
will inherit. Getting this wrong means predictable public IDs, timezone
ambiguity, and timestamp logic drifting between entities.

## Decision

- **Identifiers: application-assigned `UUID`** (`java.util.UUID` ↔
  PostgreSQL `uuid`), generated in the entity constructor via
  `UUID.randomUUID()`. Non-predictable in future public APIs, merge-safe if
  the architecture ever distributes, and available before persist with no
  extra round-trip or database-specific function (no `pgcrypto` needed).
- **Timestamps: `Instant` ↔ `TIMESTAMPTZ`**, managed by `@PrePersist`
  (sets both) / `@PreUpdate` (bumps `updatedAt`) in a single small
  `@MappedSuperclass BaseAuditableEntity` (`common` package — the exact
  "auditing base class" AGENTS.md §2 reserves `common` for). `createdAt` is
  `updatable = false`. No full auditing framework (no actors/versions yet).
- **No optimistic-locking `@Version` yet**: no concurrent state transition
  exists in Phase 2A; adding the column is a trivial future migration when
  the first one does. No speculative columns.
- **Equality stays identity-based** (no equals/hashCode): no hash-based
  entity collections exist, and id-based equality breaks on lazy proxies.

## Alternatives Considered

1. **Sequential `BIGINT GENERATED ALWAYS AS IDENTITY`.** Rejected:
   predictable IDs leak business volume in URLs/APIs and complicate any
   future distributed or merge scenario; saves nothing at this scale.
2. **`LocalDateTime` timestamps.** Rejected: no zone info — ambiguous
   across servers/clients; `Instant` is unambiguous UTC by construction.
3. **Duplicating id/timestamp fields per entity (no superclass).** Rejected:
   the lifecycle callbacks would be copy-pasted and drift; one tested
   superclass is the convention carrier. This is the "clear value" the
   task demands before abstracting — not abstraction for its own sake.
4. **Full auditing (Envers / created-by / revision tables).** Rejected:
   no actor model exists yet; premature framework for two timestamps.

## Consequences

- Every future entity extends `BaseAuditableEntity` and gets UUID identity
  + reliable timestamps for free; `docs/database/README.md` records the
  convention.
- Tests assert: ids assigned pre-persist, `createdAt == updatedAt` on
  create, `updatedAt` advances while `createdAt` is frozen on update.
