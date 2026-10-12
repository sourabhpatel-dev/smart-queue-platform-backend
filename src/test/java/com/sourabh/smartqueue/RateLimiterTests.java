package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.auth.RateLimitExceededException;
import com.sourabh.smartqueue.auth.RateLimitProperties;
import com.sourabh.smartqueue.auth.RateLimiter;
import com.sourabh.smartqueue.auth.RedisRateLimiter;

/**
 * Rate-limiter behavior (see the rate-limit decision notes): budgets
 * enforced per scope/key against real Redis, expiry wired (proven by
 * TTL, not by sleeping), fail-open on Redis outage, and the off
 * switch. No Spring context needed — plain units plus one
 * container.
 */
@Testcontainers
class RateLimiterTests {

	@Container
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	private RateLimitProperties policy() {
		RateLimitProperties policy = new RateLimitProperties();
		policy.setWindow(Duration.ofMinutes(1));
		policy.setLoginMaxAttempts(3);
		return policy;
	}

	private RedisRateLimiter limiter(RateLimitProperties policy) {
		return new RedisRateLimiter(template(), policy);
	}

	private StringRedisTemplate template() {
		org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
				new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
						redis.getHost(), redis.getMappedPort(6379));
		StringRedisTemplate template = new StringRedisTemplate();
		template.setConnectionFactory(factory);
		template.afterPropertiesSet();
		factory.start();
		return template;
	}

	@Test
	void budgetAllowsThenRejects() {
		RedisRateLimiter limiter = limiter(policy());
		String key = "budget-" + System.nanoTime();

		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		assertThatThrownBy(() -> limiter.check(RateLimiter.SCOPE_LOGIN, key))
				.isInstanceOf(RateLimitExceededException.class);
	}

	@Test
	void keysAreIsolatedPerScopeAndIdentity() {
		RedisRateLimiter limiter = limiter(policy());
		String key = "iso-" + System.nanoTime();

		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		limiter.check(RateLimiter.SCOPE_LOGIN, key);
		// Other scopes and keys are unaffected by exhaustion here.
		limiter.check(RateLimiter.SCOPE_RECOVERY, key);
		limiter.check(RateLimiter.SCOPE_LOGIN, key + "-other");
	}

	@Test
	void windowExpiryIsWired() {
		StringRedisTemplate template = template();
		RedisRateLimiter limiter = new RedisRateLimiter(template, policy());
		String key = "ttl-" + System.nanoTime();
		limiter.check(RateLimiter.SCOPE_LOGIN, key);

		Long ttl = template.getExpire("sq:rl:login:" + key);
		assertThat(ttl).isNotNull();
		assertThat(ttl).isBetween(1L, 60L);
	}

	@Test
	void redisOutageFailsOpen() {
		StringRedisTemplate broken = Mockito.mock(StringRedisTemplate.class);
		Mockito.when(broken.opsForValue()).thenThrow(
				new RuntimeException("redis down"));
		RedisRateLimiter limiter = new RedisRateLimiter(broken, policy());

		// Protection degrades; authentication correctness does not.
		for (int i = 0; i < 10; i++) {
			limiter.check(RateLimiter.SCOPE_LOGIN, "outage");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void disabledLimiterAllowsEverything() {
		RateLimitProperties policy = policy();
		policy.setEnabled(false);
		StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
		ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
		Mockito.when(template.opsForValue()).thenReturn(ops);
		Mockito.when(ops.increment(Mockito.anyString())).thenReturn(1000L);
		RedisRateLimiter limiter = new RedisRateLimiter(template, policy);

		limiter.check(RateLimiter.SCOPE_LOGIN, "off");
		Mockito.verify(template, Mockito.never()).opsForValue();
	}
}
