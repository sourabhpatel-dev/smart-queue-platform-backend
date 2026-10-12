package com.sourabh.smartqueue.workinghour;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * One continuous operating interval of a branch on a weekday, e.g. Monday
 * 09:00–13:00 (see ADR-010).
 *
 * <p>A day is NOT a single opening/closing pair: split periods (lunch
 * breaks) are multiple rows, and a weekday with no rows simply means
 * "normally closed" — no {@code CLOSED} rows, no {@code isClosed} flag.
 *
 * <p>Two invariants, two layers:
 * <ul>
 *   <li>{@code startTime < endTime} — constructor guard (fail fast) plus a
 *   {@code CHECK} constraint, so raw SQL cannot insert {@code 18:00 → 09:00}
 *   either.</li>
 *   <li>No overlapping intervals per branch/day — enforced <em>only</em> by
 *   the PostgreSQL exclusion constraint (an entity cannot see its sibling
 *   rows, so there is no honest application-level check; the exclusion is
 *   the single enforcement point).</li>
 * </ul>
 *
 * <p>Times are minute-granular wall-clock {@link LocalTime} (no seconds):
 * the exclusion constraint compares minute ranges, and sub-minute values
 * would truncate into empty ranges that never conflict. The constructor
 * rejects them so the constraint stays exact. Timezone interpretation is
 * deferred to the appointment phase — see ADR-010.
 *
 * <p>Unidirectional lazy {@code WorkingHour → Branch}, no cascade — the
 * Phase 2A conventions.
 */
@Entity
@Table(name = "working_hour")
public class WorkingHour extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_working_hour_branch"))
	private Branch branch;

	@NotNull
	@Convert(converter = DayOfWeekConverter.class)
	@Column(name = "day_of_week", nullable = false, length = 10)
	private DayOfWeek dayOfWeek;

	@NotNull
	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@NotNull
	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	protected WorkingHour() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if {@code startTime} is not before
	 * {@code endTime}, or if either time carries seconds/nanos (schedules
	 * are minute-granular; see class javadoc). Overlap with sibling rows is
	 * enforced by the database exclusion constraint.
	 */
	public WorkingHour(Branch branch, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
		if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
			throw new IllegalArgumentException("startTime must be before endTime");
		}
		if (startTime.getSecond() != 0 || startTime.getNano() != 0
				|| endTime.getSecond() != 0 || endTime.getNano() != 0) {
			throw new IllegalArgumentException("working hours are minute-granular (no seconds)");
		}
		this.branch = branch;
		this.dayOfWeek = dayOfWeek;
		this.startTime = startTime;
		this.endTime = endTime;
	}

	public Branch getBranch() {
		return branch;
	}

	public DayOfWeek getDayOfWeek() {
		return dayOfWeek;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}
}
