package com.sourabh.smartqueue.infrastructure.redis;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON codec for Redis values (see ADR-017).
 *
 * <p>Redis stores strings; structured projections travel as JSON through
 * this single component using Boot's {@code ObjectMapper} (ISO-8601 dates,
 * no Java-serialization coupling, inspectable with {@code redis-cli}).
 * Only explicit projection types go through here — never JPA entities or
 * proxies. Unchecked failures: malformed content is a programming error,
 * not a recoverable state.
 */
@Component
public class RedisJson {

	private final ObjectMapper objectMapper;

	public RedisJson(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new IllegalArgumentException("cannot serialize value for Redis", e);
		}
	}

	public <T> T fromJson(String json, Class<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (JacksonException e) {
			throw new IllegalArgumentException("cannot deserialize Redis value", e);
		}
	}
}
