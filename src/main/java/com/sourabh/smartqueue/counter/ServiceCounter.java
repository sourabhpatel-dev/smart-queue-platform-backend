package com.sourabh.smartqueue.counter;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A physical/service point at a branch where services are delivered
 * (see ADR-008).
 *
 * <p>Belongs to exactly one Branch; names ("Counter 1") are unique within
 * the branch but reusable across branches. A counter supports services via
 * {@code CounterCapability} rows — never by belonging to a single service.
 * Unidirectional lazy {@code ServiceCounter → Branch}, no cascade.
 */
@Entity
@Table(
		name = "service_counter",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_counter_branch_name",
				columnNames = {"branch_id", "name"}))
public class ServiceCounter extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_counter_branch"))
	private Branch branch;

	@NotBlank
	@Size(max = 100)
	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private CounterStatus status = CounterStatus.ACTIVE;

	protected ServiceCounter() {
		// JPA only.
	}

	public ServiceCounter(Branch branch, String name) {
		this.branch = branch;
		this.name = name;
	}

	public Branch getBranch() {
		return branch;
	}

	public void setBranch(Branch branch) {
		this.branch = branch;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public CounterStatus getStatus() {
		return status;
	}

	public void setStatus(CounterStatus status) {
		this.status = status;
	}
}
