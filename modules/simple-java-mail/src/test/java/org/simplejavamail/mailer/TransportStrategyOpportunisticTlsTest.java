package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class TransportStrategyOpportunisticTlsTest {

	@Test
	void disablingOpportunisticTlsOnlyChangesPlainSmtp() throws Exception {
		assertThat(sessionProperties(TransportStrategy.SMTP, false))
				.doesNotContainKeys("mail.smtp.starttls.enable", "mail.smtp.starttls.required");

		assertThat(sessionProperties(TransportStrategy.SMTP_TLS, false))
				.containsAllEntriesOf(TransportStrategy.SMTP_TLS.generateProperties());
		assertThat(sessionProperties(TransportStrategy.SMTP_OAUTH2, false))
				.containsAllEntriesOf(TransportStrategy.SMTP_OAUTH2.generateProperties());
		assertThat(sessionProperties(TransportStrategy.SMTPS, false))
				.containsAllEntriesOf(TransportStrategy.SMTPS.generateProperties());
	}

	private static Properties sessionProperties(final TransportStrategy strategy, final boolean opportunisticTls) throws Exception {
		final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoader.builder().load())
				.mailerBuilder()
				.withSMTPServerHost("localhost")
				.withTransportStrategy(strategy)
				.withOpportunisticTLS(opportunisticTls);
		if (strategy == TransportStrategy.SMTP_OAUTH2) {
			builder.withSMTPServerUsername("test-user")
					.withOAuth2AccessTokenProvider(() -> "test-token");
		}
		try (Mailer mailer = builder.buildMailer()) {
			return mailer.getSession().getProperties();
		}
	}
}
