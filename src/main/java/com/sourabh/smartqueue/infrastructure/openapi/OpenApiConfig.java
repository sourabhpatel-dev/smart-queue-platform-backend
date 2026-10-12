package com.sourabh.smartqueue.infrastructure.openapi;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * API contract metadata (see ADR-022).
 *
 * <p>One document for the versioned {@code /api/v1} surface: title,
 * description, and the bearer-token scheme that every authenticated endpoint
 * references via {@code @SecurityRequirement(name = "bearerAuth")}. Public
 * endpoints (login, health, the docs themselves) carry no requirement and
 * render unlocked. Actuator stays outside the document.
 *
 * <p>Swagger UI policy: served with the application by default for local
 * development; production operators set
 * {@code springdoc.swagger-ui.enabled=false} (documented in
 * {@code docs/api/README.md}).
 */
@Configuration
@OpenAPIDefinition(info = @Info(
		title = "Smart Queue & Appointment Management Platform API",
		description = "Versioned REST API (prefix /api/v1). Authenticate with "
				+ "POST /api/v1/auth/login, then send "
				+ "Authorization: Bearer <token> on application endpoints.",
		version = "v1"))
@SecurityScheme(
		name = "bearerAuth",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT",
		description = "HS256 access token from POST /api/v1/auth/login")
public class OpenApiConfig {
}
