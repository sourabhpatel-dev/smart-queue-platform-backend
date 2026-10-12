package com.sourabh.smartqueue.counter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Counter update payload (see ADR-024: PUT carries the complete mutable
 * representation — no PATCH variant).
 *
 * <p>The name is the only mutable field today; it stays required so
 * partial semantics can never hide under PUT. No ownership fields, no
 * {@code status} (activate/deactivate only), no capability list.
 */
public record UpdateCounterRequest(
		@NotBlank @Size(max = 100) String name) {
}
