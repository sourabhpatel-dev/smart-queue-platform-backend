package com.sourabh.smartqueue.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis fixed-window rate limiter (see the rate-limit decision
 * notes).
 *
 * <p>Key {@code sq:rl:{scope}:{key}} counts attempts; the key
 * expires after the configured window, which bounds Redis memory
 * without a cleanup job. First increment in a window sets the TTL —
 * the classic fixed-window shape (a burst exactly on the boundary
 * can double-spend one window; accepted and documented — this is
 * abuse friction, not a billing meter).
 *
 * <p>Fail-OPEN on Redis failure (logged): when Redis is down,
 * attempts are allowed and authentication keeps working. Abuse
 * protection degrades; correctness never depends on Redis (see
 * AGENTS.md §9).
 */
@Service
public class RedisRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	private final StringRedisTemplate redis;
	private final RateLimitProperties policy;

	public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties policy) {
		this.redis = redis;
		this.policy = policy;
	}

	@Override
	public void check(String scope, String key) {
		if (!policy.isEnabled()) {
			return;
		}
		String redisKey = "sq:rl:" + scope + ":" + key;
		try {
			Long count = redis.opsForValue().increment(redisKey);
			if (count != null && count == 1L) {
				redis.expire(redisKey, policy.getWindow());
			}
			if (count != null && count > policy.maxAttempts(scope)) {
				throw new RateLimitExceededException(
						"too many attempts; please wait and try again");
			}
		} catch (RateLimitExceededException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("rate limiter unavailable (scope={}); allowing attempt", scope, e);
		}
	}
}
