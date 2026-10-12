package com.sourabh.smartqueue.service;

import java.time.Duration;

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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Something a customer requests at a branch, e.g. "General Consultation"
 * (see ADR-008).
 *
 * <p>Belongs to exactly one Branch; names are unique within the branch but
 * may repeat across branches. Unidirectional lazy {@code Service → Branch},
 * no cascade — same conventions as Phase 2A.
 *
 * <p>Duration is stored as whole minutes ({@code standard_duration_minutes}):
 * Hibernate maps {@code Duration} to an opaque numeric of nanoseconds, while
 * PostgreSQL {@code INTERVAL} fails Hibernate validation — so the explicit,
 * readable, sortable minutes column wins over both. The unit lives in the
 * column name and in {@link #getStandardDuration()}, the single conversion
 * point for future slot math and wait-time estimates. Values must be
 * positive (guarded in the setter; Bean Validation has no duration-range
 * constraint, so {@code @Min} on the minutes field states it
 * declaratively).
 */
@Entity
@Table(
		name = "service",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_service_branch_name",
				columnNames = {"branch_id", "name"}))
public class Service extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_service_branch"))
	private Branch branch;

	@NotBlank
	@Size(max = 150)
	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Size(max = 1000)
	@Column(name = "description", length = 1000)
	private String description;

	@NotNull
	@Min(1)
	@Column(name = "standard_duration_minutes", nullable = false)
	private Integer standardDurationMinutes;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ServiceStatus status = ServiceStatus.ACTIVE;

	protected Service() {
		// JPA only.
	}

	public Service(Branch branch, String name, Duration standardDuration) {
		this.branch = branch;
		this.name = name;
		setStandardDuration(standardDuration);
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

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Duration getStandardDuration() {
		return Duration.ofMinutes(standardDurationMinutes);
	}

	public void setStandardDuration(Duration standardDuration) {
		if (standardDuration == null || standardDuration.isZero() || standardDuration.isNegative()) {
			throw new IllegalArgumentException("standardDuration must be a positive duration");
		}
		if (standardDuration.toMinutes() == 0) {
			throw new IllegalArgumentException("standardDuration must be at least one minute");
		}
		this.standardDurationMinutes = Math.toIntExact(standardDuration.toMinutes());
	}

	public ServiceStatus getStatus() {
		return status;
	}

	public void setStatus(ServiceStatus status) {
		this.status = status;
	}
}
