package com.sourabh.smartqueue.common.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable paginated response envelope (see ADR-023).
 *
 * <p>{@code page} echoes the 0-based request index; an out-of-range page is
 * a successful empty {@code content}, never a 404. Future list endpoints
 * reuse this shape unchanged.
 */
public record ApiPage<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public static <T> ApiPage<T> from(Page<T> page) {
		return new ApiPage<>(
				List.copyOf(page.getContent()),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
