package org.simplejavamail.mailer;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.SmtpServerExtension;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyCustomHeaderSmtpTest {

	@RegisterExtension
	final SmtpServerExtension smtpServer = new SmtpServerExtension(0, null, null, false);

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void ordinarySendsKeepEmptyFieldOccurrencesAndStoredNullMetadata(final boolean asynchronous) throws Exception {
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
		final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
				.withPlainText("body").withHeader("X-Example", null).withHeader("x-example", "")
				.withHeader("X-Example", "last").buildEmail();

		try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", smtpServer.getPort())
				.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClusterKey(UUID.randomUUID()).build()).buildMailer()) {
			if (asynchronous) {
				assertThat(mailer.async().sendMail(email).getCompletion().get(20, TimeUnit.SECONDS).getAcceptedRecipients())
						.containsExactly("reader@example.org");
			} else {
				assertThat(mailer.sync().sendMail(email).getAcceptedRecipients()).containsExactly("reader@example.org");
			}
		}

		assertThat(smtpServer.getMessages()).singleElement().satisfies(received ->
				assertThat(received.getMimeMessage().getHeader("X-Example")).containsExactly("", "", "last"));
		assertThat(email.getHeaders().get("X-Example")).containsExactly(null, "", "last");
	}
}
