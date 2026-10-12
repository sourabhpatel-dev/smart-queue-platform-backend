# Backend scripts (development-only)

| File | Purpose |
|---|---|
| `demo-seed.sql` | Demo tenant seed (Harbor Clinic) for visual review. Re-runnable; deletes the demo org first. **Never applied via Flyway; never committed as a migration.** Run from `backend/`: `psql -h localhost -U smartqueue -d smartqueue -f scripts/demo-seed.sql` |
| `probe.sql` | Scratch query experiment (queue ordering probes against a `scratch_*` table). Not part of the application; kept as a reference for the ordering semantics used by the queue engine. |

Nothing here runs automatically. Nothing here touches Flyway migration
history (`src/main/resources/db/migration/`).
