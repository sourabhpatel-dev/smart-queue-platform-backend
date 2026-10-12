package com.sourabh.smartqueue.auth.api;

import java.util.List;

/**
 * Enabled social providers for the login UI (names only — client
 * secrets never leave the server, and disabled providers are simply
 * absent so the UI renders no dead buttons).
 */
public record OAuthProvidersResponse(List<String> providers) {
}
