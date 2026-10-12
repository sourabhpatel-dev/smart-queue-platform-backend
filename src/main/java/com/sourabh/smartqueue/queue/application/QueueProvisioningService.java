package com.sourabh.smartqueue.queue.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;

/**
 * Queue auto-provisioning (see ADR-038).
 *
 * <p>Owns the one-queue-per-branch-plus-service invariant at the
 * application layer: {@link #ensureQueueForService} guarantees exactly
 * one {@link Queue} row for the pair, creating the {@code ACTIVE} row
 * when absent and returning the existing row untouched (id, status,
 * and history preserved) when present.
 *
 * <p>Transaction discipline: no new boundary — the default
 * {@code REQUIRED} propagation joins the caller's transaction, so a
 * service and its queue commit together or roll back together. There
 * is deliberately no catch-and-continue around the insert: the pair
 * references a just-created service id, so a uniqueness conflict is
 * unreachable through the service-creation path (concurrent
 * same-name service creation is arbitrated earlier by the service
 * name unique), and any genuine persistence failure must roll the
 * whole creation back rather than leave a queueless service. The
 * {@code uq_queue_branch_service} unique constraint stays the final
 * backstop. PostgreSQL aborts the whole transaction on a constraint
 * violation, so catching one here could never usefully continue.
 *
 * <p>No status mirroring: the queue keeps its own status as the
 * walk-in kill-switch (see ADR-012). Branch/service inactivity is
 * enforced at join time, never by cascading here.
 */
@Service
public class QueueProvisioningService {

	private final QueueRepository queues;

	public QueueProvisioningService(QueueRepository queues) {
		this.queues = queues;
	}

	/**
	 * Returns the queue for the pair, creating the {@code ACTIVE} row
	 * when none exists. Existing rows are returned as-is — never
	 * rewritten, never reactivated, never duplicated.
	 */
	@Transactional
	public Queue ensureQueueForService(Organization organization, Branch branch,
			com.sourabh.smartqueue.service.Service service) {
		return queues.findByBranchIdAndServiceId(branch.getId(), service.getId())
				.orElseGet(() -> queues.saveAndFlush(
						new Queue(organization, branch, service)));
	}
}
