package com.sourabh.smartqueue.holiday;

import java.time.LocalDate;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One explicit full-day branch closure, e.g. 2026-12-25 "Christmas Day"
 * (see ADR-010).
 *
 * <p>Branch-specific by design: the same date is a holiday only where a row
 * says so. Phase 2D models full closures only — no partial days, no
 * recurrences, no national-calendar automation. Those are product decisions
 * for a later phase, not missing engineering.
 *
 * <p>Unidirectional lazy {@code Holiday → Branch}, no cascade — the Phase
 * 2A conventions.
 */
@Entity
@Table(
		name = "holiday",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_holiday_branch_date",
				columnNames = {"branch_id", "holiday_date"}))
public class Holiday extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_holiday_branch"))
	private Branch branch;

	@NotNull
	@Column(name = "holiday_date", nullable = false)
	private LocalDate date;

	@NotBlank
	@Size(max = 150)
	@Column(name = "name", nullable = false, length = 150)
	private String name;

	protected Holiday() {
		// JPA only.
	}

	public Holiday(Branch branch, LocalDate date, String name) {
		this.branch = branch;
		this.date = date;
		this.name = name;
	}

	public Branch getBranch() {
		return branch;
	}

	public void setBranch(Branch branch) {
		this.branch = branch;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
}
