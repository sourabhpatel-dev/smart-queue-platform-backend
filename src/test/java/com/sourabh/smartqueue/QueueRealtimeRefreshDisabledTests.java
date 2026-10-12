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

import com.sourabh.smartqueue.queue.realtime.QueueRealtimeRefreshProperties;
import com.sourabh.smartqueue.queue.realtime.QueueRealtimeRefreshService;

/**
 * Disabled-refresh behavior (see ADR-020): with
 * {@code queue.realtime.refresh.enabled=false} no scheduled tick exists,
 * while event-driven snapshots keep working (proven by the Phase 3C suite,
 * which runs with the default enabled flag).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "queue.realtime.refresh.enabled=false")
class QueueRealtimeRefreshDisabledTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	ApplicationContext context;

	@Autowired
	QueueRealtimeRefreshProperties refreshProperties;

	@Test
	void refreshServiceBeanIsAbsentWhenDisabled() {
		assertThatThrownBy(() -> context.getBean(QueueRealtimeRefreshService.class))
				.isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
	}

	@Test
	void refreshPropertiesStillBindWhenDisabled() {
		assertThat(refreshProperties.isEnabled()).isFalse();
	}
}
