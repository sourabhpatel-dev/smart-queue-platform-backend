package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sourabh.smartqueue.auth.AuthProperties;
import com.sourabh.smartqueue.auth.AuthenticatedPrincipal;
import com.sourabh.smartqueue.auth.JwtService;
import com.sourabh.smartqueue.auth.JwtAuthenticationFilter;
import com.sourabh.smartqueue.auth.JwtInvalidException;
import com.sourabh.smartqueue.auth.SessionRevocationService;
import com.sourabh.smartqueue.auth.TenantContextFilter;
import com.sourabh.smartqueue.tenant.TenantContextHolder;
import com.sourabh.smartqueue.user.RoleType;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter unit tests (see ADR-021): bearer validation and tenant bridging
 * without a server. {@code spring-test} mocks stand in for the servlet
 * container; the real {@link JwtService} signs and verifies.
 */
class SecurityFilterTests {

	static final String SECRET = "filter-test-only-signing-key-0123456789abcdef";

	private JwtService jwtService() {
		AuthProperties properties = new AuthProperties();
		properties.setSecret(SECRET);
		properties.setExpiration(Duration.ofHours(1));
		return new JwtService(properties, java.time.Clock.systemUTC());
	}

	private AuthenticatedPrincipal principal() {
		return new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(),
				"filter@example.com", Set.of(RoleType.STAFF));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
	}

	@Test
	void validBearerAuthenticates() throws Exception {
		JwtService service = jwtService();
		AuthenticatedPrincipal principal = principal();
		String token = service.issueToken(principal).token();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new JwtAuthenticationFilter(service, Mockito.mock(SessionRevocationService.class)).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.isEqualTo(principal);
	}

	@Test
	void invalidBearerIs401WithoutChain() throws Exception {
		JwtService service = jwtService();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer garbage-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		var proceeded = new boolean[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest r,
					jakarta.servlet.ServletResponse s) {
				proceeded[0] = true;
			}
		};

		new JwtAuthenticationFilter(service, Mockito.mock(SessionRevocationService.class)).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
		assertThat(response.getContentAsString()).doesNotContain("garbage-token");
		assertThat(proceeded[0]).isFalse();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void revokedTokenIs401WithoutChain() throws Exception {
		JwtService service = jwtService();
		AuthenticatedPrincipal principal = principal();
		String token = service.issueToken(principal).token();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		SessionRevocationService revocations = Mockito.mock(SessionRevocationService.class);
		Mockito.doThrow(new JwtInvalidException("revoked token"))
				.when(revocations).requireNotRevoked(
						Mockito.eq(principal.userId()), Mockito.any());

		new JwtAuthenticationFilter(service, revocations).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void missingHeaderPassesThroughAnonymously() throws Exception {
		JwtService service = jwtService();
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new JwtAuthenticationFilter(service, Mockito.mock(SessionRevocationService.class)).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void tenantFilterInstallsOrganizationScopeAndRestores() throws Exception {
		AuthenticatedPrincipal authenticated = principal();
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(
						authenticated, null, authenticated.toAuthorities()));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		var observed = new Object[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest r,
					jakarta.servlet.ServletResponse s) {
				observed[0] = TenantContextHolder.get();
			}
		};

		new TenantContextFilter().doFilter(request, response, chain);

		assertThat(observed[0]).isEqualTo(
				new com.sourabh.smartqueue.tenant.TenantContext(
						authenticated.organizationId(), null));
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void tenantFilterRestoresOnChainException() {
		AuthenticatedPrincipal authenticated = principal();
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(
						authenticated, null, authenticated.toAuthorities()));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest r,
					jakarta.servlet.ServletResponse s) throws java.io.IOException {
				throw new java.io.IOException("downstream failure");
			}
		};

		assertThatThrownBy(
				() -> new TenantContextFilter().doFilter(request, response, chain))
				.isInstanceOf(java.io.IOException.class);
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}

	@Test
	void tenantFilterIgnoresAnonymousRequests() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		var observed = new Object[1];
		MockFilterChain chain = new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest r,
					jakarta.servlet.ServletResponse s) {
				observed[0] = TenantContextHolder.getOrNull();
			}
		};

		new TenantContextFilter().doFilter(request, response, chain);

		assertThat(observed[0]).isNull();
		assertThat(TenantContextHolder.getOrNull()).isNull();
	}
}
