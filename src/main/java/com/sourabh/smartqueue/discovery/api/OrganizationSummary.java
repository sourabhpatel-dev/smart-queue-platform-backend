package com.sourabh.smartqueue.discovery.api;

import java.util.UUID;

/** An active organization, as the public directory sees it. */
public record OrganizationSummary(UUID id, String name) {
}
