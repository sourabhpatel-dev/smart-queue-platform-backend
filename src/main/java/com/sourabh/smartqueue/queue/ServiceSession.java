package com.sourabh.smartqueue.queue;

import java.time.Instant;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.counter.ServiceCounter;
import com.sourabh.smartqueue.service.Service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * The actual service interaction for one queue entry: who was served, where,
 * by which counter, from when to when (see ADR-013).
 *
 * <p>Deliberately in the {@code queue} package (not {@code service}): it is
 * created and completed by the queue lifecycle transitions, and the catalog
 * {@code service} package stays free of operational state.
 *
 * <p>One session per entry, enforced by {@code UNIQUE(queue_entry_id)}.
 * {@code endedAt} null means the session is running; a set value with
 * {@code endedAt > startedAt} (CHECK-enforced) means delivered. Duration is
 * always derived ({@code endedAt − startedAt}) — never a stored field.
 *
 * <p>Branch scoping: the row carries {@code branch_id} so the database
 * pins counter and service to the entry's branch via composite foreign
 * keys. Entry↔session <em>service</em> equality (same service object, not
 * just same branch) is application-enforced — exposing it to the database
 * would require denormalizing onto the closed {@code queue_entry} table —
 * and guarded in the constructor plus validated by the lifecycle service.
 *
 * <p>Unidirectional lazy associations, no cascade.
 */
@Entity
@Table(name = "service_session")
public class ServiceSession extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_session_branch"))
	private Branch branch;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "queue_entry_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_session_entry"))
	private QueueEntry queueEntry;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "service_counter_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_session_counter"))
	private ServiceCounter counter;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "service_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_session_service"))
	private Service service;

	@NotNull
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	protected ServiceSession() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if branch/counter/service do not share
	 * one branch, or the entry belongs to a different branch or service.
	 * The V8 composite foreign keys enforce the branch legs independently.
	 */
	public ServiceSession(Branch branch, QueueEntry queueEntry, ServiceCounter counter,
			Service service, Instant startedAt) {
		if (startedAt == null) {
			throw new IllegalArgumentException("session start must be set");
		}
		if (!counter.getBranch().getId().equals(branch.getId())
				|| !service.getBranch().getId().equals(branch.getId())
				|| !queueEntry.getBranch().getId().equals(branch.getId())
				|| !queueEntry.getQueue().getService().getId().equals(service.getId())) {
			throw new IllegalArgumentException(
					"session branch, entry, counter and service must belong to one branch"
							+ " (and the service must be the entry's service)");
		}
		this.branch = branch;
		this.queueEntry = queueEntry;
		this.counter = counter;
		this.service = service;
		this.startedAt = startedAt;
	}

	public Branch getBranch() {
		return branch;
	}

	public QueueEntry getQueueEntry() {
		return queueEntry;
	}

	public ServiceCounter getCounter() {
		return counter;
	}

	public Service getService() {
		return service;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public boolean isActive() {
		return endedAt == null;
	}

	/**
	 * @throws IllegalStateException if already ended, or the end does not
	 * follow the start. One-way: an ended session never reopens.
	 */
	public void end(Instant endedAt) {
		if (this.endedAt != null) {
			throw new IllegalStateException("service session already ended");
		}
		if (endedAt == null || !endedAt.isAfter(startedAt)) {
			throw new IllegalArgumentException("session end must be after start");
		}
		this.endedAt = endedAt;
	}
}
