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

import com.sourabh.smartqueue.notification.NotificationRetentionProperties;
import com.sourabh.smartqueue.notification.application.NotificationRetentionService;

/**
 * Disabled-retention behavior (see ADR-033): with
 * {@code notifications.retention.enabled=false} no scheduled tick
 * exists, while notification creation, reads, and realtime delivery
 * keep working (proven by the Phase 4D suite, which runs with the
 * default enabled flag).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "notifications.retention.enabled=false")
class NotificationRetentionDisabledTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	ApplicationContext context;

	@Autowired
	NotificationRetentionProperties retentionProperties;

	@Test
	void retentionServiceBeanIsAbsentWhenDisabled() {
		assertThatThrownBy(() -> context.getBean(NotificationRetentionService.class))
				.isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
	}

	@Test
	void retentionPropertiesStillBindWhenDisabled() {
		assertThat(retentionProperties.isEnabled()).isFalse();
	}
}
