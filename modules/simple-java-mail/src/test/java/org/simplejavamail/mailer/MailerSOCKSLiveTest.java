package org.simplejavamail.mailer;

import org.bbottema.javasocksproxyserver.junit.SockServerExtension;
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
	private static final Integer PROXY_SERVER_PORT = 253;

	@RegisterExtension
	static SmtpServerExtension smtpServerRule = new SmtpServerExtension(SMTP_SERVER_PORT, null, null);
	@RegisterExtension
	static SockServerExtension sockServerRule = new SockServerExtension(PROXY_SERVER_PORT);

	@BeforeEach
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@AfterEach
	public void tearDown() {
		smtpServerRule.getWiser().stop();
	}

	@Test
	public void testSOCKSPassthrough_Anonymous() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", PROXY_SERVER_PORT)
				.buildMailer();

		assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false), false);
	}

	@Test
	// NOTE: this doesn't really trigger authentication because the embedded SOCKS server doesn't support it,
	// but it triggers the code on the mailer side, which should not produce errors either
	public void testSOCKSPassthrough_Authenticating() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", PROXY_SERVER_PORT, "username", "password")
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