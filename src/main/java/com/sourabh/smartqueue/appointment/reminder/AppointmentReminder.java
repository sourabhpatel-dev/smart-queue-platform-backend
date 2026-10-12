package com.sourabh.smartqueue.appointment.reminder;

import java.time.Instant;

import com.sourabh.smartqueue.appointment.Appointment;
import com.sourabh.smartqueue.common.BaseAuditableEntity;
import com.sourabh.smartqueue.organization.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Durable proof that one reminder was processed for one appointment
 * (see ADR-034).
 *
 * <p>Creation-only semantics: the row's existence <em>is</em> the state
 * (there is no PENDING/SENT lifecycle — a smaller model would be no
 * model at all, and a larger one has no failure to describe yet). The
 * scheduler inserts this row and the reminder notification in one
 * transaction; the database unique constraint on
 * {@code (appointment_id, reminder_type)} arbitrates concurrent
 * schedulers, so two instances discovering the same appointment yield
 * one row and one notification, never two.
 *
 * <p>Unidirectional lazy associations, no cascade — the established
 * conventions. Appointment deletion is not a supported operation, so
 * the foreign key fails loudly rather than cascading silently.
 */
@Entity
@Table(name = "appointment_reminder")
public class AppointmentReminder extends BaseAuditableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "organization_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_reminder_organization"))
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
			name = "appointment_id",
			nullable = false,
			updatable = false,
			foreignKey = @ForeignKey(name = "fk_appointment_reminder_appointment"))
	private Appointment appointment;

	@Enumerated(EnumType.STRING)
	@Column(name = "reminder_type", nullable = false, length = 20, updatable = false)
	private AppointmentReminderType reminderType;

	@Column(name = "sent_at", nullable = false, updatable = false)
	private Instant sentAt;

	protected AppointmentReminder() {
		// JPA only.
	}

	public AppointmentReminder(Organization organization, Appointment appointment,
			AppointmentReminderType reminderType, Instant sentAt) {
		if (organization == null) {
			throw new IllegalArgumentException("organization is required");
		}
		if (appointment == null) {
			throw new IllegalArgumentException("appointment is required");
		}
		if (reminderType == null) {
			throw new IllegalArgumentException("reminder type is required");
		}
		if (sentAt == null) {
			throw new IllegalArgumentException("sent time is required");
		}
		if (!appointment.getOrganization().getId().equals(organization.getId())) {
			throw new IllegalArgumentException(
					"reminder organization must match the appointment organization");
		}
		this.organization = organization;
		this.appointment = appointment;
		this.reminderType = reminderType;
		this.sentAt = sentAt;
	}

	public Organization getOrganization() {
		return organization;
	}

	public Appointment getAppointment() {
		return appointment;
	}

	public AppointmentReminderType getReminderType() {
		return reminderType;
	}

	public Instant getSentAt() {
		return sentAt;
	}
}
