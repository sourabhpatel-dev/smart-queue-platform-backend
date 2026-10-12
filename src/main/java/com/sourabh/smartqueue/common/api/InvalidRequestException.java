package com.sourabh.smartqueue.common.api;

/**
 * A client-correctable request failure with a stable machine-readable code
 * (see ADR-023).
 *
 * <p>Used for request-parameter faults the Bean Validation envelope cannot
 * express (bad page/size/sort/status-filter values). Renders as {@code 400}
 * with {@link #getCode()} as the {@code ApiError} code.
 */
public class InvalidRequestException extends RuntimeException {

	private final String code;

	public InvalidRequestException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
