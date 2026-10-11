package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.ConnectionSetupFailure;
import org.simplejavamail.mailer.internal.OAuth2AccessTokenResolver;

/**
	 * Opens Jakarta Mail transports. OAuth2 requires the explicit username-and-token form because Jakarta Mail's Authenticator cannot provide a refreshed token.
	 *
	 * @see <a href="https://eclipse-ee4j.github.io/angus-mail/OAuth2">Angus Mail OAuth2 support</a>
 */
public class TransportConnectionHelper {

	public static void connectTransport(final Transport transport, final Session session) throws MessagingException {
		if (usesOAuth2Authentication(session)) {
			connectWithOAuth2Token(transport, session, OAuth2AccessTokenResolver.resolveAccessToken(session));
        } else {
            transport.connect();
        }
    }

    /** Keeps credential-provider errors local; only the subsequent physical setup has connection-failure evidence. */
    public static void connectForSending(final Transport transport, final Session session) {
		final boolean usesOAuth2Authentication = usesOAuth2Authentication(session);
		final String accessToken = usesOAuth2Authentication ? OAuth2AccessTokenResolver.resolveAccessToken(session) : null;
        try {
			if (usesOAuth2Authentication) {
				connectWithOAuth2Token(transport, session, accessToken);
            } else {
                transport.connect();
            }
        } catch (MessagingException failure) {
            throw new ConnectionSetupFailure(failure);
        }
    }

	private static boolean usesOAuth2Authentication(final Session session) {
		return session.getProperties().containsKey(TransportStrategy.OAUTH2_TOKEN_PROPERTY)
				|| session.getProperties().containsKey(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY);
	}

	private static void connectWithOAuth2Token(final Transport transport, final Session session, final String accessToken) throws MessagingException {
		final String username = session.getProperty(TransportStrategy.SMTP_TLS.propertyNameUsername());
		transport.connect(username, accessToken);
	}
}
