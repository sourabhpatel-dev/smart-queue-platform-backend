package com.sourabh.smartqueue.auth.oauth;

/**
 * Supported social identity providers. The wire value is the enum
 * name ({@code GOOGLE}, {@code APPLE}) — persisted in
 * {@code oauth_identity.provider}, so renaming a constant is a
 * migration, not a refactor.
 */
public enum OauthProvider {

	GOOGLE,

	APPLE
}
