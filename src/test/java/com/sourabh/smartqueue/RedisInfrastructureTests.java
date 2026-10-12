package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.infrastructure.redis.RedisJson;
import com.sourabh.smartqueue.infrastructure.redis.RedisKeyConvention;

/**
 * Phase 3B Redis infrastructure tests against a real Redis (see ADR-017):
 * connectivity, string round-trip, JSON projection round-trip, TTL
 * application (without sleeping), tenant-aware key convention, and test
 * isolation. Own containers per test class.
 *
 * <p>{@code QueueStateProbe} is deliberately test-scoped: it proves the
 * JSON path with a realistic projection shape without implying a
 * production Redis schema (none exists yet).
 */
@SpringBootTest
@Testcontainers
class RedisInfrastructureTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	RedisJson redisJson;

	/** Representative projection shape — test-only, not a production schema. */
	record QueueStateProbe(UUID queueId, UUID branchId, int waitingCount, Instant updatedAt) {
	}

	@Test
	void connectsToRedis() {
		assertThat(redisTemplate.getConnectionFactory()).isNotNull();
		String pong = redisTemplate.execute(
				(org.springframework.data.redis.core.RedisCallback<String>) connection -> connection
						.ping());
		assertThat(pong).isEqualTo("PONG");
	}

	@Test
	void writesAndReadsStrings() {
		String key = "3b:probe:string:" + UUID.randomUUID();

		redisTemplate.opsForValue().set(key, "hello");
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("hello");
	}

	@Test
	void jsonProjectionRoundTrips() {
		String key = "3b:probe:json:" + UUID.randomUUID();
		QueueStateProbe probe = new QueueStateProbe(
				UUID.randomUUID(), UUID.randomUUID(), 7, Instant.parse("2026-10-06T02:00:00Z"));

		redisTemplate.opsForValue().set(key, redisJson.toJson(probe));
		String stored = redisTemplate.opsForValue().get(key);

		assertThat(stored).contains("\"waitingCount\":7");
		assertThat(redisJson.fromJson(stored, QueueStateProbe.class)).isEqualTo(probe);
	}

	@Test
	void ttlIsAppliedWithoutWaitingForExpiry() {
		String key = "3b:probe:ttl:" + UUID.randomUUID();

		redisTemplate.opsForValue().set(key, "ephemeral", Duration.ofSeconds(30));

		// Deterministic: prove the expiry was set, not that it elapsed.
		Long ttl = redisTemplate.getExpire(key);
		assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(30);
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ephemeral");
	}

	@Test
	void missingKeyHasNoValueOrTtl() {
		String key = "3b:probe:missing:" + UUID.randomUUID();

		assertThat(redisTemplate.opsForValue().get(key)).isNull();
		assertThat(redisTemplate.getExpire(key)).isEqualTo(-2L);
	}

	@Test
	void keyConventionIsTenantScopedAndDeterministic() {
		UUID org = UUID.randomUUID();
		UUID branch = UUID.randomUUID();
		UUID queue = UUID.randomUUID();
		UUID entry = UUID.randomUUID();

		assertThat(RedisKeyConvention.queueState(org, branch, queue))
				.isEqualTo("sq:o:" + org + ":b:" + branch + ":queue:" + queue + ":state");
		assertThat(RedisKeyConvention.queueEntry(org, branch, queue, entry))
				.startsWith("sq:o:" + org + ":b:" + branch + ":queue:" + queue + ":");
		assertThat(RedisKeyConvention.queueWait(org, branch, queue))
				.isEqualTo("sq:o:" + org + ":b:" + branch + ":queue:" + queue + ":state:wait");

		// Same branch/service/queue ids under another organization differ.
		assertThat(RedisKeyConvention.queueState(UUID.randomUUID(), branch, queue))
				.isNotEqualTo(RedisKeyConvention.queueState(org, branch, queue));
	}

	@Test
	void keyConventionRejectsNullScope() {
		UUID id = UUID.randomUUID();

		assertThatThrownBy(() -> RedisKeyConvention.queueState(null, id, id))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> RedisKeyConvention.queueState(id, null, id))
				.isInstanceOf(NullPointerException.class);
	}
}
