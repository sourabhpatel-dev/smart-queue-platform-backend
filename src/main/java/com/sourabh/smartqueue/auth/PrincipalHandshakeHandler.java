package com.sourabh.smartqueue.auth;

import java.security.Principal;
import java.util.Collection;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import com.sourabh.smartqueue.notification.NotificationDestinations;

/**
 * Binds the handshake authentication to the WebSocket session (see ADR-021,
 * extended in ADR-032).
 *
 * <p>Spring resolves the STOMP session user from
 * {@code WebSocketSession.getPrincipal()}, which for native WebSocket comes
 * from this handler — not from STOMP CONNECT headers (setting a user there
 * never reaches the session). The {@link AuthHandshakeInterceptor} already
 * validated the token; this handler simply promotes its
 * {@code Authentication} to the session principal so every SUBSCRIBE and
 * SEND carries it.
 *
 * <p>The returned token names the session after the user's id (instead of
 * the default record rendering). The type, principal object, and
 * authorities are unchanged — queue-scope authorization keeps working —
 * but Spring's user-destination routing
 * ({@link NotificationDestinations}) can now resolve per-user sessions
 * by that stable name.
 */
@Component
public class PrincipalHandshakeHandler extends DefaultHandshakeHandler {

	@Override
	protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
			Map<String, Object> attributes) {
		Object authentication = attributes.get(AuthHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
		if (authentication instanceof Authentication auth
				&& auth.getPrincipal() instanceof AuthenticatedPrincipal principal) {
			return new SessionAuthentication(principal, auth);
		}
		if (authentication instanceof Authentication auth) {
			return auth;
		}
		return super.determineUser(request, wsHandler, attributes);
	}

	/**
	 * Same identity and authorities, session-stable name: Spring routes
	 * {@code /user} destinations by {@code getName()}, so the name is the
	 * user id and nothing else.
	 */
	static final class SessionAuthentication extends UsernamePasswordAuthenticationToken {

		private final String sessionName;

		SessionAuthentication(AuthenticatedPrincipal principal, Authentication source) {
			// The three-arg constructor marks the token authenticated (the
			// handshake only stores validated tokens); setAuthenticated
			// must not be called afterwards — it rejects trusted tokens.
			super(principal, source.getCredentials(), authoritiesOf(source));
			setDetails(source.getDetails());
			this.sessionName = principal.userId().toString();
		}

		private static Collection<? extends GrantedAuthority> authoritiesOf(Authentication source) {
			Collection<? extends GrantedAuthority> authorities = source.getAuthorities();
			return authorities == null ? java.util.List.of() : authorities;
		}

		@Override
		public String getName() {
			return sessionName;
		}
	}
}
