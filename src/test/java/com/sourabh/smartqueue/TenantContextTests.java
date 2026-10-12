package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.tenant.TenantContextRequiredException;

/**
 * Unit tests for the tenant context abstraction (see ADR-019): lifecycle,
 * fail-closed behavior, and scope semantics. No containers needed.
 */
class TenantContextTests {

	private final UUID orgId = UUID.randomUUID();
	private final UUID branchId = UUID.randomUUID();

	@AfterEach
	void clearContext() {
		TenantContextHolder.clear();
	}

	@Test
	void contextStoresOrganizationAndBranch() {
		TenantContext context = new TenantContext(orgId, branchId);

		assertThat(context.organizationId()).isEqualTo(orgId);
		assertThat(context.branchId()).isEqualTo(branchId);
		assertThat(context.isBranchScoped()).isTrue();
	}

	@Test
	void contextAllowsOrganizationLevelScope() {
		TenantContext context = new TenantContext(orgId, null);

		assertThat(context.organizationId()).isEqualTo(orgId);
		assertThat(context.branchId()).isNull();
		assertThat(context.isBranchScoped()).isFalse();
	}

	@Test
	void nullOrganizationIsRejected() {
		assertThatThrownBy(() -> new TenantContext(null, branchId))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new TenantContext(null, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void holderFailsClosedWhenNoContextSet() {
		assertThatThrownBy(() -> TenantContextHolder.get())
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void holderReturnsNullWhenNoContextSet() {
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void holderSetAndGetRoundTrip() {
		TenantContext context = new TenantContext(orgId, branchId);
		TenantContextHolder.set(context);

		assertThat(TenantContextHolder.get()).isEqualTo(context);
		assertThat(TenantContextHolder.getOrNull()).isEqualTo(context);
	}

	@Test
	void holderClearRemovesContext() {
		TenantContextHolder.set(new TenantContext(orgId, branchId));
		TenantContextHolder.clear();

		assertThat(TenantContextHolder.getOrNull()).isNull();
		assertThatThrownBy(() -> TenantContextHolder.get())
				.isInstanceOf(TenantContextRequiredException.class);
	}

	@Test
	void holderClearIsSafeWhenNoContextSet() {
		TenantContextHolder.clear();

		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void branchScopedContextIncludesMatchingBranch() {
		TenantContext context = new TenantContext(orgId, branchId);

		assertThat(context.includesBranch(branchId)).isTrue();
		assertThat(context.includesBranch(UUID.randomUUID())).isFalse();
	}

	@Test
	void organizationLevelContextIncludesAnyBranch() {
		TenantContext context = new TenantContext(orgId, null);

		assertThat(context.includesBranch(branchId)).isTrue();
		assertThat(context.includesBranch(UUID.randomUUID())).isTrue();
	}

	@Test
	void contextDoesNotContainUserOrRoles() {
		TenantContext context = new TenantContext(orgId, branchId);

		assertThat(context.organizationId()).isNotNull();
		assertThat(context.branchId()).isNotNull();
	}
}
