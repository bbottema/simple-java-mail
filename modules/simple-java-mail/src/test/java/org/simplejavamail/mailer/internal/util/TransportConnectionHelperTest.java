package org.simplejavamail.mailer.internal.util;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;

class TransportConnectionHelperTest {

	@Test
	void providerShouldBeResolvedForEveryPhysicalConnection() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		Session session = oauth2Session();
		session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY,
				(OAuth2AccessTokenProvider) () -> "token-" + calls.incrementAndGet());
		Transport firstTransport = mock(Transport.class);
		Transport secondTransport = mock(Transport.class);

		TransportConnectionHelper.connectTransport(firstTransport, session);
		TransportConnectionHelper.connectTransport(secondTransport, session);

		assertThat(calls).hasValue(2);
		verify(firstTransport).connect("user@example.com", "token-1");
		verify(secondTransport).connect("user@example.com", "token-2");
	}

	@Test
	void fixedTokenShouldRemainSupported() throws Exception {
		Session session = oauth2Session();
		session.getProperties().setProperty(OAUTH2_TOKEN_PROPERTY, "fixed-token");
		Transport transport = mock(Transport.class);

		TransportConnectionHelper.connectTransport(transport, session);

		verify(transport).connect("user@example.com", "fixed-token");
	}

	@Test
	void blankProviderTokenShouldFailClearly() {
		Session session = oauth2Session();
		session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> "  ");

		assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(mock(Transport.class), session))
				.isInstanceOf(MailException.class)
				.hasMessage("The OAuth2 access-token provider returned a blank access token");
	}

	@Test
	void nullProviderTokenShouldFailClearly() {
		Session session = oauth2Session();
		session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> null);

		assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(mock(Transport.class), session))
				.isInstanceOf(MailException.class)
				.hasMessage("The OAuth2 access-token provider returned a blank access token");
	}

	@Test
	void providerFailureShouldPreserveCause() {
		IllegalStateException cause = new IllegalStateException("refresh failed");
		Session session = oauth2Session();
		session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> {
			throw cause;
		});

		assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(mock(Transport.class), session))
				.isInstanceOf(MailException.class)
				.hasMessage("The OAuth2 access-token provider failed while obtaining an access token")
				.hasCause(cause);
	}

	@Test
	void authenticationFailureShouldPropagateWithoutRetry() throws Exception {
		Session session = oauth2Session();
		AtomicInteger calls = new AtomicInteger();
		session.getProperties().put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (OAuth2AccessTokenProvider) () -> {
			calls.incrementAndGet();
			return "rejected-token";
		});
		Transport transport = mock(Transport.class);
		AuthenticationFailedException failure = new AuthenticationFailedException("rejected");
		doThrow(failure).when(transport).connect("user@example.com", "rejected-token");

		assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(transport, session)).isSameAs(failure);
		assertThat(calls).hasValue(1);
	}

	private static Session oauth2Session() {
		Properties properties = new Properties();
		properties.setProperty("mail.smtp.user", "user@example.com");
		return Session.getInstance(properties);
	}
}
