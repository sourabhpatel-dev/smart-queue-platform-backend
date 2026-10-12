package com.sourabh.smartqueue.queue.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support for the queue real-time freshness
 * pass (see ADR-020).
 *
 * <p>No custom threads, no scheduler framework: the single
 * {@code @Scheduled} tick on {@link QueueRealtimeRefreshService} runs on
 * Boot's auto-configured task scheduler. Disabling
 * {@code queue.realtime.refresh.enabled} removes the refresh bean, so no
 * tick is ever registered.
 */
@Configuration
@EnableScheduling
public class QueueRealtimeSchedulingConfiguration {
}
