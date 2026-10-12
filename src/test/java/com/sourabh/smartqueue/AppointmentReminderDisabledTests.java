package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderProperties;
import com.sourabh.smartqueue.appointment.reminder.AppointmentReminderScheduler;
import com.sourabh.smartqueue.appointment.reminder.application.AppointmentReminderService;

/**
 * Disabled-reminder behavior (see ADR-034): with
 * {@code appointment.reminders.enabled=false} no sweep exists, while
 * booking, the notification center, and retention keep working
 * (proven by their own suites, which run with the default enabled
 * flag).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "appointment.reminders.enabled=false")
class AppointmentReminderDisabledTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	ApplicationContext context;

	@Autowired
	AppointmentReminderProperties reminderProperties;

	@Test
	void reminderBeansAreAbsentWhenDisabled() {
		assertThatThrownBy(() -> context.getBean(AppointmentReminderService.class))
				.isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
		assertThatThrownBy(() -> context.getBean(AppointmentReminderScheduler.class))
				.isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
	}

	@Test
	void reminderPropertiesStillBindWhenDisabled() {
		assertThat(reminderProperties.isEnabled()).isFalse();
	}
}
