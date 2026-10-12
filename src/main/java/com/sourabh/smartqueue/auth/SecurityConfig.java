package com.sourabh.smartqueue.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP security boundary (see ADR-021).
 *
 * <p>Model: the login endpoint, the WebSocket handshake, and the health
 * probe are public; every other REST endpoint requires authentication
 * (none exist yet besides login — the rule is posture, not pretense).
 * Stateless throughout: no sessions, no cookies, no CSRF tokens to manage
 * — CSRF is disabled <em>because</em> credentials travel in the
 * {@code Authorization} header rather than ambient browser state, not as a
 * casual default. Browser CORS for the React frontend lives in
 * {@code infrastructure.cors} (allow-listed origins on {@code /api/**}
 * only — see ADR-028) and is enabled in this chain from that same
 * source, so preflight requests are answered before authentication
 * runs instead of dying as unauthenticated.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded at the
 * application entry point: this application authenticates from its own
 * {@code app_user} table through {@link AuthService}, so Boot's generated
 * in-memory user (and its logged password) would be misleading dead weight.
 *
 * <p>Method security is enabled as a capability for future API phases;
 * nothing is annotated yet — authorization policy lives in
 * {@link TenantAccessPolicy} until there are endpoints to protect.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final TenantContextFilter tenantContextFilter;
	private final CorsConfigurationSource corsConfigurationSource;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
			TenantContextFilter tenantContextFilter,
			CorsConfigurationSource corsConfigurationSource) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.tenantContextFilter = tenantContextFilter;
		this.corsConfigurationSource = corsConfigurationSource;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				// CORS inside the chain (same allow-list as the MVC
				// mapping): preflight OPTIONS must be answered before
				// authentication runs, or browsers block every
				// authenticated cross-origin API call.
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.sessionManagement(sessions -> sessions
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/api/v1/auth/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/discovery/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers(HttpMethod.GET, "/ws/**").permitAll()
						// API documentation is not application data: the document
						// and UI stay public so integrators can read the
						// contract, while every application endpoint behind
						// /api/v1 (except login) still requires a bearer token
						// at runtime. Production operators may additionally set
						// springdoc.swagger-ui.enabled=false (see ADR-022).
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
								"/swagger-ui.html").permitAll()
						// The error endpoint must stay public: for unmapped
						// paths (and handler failures) the nested error
						// dispatch re-authorizes without the request-scoped
						// authentication (stateless chain persists nothing),
						// so protecting /error would mask every true status
						// as 401. This mirrors Boot's default rules.
						.requestMatchers("/error").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(unauthorizedEntryPoint())
						.accessDeniedHandler(forbiddenHandler()))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class)
				.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static AuthenticationEntryPoint unauthorizedEntryPoint() {
		return (request, response, exception) -> {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json");
			response.getWriter().write(
					"{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid authentication\"}");
		};
	}

	private static AccessDeniedHandler forbiddenHandler() {
		return (request, response, exception) -> {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			response.setContentType("application/json");
			response.getWriter().write(
					"{\"error\":\"Forbidden\",\"message\":\"Insufficient authorization\"}");
		};
	}
}
