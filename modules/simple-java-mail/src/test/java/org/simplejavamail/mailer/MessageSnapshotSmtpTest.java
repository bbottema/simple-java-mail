package org.simplejavamail.mailer;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.converter.EmailConverter;
import org.subethamail.wiser.WiserMessage;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.SmtpServerExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class MessageSnapshotSmtpTest {

	@RegisterExtension
	final SmtpServerExtension smtpServer = new SmtpServerExtension(0, null, null, false);

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void copyingKeepsTestDeliverySeparateFromVisibleRecipients(final boolean asynchronous) throws Exception {
		final Email original = mail.emailBuilder().startingBlank()
				.from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Visible To", "to@example.org"))
				.withRecipients(mail.recipientBuilder().cc("Visible Cc", "cc@example.org"))
				.withRecipients(mail.recipientBuilder().bcc("Hidden Bcc", "bcc@example.org"))
				.withOverrideReceivers(mail.recipientBuilder().to("Test inbox", "test-inbox@example.org"))
				.withSubject("Original subject")
				.withPlainText("Synthetic review data")
				.buildEmail();
		final Email copied = mail.emailBuilder().copying(original).withSubject("Copied subject").buildEmail();

		try (Mailer mailer = smtpMailer()) {
			final MailSubmissionReceipt receipt = asynchronous
					? mailer.async().sendMail(copied).getCompletion().get(20, TimeUnit.SECONDS)
					: mailer.sync().sendMail(copied);
			assertThat(receipt.getAcceptedRecipients()).containsExactly("test-inbox@example.org");
		}

		assertThat(smtpServer.getMessages()).singleElement().satisfies(received -> {
			assertThat(received.getEnvelopeReceiver()).isEqualTo("test-inbox@example.org");
			assertThat(received.getMimeMessage().getRecipients(RecipientType.TO)).extracting(Object::toString)
					.containsExactly("Visible To <to@example.org>");
			assertThat(received.getMimeMessage().getRecipients(RecipientType.CC)).extracting(Object::toString)
					.containsExactly("Visible Cc <cc@example.org>");
			assertThat(received.getMimeMessage().getRecipients(RecipientType.BCC)).isNull();
			assertThat(received.getMimeMessage().getSubject()).isEqualTo("Copied subject");
		});
		assertThat(copied.getRecipients()).containsExactlyElementsOf(original.getRecipients());
	}

	@Test
	void changesToBuilderInputAndGetterCopiesDoNotChangeTheSubmittedMessage() throws Exception {
		final byte[] attachment = "original attachment".getBytes(US_ASCII);
		final Recipient actual = mail.recipientBuilder().to("Actual", "actual@example.org");
		final Recipient changed = mail.recipientBuilder().to("Changed", "changed@example.org");
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Visible", "visible@example.org"))
				.withOverrideReceivers(actual).withReplyTo("reply@example.org").withHeader("X-Route", "original")
				.withSubject("Snapshot").withPlainText("body").withAttachment("snapshot.txt", attachment, "text/plain");
		final Email snapshot = builder.buildEmail();
		builder.getHeaders().get("X-Route").clear();
		builder.getOverrideReceivers().clear();
		builder.getReplyToRecipients().clear();
		builder.clearOverrideReceivers().withOverrideReceivers(changed).withHeader("X-Route", "changed", true)
				.withReplyTo("changed-reply@example.org");
		Arrays.fill(attachment, (byte) 'x');

		try (Mailer mailer = smtpMailer()) {
			assertThat(mailer.sync().sendMail(snapshot).getAcceptedRecipients()).containsExactly("actual@example.org");
		}

		assertThat(smtpServer.getMessages()).hasSize(1);
		final WiserMessage received = smtpServer.getMessages().get(0);
		assertThat(received.getEnvelopeReceiver()).isEqualTo("actual@example.org");
		assertThat(received.getMimeMessage().getHeader("X-Route")).containsExactly("original");
		final Email parsed = EmailConverter.mimeMessageToEmail(received.getMimeMessage());
		assertThat(parsed.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("reply@example.org");
		assertThat(parsed.getAttachments().get(0).readAllBytes()).containsExactly("original attachment".getBytes(US_ASCII));
	}

	@Test
	void concurrentSendsReuseOneMessageSnapshotWithoutBuilderContamination() throws Exception {
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(mail.recipientBuilder().to("Actual", "actual@example.org"))
				.fixingMessageId("<snapshot-concurrent@example.org>").withSubject("Shared snapshot")
				.withPlainText("body").withHeader("X-Route", "original");
		final Email snapshot = builder.buildEmail();
		builder.clearRecipients().withRecipients(mail.recipientBuilder().to("Changed", "changed@example.org"))
				.withHeader("X-Route", "changed", true);

		final List<CompletableFuture<MailSubmissionReceipt>> completions = new ArrayList<>();
		try (Mailer mailer = smtpMailer()) {
			for (int index = 0; index < 8; index++) {
				completions.add(mailer.async().sendMail(snapshot).getCompletion());
			}
			CompletableFuture.allOf(completions.toArray(new CompletableFuture<?>[0])).get(20, TimeUnit.SECONDS);
		}
		assertThat(completions).allSatisfy(completion ->
				assertThat(completion.join().getAcceptedRecipients()).containsExactly("actual@example.org"));
		assertThat(smtpServer.getMessages()).hasSize(8);
		for (final WiserMessage received : smtpServer.getMessages()) {
			assertThat(received.getEnvelopeReceiver()).isEqualTo("actual@example.org");
			assertThat(received.getMimeMessage().getHeader("X-Route")).containsExactly("original");
			assertThat(received.getMimeMessage().getMessageID()).isEqualTo("<snapshot-concurrent@example.org>");
		}
	}

	private Mailer smtpMailer() {
		return mail.mailerBuilder().withSMTPServer("localhost", smtpServer.getPort()).withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(4).build())
				.withConnectionPoolConfig(ConnectionPoolConfig.builder().withClusterKey(UUID.randomUUID()).withCoreSize(0).withMaxSize(4).build()).buildMailer();
	}
}
