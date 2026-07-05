package org.simplejavamail.mailer;

import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.testrules.MimeMessageAndEnvelope;
import testutil.testrules.SmtpServerExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

public class MailerSOCKSLiveTest {
	private static final Integer SMTP_SERVER_PORT = 252;

	@RegisterExtension
	static SmtpServerExtension smtpServerRule = new SmtpServerExtension(SMTP_SERVER_PORT, null, null);

	private final SyncSocksServer socksServer = new SyncSocksServer();
	private RunningSocksServer proxyServer;

	@BeforeEach
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		proxyServer = socksServer.startServer(0);
	}

	@AfterEach
	public void tearDown() {
		if (proxyServer != null) {
			proxyServer.stop();
			proxyServer = null;
		}
		smtpServerRule.getWiser().stop();
	}

	@Test
	public void testSOCKSPassthrough_Anonymous() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", proxyServer.getPort())
				.buildMailer();

		assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
	}

	@Test
	// NOTE: this doesn't really trigger authentication because the embedded SOCKS server doesn't support it,
	// but it triggers the code on the mailer side, which should not produce errors either
	public void testSOCKSPassthrough_Authenticating() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", proxyServer.getPort(), "username", "password")
				.buildMailer();

		assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
	}

	private void assertSendingEmail(final Mailer mailer, final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean async) throws Exception {
		Email originalEmail = originalEmailPopulatingBuilder.buildEmail();

		if (!async) {
			mailer.sendMail(originalEmail);
		} else {
			verifyNonnullOrEmpty(mailer.sendMail(originalEmail, async)).get();
		}
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerRule.getOnlyMessage();
		assertThat(receivedMimeMessage.getMimeMessage().getMessageID()).isEqualTo(originalEmail.getId());
	}
}
