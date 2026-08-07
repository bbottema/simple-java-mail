package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.Properties;
import java.util.function.Supplier;

import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;

/**
 * Keeps OAuth2 credential validation separate from connection-time token resolution so providers remain lazy.
 */
public final class OAuth2AccessTokenResolver {

	@Nullable
	static Supplier<String> validateConfiguration(@NotNull Session session,
			@NotNull Properties additionalProperties,
			@Nullable TransportStrategy transportStrategy,
			@Nullable OAuth2AccessTokenProvider explicitProvider) {
		final boolean fixedTokenConfigured = session.getProperties().containsKey(OAUTH2_TOKEN_PROPERTY)
				|| additionalProperties.containsKey(OAUTH2_TOKEN_PROPERTY);
		final Object sessionProvider = session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY);
		final Object additionalProvider = additionalProperties.get(OAUTH2_TOKEN_PROVIDER_PROPERTY);
		final Object configuredProvider = selectProvider(explicitProvider, sessionProvider, additionalProvider);
		final boolean oauth2Transport = transportStrategy == SMTP_OAUTH2
				|| (transportStrategy == null && hasOAuth2AuthenticationMechanism(session));

		if (configuredProvider != null && !(configuredProvider instanceof Supplier)) {
			throw new MailerException(MailerException.INVALID_OAUTH2_TOKEN_PROVIDER);
		}
		if (configuredProvider != null && !oauth2Transport) {
			throw new MailerException(MailerException.OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY);
		}
		if (oauth2Transport && fixedTokenConfigured && configuredProvider != null) {
			throw new MailerException(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
		}
		if (oauth2Transport && !fixedTokenConfigured && configuredProvider == null) {
			throw new MailerException(MailerException.MISSING_OAUTH2_TOKEN);
		}
		@SuppressWarnings("unchecked")
		final Supplier<String> provider = (Supplier<String>) configuredProvider;
		return provider;
	}

	@Nullable
	public static String resolveAccessToken(@NotNull Session session) {
		final Object provider = session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY);
		final String fixedToken = session.getProperties().getProperty(OAUTH2_TOKEN_PROPERTY);
		if (provider != null && fixedToken != null) {
			throw new MailerException(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
		}
		if (provider == null) {
			return fixedToken;
		}
		if (!(provider instanceof Supplier)) {
			throw new MailerException(MailerException.INVALID_OAUTH2_TOKEN_PROVIDER);
		}

		final Object providedToken;
		try {
			providedToken = ((Supplier<?>) provider).get();
		} catch (RuntimeException e) {
			throw new MailerException(MailerException.OAUTH2_TOKEN_PROVIDER_FAILED, e);
		}
		if (!(providedToken instanceof String) || ((String) providedToken).trim().isEmpty()) {
			throw new MailerException(MailerException.BLANK_OAUTH2_TOKEN_FROM_PROVIDER);
		}
		return (String) providedToken;
	}

	@Nullable
	private static Object selectProvider(@Nullable Object explicitProvider,
			@Nullable Object sessionProvider,
			@Nullable Object additionalProvider) {
		Object selected = explicitProvider;
		selected = selectProvider(selected, sessionProvider);
		return selectProvider(selected, additionalProvider);
	}

	@Nullable
	private static Object selectProvider(@Nullable Object selected, @Nullable Object candidate) {
		if (candidate == null) {
			return selected;
		}
		if (selected != null && selected != candidate) {
			throw new MailerException(MailerException.MULTIPLE_OAUTH2_TOKEN_PROVIDERS);
		}
		return candidate;
	}

	private static boolean hasOAuth2AuthenticationMechanism(@NotNull Session session) {
		final String authenticationMechanisms = session.getProperty("mail.smtp.auth.mechanisms");
		if (authenticationMechanisms == null) {
			return false;
		}
		for (String mechanism : authenticationMechanisms.split("[,\\s]+")) {
			if ("XOAUTH2".equalsIgnoreCase(mechanism)) {
				return true;
			}
		}
		return false;
	}

	private OAuth2AccessTokenResolver() {
	}
}
