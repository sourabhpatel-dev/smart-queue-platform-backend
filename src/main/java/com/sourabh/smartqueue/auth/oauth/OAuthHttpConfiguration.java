package com.sourabh.smartqueue.auth.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP wiring for provider token exchange (see the OAuth decision
 * notes). Explicit {@code @Bean} methods instead of component
 * scanning: the exchanger's test-seam constructor would otherwise
 * make injection ambiguous, and the builder's absence from Boot
 * auto-configuration here is handled in one visible place rather
 * than discovered at startup.
 */
@Configuration
public class OAuthHttpConfiguration {

	@Bean
	RestClient.Builder oauthRestClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	OAuthTokenExchanger oauthTokenExchanger(RestClient.Builder oauthRestClientBuilder,
			OAuthProperties properties,
			AppleClientSecretFactory secrets) {
		return new HttpOAuthTokenExchanger(oauthRestClientBuilder, properties, secrets);
	}
}
