# ADR-017: Redis Infrastructure Foundation (Derived State Only)

- Status: Accepted
- Date: 2026-10-06
- Deciders: Project lead (Phase 3B — Redis Foundation)
- Relates to: AGENTS.md §9, ADR-003, ADR-016, `infrastructure/redis/`

## Context

Live queue updates will soon need fast, ephemeral, derived state that
PostgreSQL shouldn't serve per-poll. That future needs Redis to already
exist as a bounded, tested capability — configured, named, serialized, and
health-checked — without moving a single byte of authority off PostgreSQL.

## Decision

- **Role: derived/ephemeral state downstream of committed facts**
  (PostgreSQL → events → Redis → future real-time reads). Redis holds
  nothing authoritative: no queue lifecycle, no appointments, no identity,
  no configuration. If Redis is lost, everything rebuilds from PostgreSQL.
  No repository caching (`@Cacheable` nowhere), no queue migration, no
  Redis locks — each explicitly deferred, not overlooked.
- **Stack**: `spring-boot-starter-data-redis` (Boot-managed Lettuce; single
  client, no Redisson), `redis:7.4-alpine` in Compose (no volume — disposable
  by design; no cluster/sentinel/stack), Testcontainers `GenericContainer`
  (no dedicated Redis module exists in the BOM) wired via Boot
  `@ServiceConnection` — verified working, zero manual wiring.
- **Config**: `REDIS_HOST`/`REDIS_PORT` with localhost/6379 defaults
  (Compose convention mirrored); no password locally by decision (nothing
  worth stealing in derived state; production auth/topology deferred). No
  secrets committed (`.env.example` placeholders only).
- **Serialization**: strings on the wire; JSON via Boot's Jackson 3
  `ObjectMapper` through one `RedisJson` codec (ISO dates, `redis-cli`
  inspectable). No Java serialization, no JPA entities in values — ever.
  (Noted: Boot 4.x is Jackson 3 `tools.jackson`, not `com.fasterxml` —
  verified against the actual classpath, not copied from 3.x-era snippets.)
- **Keys**: `sq:o:{org}:b:{branch}:{domain}:{id…}` built only by
  `RedisKeyConvention` (null scope rejected fail-fast). Tenant scope on
  every key; keys authorize nothing — app checks stay mandatory. No env
  segment (no shared Redis across environments to separate).
- **TTL philosophy**: every future write sets an explicit TTL (minutes
  scale); TTL is a freshness bound, never correctness — PostgreSQL
  re-derives. Proven deterministically via `GETEXPIRE`-style assertion,
  never sleeps.
- **Health**: Boot default aggregation kept deliberately — main
  `/actuator/health` goes DOWN with Redis (dev/CI visibility), while
  `/liveness` and `/readiness` stay UP (verified live with Redis stopped):
  Redis is neither process death nor a transactional-readiness gate.
  Revisit when real-time actually depends on it.
- **Failure semantics (verified, not asserted)**: app starts and serves
  with Redis down; no business path touches Redis yet, so queue/appointment
  operations are unaffected. Graceful by absence of coupling.
- **Boundary**: `infrastructure/redis/` holds exactly `RedisKeyConvention`
  + `RedisJson` (+ auto-configured `StringRedisTemplate`). No generic
  cache manager, no projection classes yet (the JSON probe record is
  test-scoped precisely to avoid implying a schema).

## Alternatives Considered

1. **Custom RedisTemplate<Object,Object> bean.** Rejected: Boot's JDK-
   serialization default is a footgun; `StringRedisTemplate` (auto-
   configured) plus an explicit JSON codec covers all current needs.
2. **Redisson / SETNX locks.** Rejected: no distributed-coordination
   requirement; PostgreSQL locking stays authoritative.
3. **Password-protected local Redis.** Rejected: ceremony without a threat;
   documented as a production concern.
4. **Persisted Redis volume.** Rejected: contradicts disposable-derived-
   state semantics; would imply durability promises we refuse to make.
5. **Repository caching now.** Rejected: Phase 3B is not "cache the app";
   caching without measured need is speculative complexity.
6. **Kafka/RabbitMQ instead/also.** Rejected: no messaging requirement;
   in-process events + future Redis cover the roadmap without a broker.

## Consequences

- Phase 3C attaches live queue updates onto this exact seam
  (AFTER_COMMIT → Redis writes → WebSocket reads) with conventions,
  health, and failure behavior already settled and tested.
- 7 new tests; 193/193 green; no migration (V1–V8 untouched).
- Follow-up numbers move: shared-DB multi-tenancy detail → ADR-019.
