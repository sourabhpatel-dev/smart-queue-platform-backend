package com.sourabh.smartqueue.common.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reusable paginated-request parsing for list endpoints (see ADR-023 —
 * first implemented for branch listing in Phase 3H).
 *
 * <p>Contract (fixed in {@code docs/api/rest-foundation.md}):
 * {@code page} is 0-based (default 0), {@code size} defaults to
 * {@value #DEFAULT_SIZE} and is capped at {@value #MAX_SIZE}, and
 * {@code sort} accepts one entry per parameter as {@code field[,asc|desc]}
 * where {@code field} must appear in the endpoint's explicit allow-list
 * (API names mapped to persistence properties — raw client strings never
 * reach the query). Every list appends {@code id ASC} as a final tiebreak
 * so equal sort keys order deterministically.
 *
 * <p>Faults throw {@link InvalidRequestException} ({@code 400} with codes
 * {@code INVALID_PAGE} / {@code INVALID_SIZE} / {@code INVALID_SORT}).
 */
public final class ApiPageRequest {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	private ApiPageRequest() {
	}

	/**
	 * Reads {@code sort} values raw from the servlet request (see
	 * ADR-023): Spring's {@code List<String>} binding splits
	 * {@code sort=name,asc} on the comma into two entries, corrupting the
	 * documented {@code field[,direction]} shape. Every list endpoint with
	 * sorting must use this — never a bound {@code List<String>}.
	 */
	public static List<String> rawSorts(HttpServletRequest request) {
		String[] raw = request.getParameterValues("sort");
		return raw == null ? List.of() : List.of(raw);
	}

	public static Pageable of(int page, int size, List<String> sorts,
			Map<String, String> allowedSorts, String defaultSortField) {
		return of(page, size, sorts, allowedSorts, defaultSortField,
				Sort.Direction.ASC);
	}

	public static Pageable of(int page, int size, List<String> sorts,
			Map<String, String> allowedSorts, String defaultSortField,
			Sort.Direction defaultDirection) {
		if (page < 0) {
			throw new InvalidRequestException("INVALID_PAGE",
					"Page index must be zero or greater");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new InvalidRequestException("INVALID_SIZE",
					"Page size must be between 1 and " + MAX_SIZE);
		}
		List<Sort.Order> orders = new ArrayList<>();
		if (sorts != null) {
			for (String sort : sorts) {
				orders.add(order(sort, allowedSorts));
			}
		}
		if (orders.isEmpty()) {
			String property = allowedSorts.get(defaultSortField);
			if (property == null) {
				throw new IllegalArgumentException(
						"unknown default sort field: " + defaultSortField);
			}
			orders.add(new Sort.Order(defaultDirection, property));
		}
		// Deterministic ties: equal sort keys fall back to id order.
		orders.add(Sort.Order.asc("id"));
		return PageRequest.of(page, size, Sort.by(orders));
	}

	private static Sort.Order order(String sort, Map<String, String> allowedSorts) {
		String[] parts = sort.split(",", -1);
		String property = allowedSorts.get(parts[0].trim());
		if (property == null) {
			throw new InvalidRequestException("INVALID_SORT",
					"Unsupported sort field: " + parts[0].trim()
							+ ". Supported: " + String.join(", ", allowedSorts.keySet()));
		}
		Sort.Direction direction = Sort.Direction.ASC;
		if (parts.length > 2) {
			throw new InvalidRequestException("INVALID_SORT",
					"Invalid sort expression: " + sort + ". Use field[,asc|desc].");
		}
		if (parts.length == 2) {
			try {
				direction = Sort.Direction.fromString(parts[1].trim());
			} catch (IllegalArgumentException e) {
				throw new InvalidRequestException("INVALID_SORT",
						"Invalid sort direction: " + parts[1].trim() + ". Use asc or desc.");
			}
		}
		return new Sort.Order(direction, property);
	}
}
