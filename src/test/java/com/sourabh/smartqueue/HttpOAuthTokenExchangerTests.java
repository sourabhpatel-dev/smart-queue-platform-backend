package com.sourabh.smartqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.client.RestClient;

import com.sourabh.smartqueue.auth.oauth.AppleClientSecretFactory;
import com.sourabh.smartqueue.auth.oauth.HttpOAuthTokenExchanger;
import com.sourabh.smartqueue.auth.oauth.OAuthProperties;
import com.sourabh.smartqueue.auth.oauth.OauthProvider;
import com.sun.net.httpserver.HttpServer;

/**
 * Token-exchange transport (see the OAuth decision notes): form
 * shape, id_token extraction, and generic failures — against a JDK
 * stub server, no provider network. Policy (what the token means)
 * is tested separately in {@link OAuthServiceTests}.
 */
class HttpOAuthTokenExchangerTests {

	private HttpServer stub;
	private String baseUrl;
	private String lastBody = "";

	@BeforeEach
	void startStub() throws IOException {
		stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
		stub.createContext("/token", exchange -> {
			lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String response = "{\"id_token\":\"stub-id-token\",\"token_type\":\"Bearer\"}";
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		stub.createContext("/missing", exchange -> {
			byte[] bytes = "{\"token_type\":\"Bearer\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		stub.createContext("/denied", exchange -> {
			exchange.sendResponseHeaders(400, -1);
		});
		stub.start();
	}

	@AfterEach
	void stopStub() {
		stub.stop(0);
	}

	private HttpOAuthTokenExchanger exchanger() {
		OAuthProperties properties = new OAuthProperties();
		properties.getGoogle().setClientId("test-client");
		properties.getGoogle().setClientSecret("test-secret");
		return new HttpOAuthTokenExchanger(RestClient.builder().build(), properties,
				new AppleClientSecretFactory(properties,
						java.time.Clock.systemUTC()),
				baseUrl + "/token", baseUrl + "/token");
	}

	@Test
	void successfulExchangeReturnsIdToken() {
		String idToken = exchanger().exchangeCode(OauthProvider.GOOGLE, "auth-code",
				"https://app.example/callback");

		assertThat(idToken).isEqualTo("stub-id-token");
		assertThat(lastBody).contains("code=auth-code");
		assertThat(lastBody).contains("grant_type=authorization_code");
		assertThat(lastBody).contains("client_id=test-client");
		// Server-to-server only: the secret travels in the token
		// form, never in logs or API responses (see OAuthService).
		assertThat(lastBody).contains("client_secret=test-secret");
		assertThat(lastBody).contains("redirect_uri=");
	}

	@Test
	void missingIdTokenIsGenericFailure() {
		HttpOAuthTokenExchanger exchanger = new HttpOAuthTokenExchanger(
				RestClient.builder().build(), new OAuthProperties(),
				new AppleClientSecretFactory(new OAuthProperties(),
						java.time.Clock.systemUTC()),
				baseUrl + "/missing", baseUrl + "/missing");

		assertThatThrownBy(() -> exchanger.exchangeCode(OauthProvider.GOOGLE, "code",
				"https://app.example/callback"))
				.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void providerDenialIsGenericFailure() {
		OAuthProperties properties = new OAuthProperties();
		properties.getGoogle().setClientId("test-client");
		properties.getGoogle().setClientSecret("test-secret");
		HttpOAuthTokenExchanger exchanger = new HttpOAuthTokenExchanger(
				RestClient.builder().build(), properties,
				new AppleClientSecretFactory(new OAuthProperties(),
						java.time.Clock.systemUTC()),
				baseUrl + "/denied", baseUrl + "/denied");

		assertThatThrownBy(() -> exchanger.exchangeCode(OauthProvider.GOOGLE, "code",
				"https://app.example/callback"))
				.isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void blankInputsAreRejectedBeforeNetwork() {
		HttpOAuthTokenExchanger exchanger = exchanger();

		assertThatThrownBy(() -> exchanger.exchangeCode(OauthProvider.GOOGLE, " ",
				"https://app.example/callback"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
