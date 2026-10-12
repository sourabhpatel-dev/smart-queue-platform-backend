package com.sourabh.smartqueue.appointment.reminder;

/**
 * Appointment reminder vocabulary (see ADR-034).
 *
 * <p>Deliberately a single value: one upcoming-appointment reminder per
 * appointment. The column exists (rather than assuming "one reminder
 * ever") so a second reminder kind later is a new enum constant plus
 * its eligibility rule — not a schema change. String-persisted, never
 * ordinals.
 */
public enum AppointmentReminderType {

	UPCOMING
}
