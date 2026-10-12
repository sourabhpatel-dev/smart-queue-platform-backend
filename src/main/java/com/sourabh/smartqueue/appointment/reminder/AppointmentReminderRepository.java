package com.sourabh.smartqueue.appointment.reminder;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence boundary for {@link AppointmentReminder} (see ADR-034).
 *
 * <p>Duplicate prevention is the database unique constraint, not a
 * pre-check: callers may probe with
 * {@link #findByAppointmentIdAndReminderType} for counting/logging, but
 * the insert itself (flushed inside the candidate transaction) is what
 * arbitrates races — a lost race surfaces as a constraint violation,
 * never as a second row.
 */
public interface AppointmentReminderRepository extends JpaRepository<AppointmentReminder, UUID> {

	Optional<AppointmentReminder> findByAppointmentIdAndReminderType(
			UUID appointmentId, AppointmentReminderType reminderType);

	boolean existsByAppointmentIdAndReminderType(
			UUID appointmentId, AppointmentReminderType reminderType);

	long countByAppointmentIdAndReminderType(
			UUID appointmentId, AppointmentReminderType reminderType);
}
