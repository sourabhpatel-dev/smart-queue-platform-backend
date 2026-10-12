package com.sourabh.smartqueue.auth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket handshake authentication (see ADR-021).
 *
 * <p>Browsers cannot attach headers to the WebSocket handshake, so the
 * bearer token travels as a {@code ?token=} query parameter (raw
 * non-browser clients may alternatively use the {@code Authorization}
 * header — same {@code Bearer} scheme as REST). A missing or untrustworthy
 * token rejects the handshake itself, so anonymous sessions never reach
 * STOMP: the client observes a failed CONNECT. On success the resulting
 * {@code Authentication} is parked in the handshake attributes for
 * {@link PrincipalHandshakeHandler} to bind to the session.
 *
 * <p>Query-string tokens can linger in access logs and proxies — accepted
 * here because the token is short-lived and carries no sensitive claims
 * (user id, organization id, email, roles only).
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

	private static final Logger log =
			LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

	static final String TOKEN_PARAMETER = "token";
	static final String PRINCIPAL_ATTRIBUTE = AuthHandshakeInterceptor.class.getName() + ".PRINCIPAL";

	private final JwtService jwtService;
	private final SessionRevocationService revocations;

	public AuthHandshakeInterceptor(JwtService jwtService,
			SessionRevocationService revocations) {
		this.jwtService = jwtService;
		this.revocations = revocations;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String token = tokenFrom(request);
		if (token == null) {
			reject(response, "missing WebSocket credentials");
			return false;
		}
		try {
			JwtService.Inspection inspection = jwtService.inspect(token);
			AuthenticatedPrincipal principal = inspection.principal();
			revocations.requireNotRevoked(principal.userId(), inspection.issuedAt());
			Authentication authentication = new UsernamePasswordAuthenticationToken(
					principal, null, principal.toAuthorities());
			attributes.put(PRINCIPAL_ATTRIBUTE, authentication);
			return true;
		} catch (JwtInvalidException | IllegalArgumentException e) {
			log.debug("WebSocket handshake rejected: {}", e.getMessage());
			reject(response, "invalid WebSocket credentials");
			return false;
		}
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Exception exception) {
	}

	private String tokenFrom(ServerHttpRequest request) {
		String query = request.getURI().getRawQuery();
		if (query != null) {
			for (String pair : query.split("&")) {
				int separator = pair.indexOf('=');
				if (separator > 0 && pair.substring(0, separator).equals(TOKEN_PARAMETER)) {
					String value = pair.substring(separator + 1);
					if (!value.isBlank()) {
						return value;
					}
				}
			}
		}
		String header = request.getHeaders().getFirst("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			String token = header.substring("Bearer ".length()).trim();
			if (!token.isEmpty()) {
				return token;
			}
		}
		return null;
	}

	private static void reject(ServerHttpResponse response, String reason) {
		if (response instanceof ServletServerHttpResponse servlet) {
			servlet.getServletResponse().setStatus(HttpStatus.FORBIDDEN.value());
		}
		log.debug("WebSocket handshake rejected: {}", reason);
	}
}
