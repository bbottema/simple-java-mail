package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
		return mail.mailerBuilder().withSMTPServer("localhost", smtpServer.getPort()).withThreadPoolSize(4)
				.withClusterKey(UUID.randomUUID()).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(4).buildMailer();
	}
}
