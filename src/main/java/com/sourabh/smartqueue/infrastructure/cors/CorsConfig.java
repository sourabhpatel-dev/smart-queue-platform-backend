package com.sourabh.smartqueue.infrastructure.cors;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Minimal CORS bridge for the React frontend (see ADR-028).
 *
 * <p>Credentials travel in the {@code Authorization} header (bearer JWTs),
 * never in cookies, so no credential sharing is enabled. Only the
 * versioned API surface is exposed; preflight is cached for one hour.
 *
 * <p>The same allow-list is exposed twice from this one source of
 * truth, deliberately: the MVC mapping below and the
 * {@link #corsConfigurationSource() security-chain source}. The MVC
 * mapping alone cannot authorize preflight requests, because Spring
 * Security filters run before DispatcherServlet CORS processing — an
 * unauthenticated {@code OPTIONS} preflight would die with 401 and the
 * browser would report a CORS failure for every authenticated API
 * call. The security chain therefore enables CORS from the same
 * source (see {@code SecurityConfig}), which answers preflights
 * before authentication runs.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

	private final CorsProperties properties;

	public CorsConfig(CorsProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
				.allowedHeaders("Authorization", "Content-Type", "Accept")
				.exposedHeaders("Location")
				.allowCredentials(false)
				.maxAge(3600);
	}

	/**
	 * Security-chain CORS source: same allow-list, same surface. Used
	 * by {@code SecurityConfig} so preflight {@code OPTIONS} requests
	 * are answered before authentication runs.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.getAllowedOrigins());
		configuration.setAllowedMethods(
				List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(
				List.of("Authorization", "Content-Type", "Accept"));
		configuration.setExposedHeaders(List.of("Location"));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
