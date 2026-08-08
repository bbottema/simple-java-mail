package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.MailerBuilder;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;

class OAuth2AccessTokenProviderTest {

	@Test
	void regularMailerShouldStoreProviderWithoutResolvingIt() {
		AtomicInteger calls = new AtomicInteger();
		OAuth2AccessTokenProvider provider = () -> "token-" + calls.incrementAndGet();

		Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587, "user@example.com", null)
				.withTransportStrategy(SMTP_OAUTH2)
				.withOAuth2AccessTokenProvider(provider)
				.buildMailer();

		assertThat(calls).hasValue(0);
		assertThat(mailer.getSession().getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
		assertThat(mailer.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(provider);
	}

	@Test
	void convenienceEntryPointShouldConfigureProvider() {
		OAuth2AccessTokenProvider provider = () -> "token";

		Mailer mailer = MailerBuilder
				.withOAuth2AccessTokenProvider(provider)
				.withSMTPServer("smtp.example.com", 587, "user@example.com", null)
				.withTransportStrategy(SMTP_OAUTH2)
				.buildMailer();

		assertThat(mailer.getSession().getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
	}

	@Test
	void providerShouldStayOutOfOperationalConfigDiagnostics() {
		OAuth2AccessTokenProvider provider = new OAuth2AccessTokenProvider() {
			@Override
			public String getAccessToken() {
				return "token";
			}

			@Override
			public String toString() {
				return "provider-diagnostic-marker";
			}
		};

		Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587, "user@example.com", null)
				.withTransportStrategy(SMTP_OAUTH2)
				.withOAuth2AccessTokenProvider(provider)
				.buildMailer();

		assertThat(mailer.getOperationalConfig().toString()).doesNotContain("provider-diagnostic-marker");
	}

	@Test
	void customSessionMailerShouldAcceptProvider() {
		Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
		properties.setProperty("mail.smtp.user", "user@example.com");
		Session session = Session.getInstance(properties);
		OAuth2AccessTokenProvider provider = () -> "token";

		Mailer mailer = MailerBuilder.usingSession(session)
				.withOAuth2AccessTokenProvider(provider)
				.buildMailer();

		assertThat(mailer.getSession()).isSameAs(session);
		assertThat(session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
	}

	@Test
	void customSessionProviderShouldRequireOAuth2AuthenticationMechanism() {
		Session session = Session.getInstance(new Properties());

		assertThatThrownBy(() -> MailerBuilder.usingSession(session)
				.withOAuth2AccessTokenProvider(() -> "token")
				.buildMailer())
				.isInstanceOf(MailException.class)
				.hasMessage(MailerException.OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY);
	}

	@Test
	void fixedTokenAndProviderShouldBeRejected() {
		assertThatThrownBy(() -> MailerBuilder
				.withSMTPServer("smtp.example.com", 587, "user@example.com", "fixed-token")
				.withTransportStrategy(SMTP_OAUTH2)
				.withOAuth2AccessTokenProvider(() -> "provided-token")
				.buildMailer())
				.isInstanceOf(MailException.class)
				.hasMessage(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
	}

	@Test
	void providerShouldRequireOAuth2TransportStrategy() {
		assertThatThrownBy(() -> MailerBuilder
				.withSMTPServer("smtp.example.com", 587, "user@example.com", null)
				.withTransportStrategy(TransportStrategy.SMTP_TLS)
				.withOAuth2AccessTokenProvider(() -> "provided-token")
				.buildMailer())
				.isInstanceOf(MailException.class)
				.hasMessage(MailerException.OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY);
	}
}
