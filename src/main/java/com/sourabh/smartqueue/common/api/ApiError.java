package com.sourabh.smartqueue.common.api;

import java.time.Instant;
import java.util.List;

/**
 * Stable REST error envelope (see ADR-022).
 *
 * <p>Every controller-thrown failure renders as this shape — clients parse
 * {@code code} (machine-readable, stable per failure kind), never free-form
 * messages. {@code fieldErrors} carries per-field detail for request-shape
 * validation failures and is empty otherwise. Filter-level rejections (missing
 * or invalid bearer token, handled before MVC dispatch) keep their minimal
 * {@code {"error","message"}} shape documented in {@code docs/api/auth.md}.
 *
 * <p>Never carries stack traces, SQL, entity internals, credentials, or
 * tokens. Date/time is ISO-8601 ({@code Instant}); ids elsewhere in the API
 * are UUID strings; enums are name strings, never ordinals.
 */
public record ApiError(
		Instant timestamp,
		int status,
		String code,
		String message,
		String path,
		List<FieldError> fieldErrors) {

	/** One rejected request field: name plus the constraint message. */
	public record FieldError(String field, String message) {
	}
}
