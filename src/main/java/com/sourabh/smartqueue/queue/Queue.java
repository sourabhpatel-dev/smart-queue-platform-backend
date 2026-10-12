package com.sourabh.smartqueue.queue;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.service.Service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

/**
 * The operational waiting line for one Branch + Service pair (see ADR-012).
 *
 * <p>Cardinality is exactly one logical row per (branch, service): multiple
 * counters serve the SAME queue (never one queue per counter), and history
 * lives on entries, not on retired queue rows. An INACTIVE queue rejects
 * new walk-ins. Unidirectional lazy associations, no cascade — the
 * established conventions. Cross-branch service attachment is rejected by
 * the constructor guard and the V7 composite foreign key alike.
 */
@Entity
@Table(
		name = "queue",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_queue_branch_service",
				columnNames = {"branch_id", "service_id"}))
public class Queue extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_queue_organization"))
	private Organization organization;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_queue_branch"))
	private Branch branch;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "service_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_queue_service"))
	private Service service;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private QueueStatus status = QueueStatus.ACTIVE;

	protected Queue() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if the service belongs to a different
	 * branch, or any party belongs to a different organization. The V7
	 * composite foreign keys enforce the same rules independently.
	 */
	public Queue(Organization organization, Branch branch, Service service) {
		if (!branch.getOrganization().getId().equals(organization.getId())
				|| !service.getBranch().getId().equals(branch.getId())) {
			throw new IllegalArgumentException(
					"queue branch and service must belong to the same organization"
							+ " (and the service to the same branch)");
		}
		this.organization = organization;
		this.branch = branch;
		this.service = service;
	}

	public Organization getOrganization() {
		return organization;
	}

	public Branch getBranch() {
		return branch;
	}

	public Service getService() {
		return service;
	}

	public QueueStatus getStatus() {
		return status;
	}

	public void setStatus(QueueStatus status) {
		this.status = status;
	}
}
