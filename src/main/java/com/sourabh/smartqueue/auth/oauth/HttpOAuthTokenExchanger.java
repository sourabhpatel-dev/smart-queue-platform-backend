package com.sourabh.smartqueue.auth.oauth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.sourabh.smartqueue.auth.AuthService;

/**
 * Form-POST code exchange (see the OAuth decision notes). Token URLs
 * travel as constructor arguments (production wires the provider
 * constants; tests wire a local stub server) — never hardcoded
 * inside the call, never derived from client input. Client secrets
 * come from configuration; the authorization code comes from the
 * request; neither is ever logged. Wired explicitly by
 * {@link OAuthHttpConfiguration} (not component-scanned: the test
 * seam constructor would make injection ambiguous).
 */
public class HttpOAuthTokenExchanger implements OAuthTokenExchanger {

	static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

	static final String APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";

	private static final Logger log = LoggerFactory.getLogger(HttpOAuthTokenExchanger.class);

	private final RestClient http;
	private final OAuthProperties properties;
	private final AppleClientSecretFactory secrets;
	private final String googleTokenUrl;
	private final String appleTokenUrl;

	public HttpOAuthTokenExchanger(RestClient.Builder rest, OAuthProperties properties,
			AppleClientSecretFactory secrets) {
		this(rest.build(), properties, secrets, GOOGLE_TOKEN_URL, APPLE_TOKEN_URL);
	}

	/**
	 * Test seam: explicit token URLs so the transport is proven
	 * against a stub server. Production uses the provider constants
	 * via the primary constructor.
	 */
	public HttpOAuthTokenExchanger(RestClient http, OAuthProperties properties,
			AppleClientSecretFactory secrets, String googleTokenUrl, String appleTokenUrl) {
		this.http = http;
		this.properties = properties;
		this.secrets = secrets;
		this.googleTokenUrl = googleTokenUrl;
		this.appleTokenUrl = appleTokenUrl;
	}

	@Override
	@SuppressWarnings("unchecked")
	public String exchangeCode(OauthProvider provider, String code, String redirectUri) {
		if (code == null || code.isBlank() || redirectUri == null || redirectUri.isBlank()) {
			throw new IllegalArgumentException("code and redirect URI are required");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		form.add("grant_type", "authorization_code");
		String tokenUrl;
		if (provider == OauthProvider.GOOGLE) {
			tokenUrl = googleTokenUrl;
			form.add("client_id", properties.getGoogle().getClientId());
			form.add("client_secret", properties.getGoogle().getClientSecret());
		} else {
			tokenUrl = appleTokenUrl;
			form.add("client_id", properties.getApple().getClientId());
			form.add("client_secret", secrets.mint());
		}
		try {
			Map<String, Object> body = http.post()
					.uri(tokenUrl)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(Map.class);
			Object idToken = body == null ? null : body.get("id_token");
			if (!(idToken instanceof String token) || token.isBlank()) {
				throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
			}
			return token;
		} catch (BadCredentialsException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("oauth code exchange failed: provider={}", provider);
			throw new BadCredentialsException(AuthService.GENERIC_FAILURE);
		}
	}
}
