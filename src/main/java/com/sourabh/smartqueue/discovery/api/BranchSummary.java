package com.sourabh.smartqueue.discovery.api;

import java.util.UUID;

/** An active branch, as the public directory sees it. */
public record BranchSummary(
		UUID id,
		String name,
		String city,
		String country,
		String timeZone) {
}
