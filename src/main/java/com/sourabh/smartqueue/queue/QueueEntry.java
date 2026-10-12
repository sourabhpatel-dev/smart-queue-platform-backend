package com.sourabh.smartqueue.queue;

import java.time.Instant;
import java.time.LocalDate;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One customer waiting for service — a walk-in unless a later integration
 * phase links appointments (see ADR-012).
 *
 * <p>Ordering is strict priority ({@code EMERGENCY > PRIORITY > NORMAL}),
 * FIFO by {@code joinedAt} inside each level, UUID tie-break — deterministic
 * and explicit, with NO aging yet (starvation under strict priority is a
 * documented trade-off owned by the later queue-algorithm phase).
 *
 * <p>Lifecycle: {@code WAITING → CALLED → SERVING → COMPLETED}, with
 * {@code WAITING|CALLED → CANCELLED|NO_SHOW} exits. Terminal states free the
 * customer's active-entry slot (partial unique index). Only guarded
 * transitions exist — no requeue, no framework.
 *
 * <p>Unidirectional lazy associations, no cascade. Cross-organization
 * customers are rejected by the constructor guard and the V7 composite
 * foreign keys alike.
 */
@Entity
@Table(name = "queue_entry")
public class QueueEntry extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_entry_organization"))
	private Organization organization;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "queue_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_entry_queue"))
	private Queue queue;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "customer_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_entry_customer"))
	private User customer;

	@NotNull
	@Min(1)
	@Column(name = "token_number", nullable = false)
	private Integer tokenNumber;

	@NotNull
	@Column(name = "business_date", nullable = false)
	private LocalDate businessDate;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "priority", nullable = false, length = 20)
	private QueuePriority priority = QueuePriority.NORMAL;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private QueueEntryStatus status = QueueEntryStatus.WAITING;

	@NotNull
	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt = Instant.now();

	@Column(name = "called_at")
	private Instant calledAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected QueueEntry() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if the queue does not belong to the
	 * given organization, or — for <em>operational</em> customers only — if
	 * the customer and the queue belong to different organizations. A
	 * customer (CUSTOMER-only role) joins any ACTIVE queue: neither a
	 * platform (null) nor a legacy organization value pins them to one
	 * organization (ADR-040). The service layer validated the queue/branch
	 * scope before this constructor runs.
	 */
	public QueueEntry(Organization organization, Queue queue, User customer,
			Integer tokenNumber, LocalDate businessDate, QueuePriority priority) {
		if (!queue.getOrganization().getId().equals(organization.getId())
				|| customerIsOperational(customer) && (customer.getOrganization() == null
						|| !customer.getOrganization().getId().equals(organization.getId()))) {
			throw new IllegalArgumentException(
					"queue entry, queue and customer must belong to the same organization");
		}
		this.organization = organization;
		this.queue = queue;
		this.customer = customer;
		this.tokenNumber = tokenNumber;
		this.businessDate = businessDate;
		this.priority = priority;
	}

	private static boolean customerIsOperational(User customer) {
		return customer.getRoles().stream()
				.anyMatch(role -> role != RoleType.CUSTOMER);
	}

	public Organization getOrganization() {
		return organization;
	}

	public Queue getQueue() {
		return queue;
	}

	public Branch getBranch() {
		return queue.getBranch();
	}

	public User getCustomer() {
		return customer;
	}

	public Integer getTokenNumber() {
		return tokenNumber;
	}

	public LocalDate getBusinessDate() {
		return businessDate;
	}

	public QueuePriority getPriority() {
		return priority;
	}

	public QueueEntryStatus getStatus() {
		return status;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	public Instant getCalledAt() {
		return calledAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	/**
	 * Selects this entry for service. Called by the atomic claim — never
	 * directly, so a concurrently claimed row cannot be re-called.
	 */
	public void call() {
		if (status != QueueEntryStatus.WAITING) {
			throw new QueueEntryStateException("only WAITING entries can be called (was " + status + ")");
		}
		this.status = QueueEntryStatus.CALLED;
		this.calledAt = Instant.now();
	}

	public void startServing() {
		if (status != QueueEntryStatus.CALLED) {
			throw new QueueEntryStateException(
					"only CALLED entries can start serving (was " + status + ")");
		}
		this.status = QueueEntryStatus.SERVING;
	}

	public void complete() {
		if (status != QueueEntryStatus.SERVING) {
			throw new QueueEntryStateException(
					"only SERVING entries can complete (was " + status + ")");
		}
		this.status = QueueEntryStatus.COMPLETED;
		this.completedAt = Instant.now();
	}

	public void cancel() {
		if (status != QueueEntryStatus.WAITING && status != QueueEntryStatus.CALLED) {
			throw new QueueEntryStateException(
					"only WAITING or CALLED entries can be cancelled (was " + status + ")");
		}
		this.status = QueueEntryStatus.CANCELLED;
	}

	public void markNoShow() {
		if (status != QueueEntryStatus.WAITING && status != QueueEntryStatus.CALLED) {
			throw new QueueEntryStateException(
					"only WAITING or CALLED entries can be marked no-show (was " + status + ")");
		}
		this.status = QueueEntryStatus.NO_SHOW;
	}
}

