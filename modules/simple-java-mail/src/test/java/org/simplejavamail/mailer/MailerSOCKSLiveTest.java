package org.simplejavamail.mailer;

import org.bbottema.javasocksproxyserver.junit.SockServerRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.testrules.MimeMessageAndEnvelope;
import testutil.testrules.SmtpServerRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

public class MailerSOCKSLiveTest {
	private static final Integer SMTP_SERVER_PORT = 252;
	private static final Integer PROXY_SERVER_PORT = 253;

	@Rule
	public final SmtpServerRule smtpServerRule = new SmtpServerRule(SMTP_SERVER_PORT);
	@ClassRule
	public static final SockServerRule sockServerRule = new SockServerRule(PROXY_SERVER_PORT);

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
	}

	@Test
	public void testSOCKSPassthrough_Anonymous() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", PROXY_SERVER_PORT)
				.buildMailer();

		assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false), false);
	}

	@Test
	// NOTE: this doesn't really trigger authentication because the embedded SOCKS server doesn't support it,
	// but it triggers the code on the mailer side, which should not produce errors either
	public void testSOCKSPassthrough_Authenticating() throws Exception {
		Mailer mailer = MailerBuilder
				.withSMTPServer("localhost", SMTP_SERVER_PORT)
				.withProxy("localhost", PROXY_SERVER_PORT, "username", "password")
				.buildMailer();

		assertSendingEmail(mailer, EmailHelper.createDummyEmailBuilder(true, true, false, false, false), false);
	}

	private void assertSendingEmail(final Mailer mailer, final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean async) throws Exception {
		Email originalEmail = originalEmailPopulatingBuilder.buildEmail();

		if (!async) {
			mailer.sendMail(originalEmail);
		} else {
			assumeNonNull(mailer.sendMail(originalEmail, async)).getFuture().get();
		}
		MimeMessageAndEnvelope receivedMimeMessage = smtpServerRule.getOnlyMessage();
		assertThat(receivedMimeMessage.getMimeMessage().getMessageID()).isEqualTo(originalEmail.getId());
	}
}