package com.sourabh.smartqueue.auth;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the bearer token on each REST request (see ADR-021).
 *
 * <p>Reads {@code Authorization: Bearer <token>}, verifies it through
 * {@link JwtService}, rejects tokens retired by a password change
 * (see ADR-037), and publishes the resulting
 * {@link AuthenticatedPrincipal} into the {@code SecurityContext}.
 * Requests without a header pass through anonymously — the filter
 * chain's rules decide whether that is allowed. Any untrustworthy
 * token short-circuits with a generic {@code 401} (never the
 * validation detail).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	static final String AUTHORIZATION_HEADER = "Authorization";
	static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;
	private final SessionRevocationService revocations;

	public JwtAuthenticationFilter(JwtService jwtService,
			SessionRevocationService revocations) {
		this.jwtService = jwtService;
		this.revocations = revocations;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String header = request.getHeader(AUTHORIZATION_HEADER);
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			String token = header.substring(BEARER_PREFIX.length()).trim();
			if (!token.isEmpty()) {
				try {
					JwtService.Inspection inspection = jwtService.inspect(token);
					AuthenticatedPrincipal principal = inspection.principal();
					revocations.requireNotRevoked(
							principal.userId(), inspection.issuedAt());
					SecurityContextHolder.getContext().setAuthentication(
							new UsernamePasswordAuthenticationToken(
									principal, null, principal.toAuthorities()));
				} catch (JwtInvalidException e) {
					unauthorized(response);
					return;
				}
			} else {
				unauthorized(response);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json");
		response.getWriter().write(
				"{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid authentication\"}");
	}
}
