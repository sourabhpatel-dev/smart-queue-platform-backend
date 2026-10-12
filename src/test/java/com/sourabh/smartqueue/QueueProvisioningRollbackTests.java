package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.branch.Branch;
import com.sourabh.smartqueue.membership.MembershipRole;
import com.sourabh.smartqueue.membership.OrganizationMembership;
import com.sourabh.smartqueue.membership.OrganizationMembershipRepository;
import com.sourabh.smartqueue.branch.BranchRepository;
import com.sourabh.smartqueue.organization.Organization;
import com.sourabh.smartqueue.organization.OrganizationRepository;
import com.sourabh.smartqueue.queue.Queue;
import com.sourabh.smartqueue.queue.QueueRepository;
import com.sourabh.smartqueue.queue.application.QueueProvisioningService;
import com.sourabh.smartqueue.service.ServiceRepository;
import com.sourabh.smartqueue.service.api.CreateServiceRequest;
import com.sourabh.smartqueue.service.application.ServiceManagementService;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;
import com.sourabh.smartqueue.user.User;
import com.sourabh.smartqueue.user.UserRepository;

/**
 * Provisioning-failure atomicity (see ADR-038).
 *
 * <p>Runs with the provisioning seam replaced by a throwing stub, so
 * the only real participants are the service insert and the
 * transaction manager. Proves the Phase 4K rule: when queue
 * provisioning fails, the service creation rolls back — never a
 * queueless service. The stub lives in test configuration only;
 * production code carries no failure hooks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class QueueProvisioningRollbackTests {

	static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

	@TestConfiguration
	static class FailingProvisioning {
		@Bean
		@Primary
		QueueProvisioningService failingProvisioning(QueueRepository queues) {
			return new QueueProvisioningService(queues) {
				@Override
				public Queue ensureQueueForService(Organization organization, Branch branch,
						com.sourabh.smartqueue.service.Service service) {
					throw new IllegalStateException("provisioning boom");
				}
			};
		}
	}

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Container
	@ServiceConnection
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

	@Autowired
	OrganizationRepository organizations;

	@Autowired
	BranchRepository branches;

	@Autowired
	ServiceRepository services;

	@Autowired
	QueueRepository queues;

	@Autowired
	UserRepository users;

	@Autowired
	OrganizationMembershipRepository memberships;

	@Autowired
	ServiceManagementService serviceManagement;

	String tag;
	Organization org;
	Branch branch;
	AuthenticatedPrincipal managerPrincipal;

	@BeforeEach
	void setUp() {
		TenantContextHolder.clear();
		tag = UUID.randomUUID().toString().substring(0, 8);
		org = organizations.save(new Organization("Rollback Org " + tag));
		branch = branches.save(new Branch(org, "Downtown " + tag,
				"Indore", "India", KOLKATA));
		User manager = users.save(new User(org, "mgr-" + tag + "@example.com", "Mgr " + tag));
		manager.grantRole(RoleType.MANAGER);
		manager = users.save(manager);
		// Mirrors the V20 backfill: the policy authorizes on the membership.
		memberships.save(new OrganizationMembership(manager, org, MembershipRole.MANAGER));
		managerPrincipal = new AuthenticatedPrincipal(manager.getId(), org.getId(),
				manager.getEmail(), Set.of(RoleType.MANAGER));
	}

	@AfterEach
	void tearDown() {
		TenantContextHolder.clear();
	}

	@Test
	void provisioningFailureRollsBackServiceCreation() {
		assertThatThrownBy(() -> serviceManagement.createService(managerPrincipal, branch.getId(),
				new CreateServiceRequest("Doomed " + tag, null, 30)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("provisioning boom");

		assertThat(services.findByBranchId(branch.getId(), PageRequest.of(0, 10)).getContent())
				.isEmpty();
		assertThat(queues.findByBranchId(branch.getId())).isEmpty();
	}
}

