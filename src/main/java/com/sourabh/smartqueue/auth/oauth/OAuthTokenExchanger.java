package com.sourabh.smartqueue.auth.oauth;

/**
 * Authorization-code exchange with the provider token endpoint
 * (see the OAuth decision notes). A narrow seam: HTTP transport in,
 * raw ID token out. Failures are the caller's generic login failure
 * — never provider detail, never the code.
 */
public interface OAuthTokenExchanger {

	/**
	 * @return the provider's raw ID token for the code.
	 * @throws org.springframework.security.authentication.BadCredentialsException
	 * when the exchange fails for any reason.
	 */
	String exchangeCode(OauthProvider provider, String code, String redirectUri);
}
