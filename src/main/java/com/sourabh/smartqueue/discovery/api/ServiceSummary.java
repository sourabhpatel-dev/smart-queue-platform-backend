package com.sourabh.smartqueue.discovery.api;

import java.util.UUID;

/** An active service, as the public directory sees it. */
public record ServiceSummary(
		UUID id,
		String name,
		String description,
		int durationMinutes) {
}
