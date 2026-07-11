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

	@Test
	public void smtpClientHostnameUsesSmtpPropertiesForStartTls() throws Exception {
		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587)
				.withTransportStrategy(TransportStrategy.SMTP_TLS)
				.withSmtpClientHostname("orders-service.prod.example.com")
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isEqualTo("orders-service.prod.example.com");
			assertThat(session.getProperty("mail.smtp.localhost")).isEqualTo("orders-service.prod.example.com");
			assertThat(session.getProperty("mail.smtps.localhost")).isNull();
		}
	}

	@Test
	public void smtpClientHostnameUsesSmtpsPropertiesForSmtps() throws Exception {
		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 465)
				.withTransportStrategy(TransportStrategy.SMTPS)
				.withSmtpClientHostname("bulk-mailer.corp.example.com")
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtps.localhost")).isEqualTo("bulk-mailer.corp.example.com");
			assertThat(session.getProperty("mail.smtp.localhost")).isNull();
		}
	}

	@Test
	public void smtpClientHostnameConfigPropertyIsAppliedThroughTransportStrategy() throws Exception {
		ConfigLoader.loadProperties(new ByteArrayInputStream((
				"simplejavamail.smtp.clienthostname=relay-identity.example.com\n").getBytes()), false);

		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 465)
				.withTransportStrategy(TransportStrategy.SMTPS)
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(session.getProperty("mail.smtps.localhost")).isEqualTo("relay-identity.example.com");
			assertThat(session.getProperty("mail.smtp.localhost")).isNull();
		}
	}

	@Test
	public void smtpClientHostnameCanBeClearedAfterConfigDefaults() throws Exception {
		ConfigLoader.loadProperties(new ByteArrayInputStream((
				"simplejavamail.smtp.clienthostname=relay-identity.example.com\n").getBytes()), false);

		try (Mailer mailer = MailerBuilder
				.withSMTPServer("smtp.example.com", 587)
				.withTransportStrategy(TransportStrategy.SMTP_TLS)
				.clearSmtpClientHostname()
				.buildMailer()) {
			Session session = mailer.getSession();

			assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isNull();
			assertThat(session.getProperty("mail.smtp.localhost")).isNull();
			assertThat(session.getProperty("mail.smtps.localhost")).isNull();
		}
	}
}
