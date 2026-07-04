package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.MailerBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class MailerBuilderLocalBindConfigTest {

	@BeforeEach
	public void restoreOriginalStaticProperties() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void localBindAddressUsesSmtpPropertiesForStartTls() throws Exception {
		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587)
				.withTransportStrategy(TransportStrategy.SMTP_TLS)
				.withLocalBindAddress("192.0.2.20", 25256)
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.20");
			assertThat(session.getProperty("mail.smtp.localport")).isEqualTo("25256");
			assertThat(session.getProperty("mail.smtps.localaddress")).isNull();
			assertThat(session.getProperty("mail.smtps.localport")).isNull();
		}
	}

	@Test
	public void localBindAddressUsesSmtpsPropertiesForSmtps() throws Exception {
		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 465)
				.withTransportStrategy(TransportStrategy.SMTPS)
				.withLocalBindAddress("192.0.2.21")
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtps.localaddress")).isEqualTo("192.0.2.21");
			assertThat(session.getProperty("mail.smtps.localport")).isNull();
			assertThat(session.getProperty("mail.smtp.localaddress")).isNull();
		}
	}

	@Test
	public void localBindConfigPropertiesAreAppliedThroughTransportStrategy() throws Exception {
		ConfigLoader.loadProperties(new ByteArrayInputStream((
				"simplejavamail.smtp.localaddress=192.0.2.22\n" +
				"simplejavamail.smtp.localport=25257\n").getBytes()), false);

		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 465)
				.withTransportStrategy(TransportStrategy.SMTPS)
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtps.localaddress")).isEqualTo("192.0.2.22");
			assertThat(session.getProperty("mail.smtps.localport")).isEqualTo("25257");
			assertThat(session.getProperty("mail.smtp.localaddress")).isNull();
		}
	}

	@Test
	public void localBindAddressCanBeClearedAfterConfigDefaults() throws Exception {
		ConfigLoader.loadProperties(new ByteArrayInputStream((
				"simplejavamail.smtp.localaddress=192.0.2.23\n" +
				"simplejavamail.smtp.localport=25258\n").getBytes()), false);

		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587)
				.withTransportStrategy(TransportStrategy.SMTP_TLS)
				.clearLocalBindAddress()
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtp.localaddress")).isNull();
			assertThat(session.getProperty("mail.smtp.localport")).isNull();
		}
	}
}
