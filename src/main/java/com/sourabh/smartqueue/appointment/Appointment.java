package com.sourabh.smartqueue.appointment;

import java.time.Instant;

import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.service.Service;
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
import jakarta.validation.constraints.NotNull;

/**
 * A booked service interval for a customer: "Asha, General Consultation,
 * Monday [10:00, 10:30)" (see ADR-011).
 *
 * <p>The interval is a stored {@code [startAt, endAt)} pair of unambiguous
 * instants, snapshotted at booking from the service's standard duration —
 * later duration changes never rewrite history. Adjacent appointments
 * (10:30 after 10:00–10:30) do not overlap.
 *
 * <p>Ownership is explicit on all three axes (organization, branch,
 * service, customer) with constructor guards plus composite-FK backstops in
 * V6: a service from Branch B or a customer from Organization B can never
 * land on Branch A's appointment, on any code path.
 *
 * <p>Status transitions are guarded methods, not a framework: only
 * {@code SCHEDULED}/{@code CONFIRMED} occupy a slot (the database
 * exclusions ignore the rest), and leaving the active states is one-way.
 * Unidirectional lazy associations, no cascade — the established
 * conventions.
 */
@Entity
@Table(name = "appointment")
public class Appointment extends BaseAuditableEntity {

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_organization"))
	private Organization organization;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "branch_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_branch"))
	private Branch branch;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "service_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_service"))
	private Service service;

	@NotNull
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "customer_id",
			nullable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_customer"))
	private User customer;

	@NotNull
	@Column(name = "start_at", nullable = false)
	private Instant startAt;

	@NotNull
	@Column(name = "end_at", nullable = false)
	private Instant endAt;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private AppointmentStatus status = AppointmentStatus.SCHEDULED;

	protected Appointment() {
		// JPA only.
	}

	/**
	 * @throws IllegalArgumentException if the interval is empty/reversed, or
	 * if branch and service do not share the given organization (with the
	 * service additionally on the same branch). For <em>operational</em>
	 * customers the customer must also share that organization; a
	 * customer (CUSTOMER-only role) books at any ACTIVE branch: neither a
	 * platform (null) nor a legacy organization value pins them to one
	 * organization (ADR-040). The service layer validated the branch
	 * scope before this constructor runs.
	 */
	public Appointment(Organization organization, Branch branch, Service service, User customer,
			Instant startAt, Instant endAt) {
		if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
			throw new IllegalArgumentException("appointment end must be after start");
		}
		if (!branch.getOrganization().getId().equals(organization.getId())
				|| !service.getBranch().getId().equals(branch.getId())) {
			throw new IllegalArgumentException(
					"appointment branch and service must belong to the same organization"
							+ " (and the service to the same branch)");
		}
		boolean operational = customer.getRoles().stream()
				.anyMatch(role -> role != RoleType.CUSTOMER);
		if (operational && (customer.getOrganization() == null
				|| !customer.getOrganization().getId().equals(organization.getId()))) {
			throw new IllegalArgumentException(
					"appointment branch, service and customer must belong to the same organization");
		}
		this.organization = organization;
		this.branch = branch;
		this.service = service;
		this.customer = customer;
		this.startAt = startAt;
		this.endAt = endAt;
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

	public User getCustomer() {
		return customer;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public AppointmentStatus getStatus() {
		return status;
	}

	/**
	 * Leaves the active states; the freed slot is immediately re-bookable
	 * because the exclusion constraints ignore cancelled rows.
	 *
	 * @throws IllegalStateException unless currently SCHEDULED or CONFIRMED.
	 */
	public void cancel() {
		requireActive("cancel");
		this.status = AppointmentStatus.CANCELLED;
	}

	/**
	 * @throws IllegalStateException unless currently SCHEDULED or CONFIRMED.
	 */
	public void complete() {
		requireActive("complete");
		this.status = AppointmentStatus.COMPLETED;
	}

	/**
	 * @throws IllegalStateException unless currently SCHEDULED or CONFIRMED.
	 */
	public void markNoShow() {
		requireActive("mark no-show");
		this.status = AppointmentStatus.NO_SHOW;
	}

	private void requireActive(String action) {
		if (status != AppointmentStatus.SCHEDULED && status != AppointmentStatus.CONFIRMED) {
			throw new IllegalStateException("cannot " + action + " an appointment with status " + status);
		}
	}
}

