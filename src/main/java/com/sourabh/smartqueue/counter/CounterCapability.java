package com.sourabh.smartqueue.counter;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.service.Service;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

/**
 * Records that a counter supports a service: "Counter 2 handles Blood Test"
 * (see ADR-008).
 *
 * <p>A dedicated association entity (not a bare {@code @ManyToMany} join
 * table) for one reason: cross-branch integrity. The row carries the shared
 * {@code branch_id}, and the V3 migration defines <em>composite</em> foreign
 * keys {@code (service_id, branch_id)} and {@code (counter_id, branch_id)},
 * so the database itself rejects a Branch-1 service linked to a Branch-2
 * counter. The JPA mapping below stays intentionally simple (one join column
 * per association — Hibernate forbids mixing writable and read-only columns
 * inside a single composite mapping); the migration is the authority on the
 * composite keys. A Java-side guard fails fast with a clear message; the
 * composite foreign keys are the backstop that no code path can bypass.
 *
 * <p>The entity shape also leaves room for future capability metadata
 * (priority, enabled flag) without restructuring. Unidirectional, lazy, no
 * cascade — the Phase 2A conventions.
 */
@Entity
@Table(
		name = "counter_capability",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_capability_service_counter",
				columnNames = {"service_id", "counter_id"}))
public class CounterCapability extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_capability_branch"))
	private Branch branch;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "service_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_capability_service"))
	private Service service;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "counter_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_capability_counter"))
	private ServiceCounter counter;

	protected CounterCapability() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if service and counter belong to
	 * different branches. The database composite foreign keys enforce the
	 * same rule independently (see V3 migration).
	 */
	public CounterCapability(Branch branch, Service service, ServiceCounter counter) {
		if (!service.getBranch().getId().equals(branch.getId())
				|| !counter.getBranch().getId().equals(branch.getId())) {
			throw new IllegalArgumentException(
					"service, counter and capability must belong to the same branch");
		}
		this.branch = branch;
		this.service = service;
		this.counter = counter;
	}

	public Branch getBranch() {
		return branch;
	}

	public Service getService() {
		return service;
	}

	public ServiceCounter getCounter() {
		return counter;
	}
}
