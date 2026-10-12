package com.sourabh.smartqueue.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Queue aging policy (see ADR-014) — project policy, not industry standard.
 *
 * <ul>
 *   <li>{@code enabled} — master switch. Off reproduces the Phase 2F strict
 *   order exactly (EMERGENCY &gt; PRIORITY &gt; NORMAL, FIFO inside).</li>
 *   <li>{@code intervalMinutes} — waiting time per aging step: a NORMAL
 *   entry earns one step per full 15 minutes waited (default matches typical
 *   service durations, so roughly one service-length of waiting counts).</li>
 *   <li>{@code maxPromotions} — cap on counted steps (default 3): a NORMAL
 *   entry's effective score tops out where it durably competes with fresh
 *   PRIORITY entries, while EMERGENCY always precedes everything by
 *   construction.</li>
 * </ul>
 *
 * <p>Plain static configuration: changes require a restart (no refresh
 * scope in this codebase). Invalid values fail fast at startup via the
 * constraints below — a zero interval would otherwise divide by zero in
 * the claim query.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "queue.aging")
public class QueueAgingProperties {

	private boolean enabled = true;

	@Min(1)
	private int intervalMinutes = 15;

	@Min(0)
	private int maxPromotions = 3;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getIntervalMinutes() {
		return intervalMinutes;
	}

	public void setIntervalMinutes(int intervalMinutes) {
		this.intervalMinutes = intervalMinutes;
	}

	public int getMaxPromotions() {
		return maxPromotions;
	}

	public void setMaxPromotions(int maxPromotions) {
		this.maxPromotions = maxPromotions;
	}
}
