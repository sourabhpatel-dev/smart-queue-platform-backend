package com.sourabh.smartqueue.infrastructure.cors;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allow-list (see ADR-028).
 *
 * <p>Local development default is the Vite dev server origin only. Production
 * operators set {@code app.cors.allowed-origins} to their exact frontend
 * origin(s) — never {@code *} with credentials, never a broad pattern.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

	private List<String> allowedOrigins = List.of("http://localhost:5173");

	public List<String> getAllowedOrigins() {
		return allowedOrigins;
	}

	public void setAllowedOrigins(List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}
}
