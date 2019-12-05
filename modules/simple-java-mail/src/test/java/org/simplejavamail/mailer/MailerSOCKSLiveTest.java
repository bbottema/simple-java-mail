package org.simplejavamail.mailer;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;
import testutil.socks.server.impl.SocksServer;
import testutil.testrules.MimeMessageAndEnvelope;
import testutil.testrules.SmtpServerRule;
import testutil.testrules.TestSmtpServer;

import javax.mail.MessagingException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

public class MailerSOCKSLiveTest {
	private static final String SERVER_HOST = "localhost";
	private static final Integer SMTP_SERVER_PORT = 252;
	private static final Integer PROXY_SERVER_PORT = 253;

	private static final SocksServer SOCKS_SERVER = new SocksServer();

	private Mailer mailer;

	@Rule
	public final SmtpServerRule smtpServerRule = new SmtpServerRule(new TestSmtpServer(SERVER_HOST, SMTP_SERVER_PORT));

	@BeforeClass
	public static void startSocksServer() {
		SOCKS_SERVER.start(PROXY_SERVER_PORT);
	}

	@AfterClass
	public static void stopSocksServer() {
		SOCKS_SERVER.stop();
	}

	@Before
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		mailer = MailerBuilder
				.withSMTPServer(SERVER_HOST, SMTP_SERVER_PORT)
				.withProxy(SERVER_HOST, PROXY_SERVER_PORT, "moo", "letmein")
				.buildMailer();
	}

	@Test
	public void testSOCKSPassthrough()
			throws IOException, InterruptedException, ExecutionException, MessagingException {
		assertSendingEmail(EmailHelper.createDummyEmailBuilder(true, true, false, false), false);
	}

	private void assertSendingEmail(final EmailPopulatingBuilder originalEmailPopulatingBuilder, boolean async)
			throws MessagingException, ExecutionException, InterruptedException {
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