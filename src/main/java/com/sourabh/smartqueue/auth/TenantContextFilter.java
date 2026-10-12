package com.sourabh.smartqueue.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sourabh.smartqueue.tenant.TenantContext;
import com.sourabh.smartqueue.tenant.TenantContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bridges authentication into tenant scope for REST requests (see ADR-021,
 * ADR-040).
 *
 * <p>Conceptually: JWT → authentication → principal → organization id →
 * {@link TenantContext}. Runs after {@link JwtAuthenticationFilter}: when
 * the request is authenticated, it installs an organization-level context
 * (no branch selection exists at the HTTP layer yet) around the rest of
 * the chain, restoring the previous context afterwards — even on
 * exception — so no request leaks scope into the next one sharing the
 * thread. Anonymous requests pass through untouched (background and public
 * paths never depend on this holder).
 *
 * <p><strong>ADR-040:</strong> the context is installed only for
 * principals that resolved an operational organization (STAFF/MANAGER/
 * ADMIN with an ACTIVE membership). Platform-level customers carry no
 * organization and receive <em>no</em> global tenant context: their
 * organization-specific actions derive scope from the target resource in
 * the service layer (owner-scoped, never organization-scoped). The
 * fail-closed holder keeps protecting every operational path.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object principal = authentication == null ? null : authentication.getPrincipal();
		if (!(principal instanceof AuthenticatedPrincipal authenticated)
				|| !authenticated.hasOrganization()) {
			chain.doFilter(request, response);
			return;
		}
		TenantContext previous = TenantContextHolder.getOrNull();
		TenantContextHolder.set(new TenantContext(authenticated.organizationId(), null));
		try {
			chain.doFilter(request, response);
		} finally {
			TenantContextHolder.restore(previous);
		}
	}
}
