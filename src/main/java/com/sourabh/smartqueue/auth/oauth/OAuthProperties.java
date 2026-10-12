package com.sourabh.smartqueue.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;

/**
 * Social login configuration (see the OAuth decision notes).
 *
 * <p>Every provider is disabled unless its client id is configured —
 * there are no live credentials in this repository, no defaults that
 * phone home, and the frontend renders no buttons for disabled
 * providers. All values come from environment
 * ({@code APP_OAUTH_*}); nothing here is ever logged or returned to
 * clients (the public config endpoint exposes enabled flags only).
 * Static configuration: changes require a restart.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthProperties {

	private final Google google = new Google();

	private final Apple apple = new Apple();

	public Google getGoogle() {
		return google;
	}

	public Apple getApple() {
		return apple;
	}

	public static class Google {

		/**
		 * OAuth client id from the Google Cloud console. Blank means
		 * Google sign-in is disabled.
		 */
		private String clientId;

		/** OAuth client secret (code exchange only, never exposed). */
		private String clientSecret;

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public boolean isEnabled() {
			return clientId != null && !clientId.isBlank();
		}
	}

	public static class Apple {

		/**
		 * Services ID (the OAuth client_id) from Apple Developer.
		 * Blank means Apple sign-in is disabled.
		 */
		private String clientId;

		/** The 10-character Team ID issuing the client secret. */
		private String teamId;

		/** The Key ID of the Sign-in-with-Apple private key. */
		private String keyId;

		/**
		 * The .p8 private key contents (PKCS#8 PEM). Blank means
		 * Apple sign-in is disabled — without it no client secret
		 * can be minted.
		 */
		private String privateKey;

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getTeamId() {
			return teamId;
		}

		public void setTeamId(String teamId) {
			this.teamId = teamId;
		}

		public String getKeyId() {
			return keyId;
		}

		public void setKeyId(String keyId) {
			this.keyId = keyId;
		}

		public String getPrivateKey() {
			return privateKey;
		}

		public void setPrivateKey(String privateKey) {
			this.privateKey = privateKey;
		}

		public boolean isEnabled() {
			return clientId != null && !clientId.isBlank()
					&& teamId != null && !teamId.isBlank()
					&& keyId != null && !keyId.isBlank()
					&& privateKey != null && !privateKey.isBlank();
		}
	}

	@AssertTrue(message = "Google OAuth needs a client secret when its client id is set")
	public boolean isGoogleCoherent() {
		return !google.isEnabled()
				|| (google.getClientSecret() != null && !google.getClientSecret().isBlank());
	}
}
