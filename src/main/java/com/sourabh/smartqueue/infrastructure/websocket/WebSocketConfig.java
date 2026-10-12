package com.sourabh.smartqueue.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.sourabh.smartqueue.auth.AuthHandshakeInterceptor;
import com.sourabh.smartqueue.auth.PrincipalHandshakeHandler;
import com.sourabh.smartqueue.auth.StompAuthChannelInterceptor;
import com.sourabh.smartqueue.infrastructure.cors.CorsProperties;

/**
 * STOMP over WebSocket (see ADR-018).
 *
 * <p>Endpoint {@code /ws} (no SockJS — no legacy-browser requirement);
 * application prefix {@code /app}; simple in-process broker for
 * {@code /topic/**}. The simple broker is deliberate: one deployable unit,
 * no horizontal WebSocket scaling requirement, and Redis already holds the
 * shared derived state a future multi-instance design would need. No
 * external broker relay, no SockJS, no security handshake yet.
 *
 * <p>Handshake origins follow the same CORS allow-list as the REST API
 * ({@code app.cors.allowed-origins}, defaulting to the local Vite
 * origin): a foreign site cannot even open the handshake, which matters
 * because the token travels in the query string ({@code ?token=}).
 * Requests without an {@code Origin} header (non-browser clients) are
 * unaffected — authentication still happens in the handshake
 * interceptor.
 *
 * <p>Phase 3F wires authentication into the handshake and the inbound
 * channel (see ADR-021): the handshake interceptor requires a bearer
 * token ({@code ?token=}, browsers cannot set headers), the handshake
 * handler binds the resulting principal to the session (the only place
 * Spring resolves STOMP users from), and the channel interceptor
 * authorizes SUBSCRIBE/SEND against organization/branch scope. The HTTP
 * handshake itself stays open — authenticating it is exactly what the
 * interceptor does.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final StompAuthChannelInterceptor authInterceptor;
	private final AuthHandshakeInterceptor handshakeInterceptor;
	private final PrincipalHandshakeHandler handshakeHandler;
	private final CorsProperties corsProperties;

	public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
			AuthHandshakeInterceptor handshakeInterceptor,
			PrincipalHandshakeHandler handshakeHandler,
			CorsProperties corsProperties) {
		this.authInterceptor = authInterceptor;
		this.handshakeInterceptor = handshakeInterceptor;
		this.handshakeHandler = handshakeHandler;
		this.corsProperties = corsProperties;
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// "/queue" carries per-session user destinations (in-app
		// notifications, ADR-032); "/topic" keeps the queue snapshots.
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns(
						corsProperties.getAllowedOrigins().toArray(String[]::new))
				.addInterceptors(handshakeInterceptor)
				.setHandshakeHandler(handshakeHandler);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(authInterceptor);
	}
}
