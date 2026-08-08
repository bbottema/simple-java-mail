package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Supplies a current OAuth2 access token for SMTP authentication.
 * <p>
 * Simple Java Mail calls this provider synchronously whenever it opens or reconnects a physical SMTP connection. An
 * already-connected pooled transport can carry multiple messages without another call. Implementations must therefore be
 * thread-safe and should return cached tokens cheaply while acquiring or refreshing them when needed.
 * <p>
 * Implementations own token acquisition, expiry handling, caching and refresh and expose only the resulting access token.
 */
@FunctionalInterface
public interface OAuth2AccessTokenProvider extends Supplier<String> {

	/**
	 * Returns a current, nonblank SMTP OAuth2 access token.
	 */
	@NotNull
	String getAccessToken();

	/**
	 * Allows this provider to be consumed by lower-level connection integrations as a standard JDK supplier.
	 */
	@Override
	@NotNull
	default String get() {
		return getAccessToken();
	}
}
