package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static jakarta.mail.Message.RecipientType.TO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.expectCommand;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.readMessage;

/** Fills protocol-boundary gaps; the existing submission, deadline and pooled tests retain their detailed outcome assertions. */
@Timeout(35)
class SmtpProtocolConformanceTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
	private static final String RECIPIENT = "recipient@example.test";

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void fragmentedMultilineRepliesAndFailedQuitCannotUndoAcceptance(final boolean asynchronous) throws Exception {
		final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
		try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
			peer.replyInFragments("220 localhost synthetic peer", 1);
			peer.expect("EHLO probe.example.test");
			peer.replyInFragments("250-localhost\r\n250-XUNKNOWN fixture\r\n250 8BITMIME", 2);
			acceptMessage(peer, MAIL_FROM, "", RECIPIENT);
			expectCommand(peer, "QUIT");
			peer.reply("421 deliberately unavailable during cleanup");
		}); Mailer mailer = builder(server).withMailSendObserver(observed::add).buildMailer()) {
			final MailSubmissionReceipt receipt = asynchronous ? mailer.async().sendMail(email()).getCompletion().get(10, SECONDS)
					: mailer.sync().sendMail(email());
			assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
			assertThat(observed).singleElement().satisfies(outcome -> assertThat(outcome.getSubmissionReceipt()).containsSame(receipt));
		}
	}

	@ParameterizedTest
	@EnumSource(RejectionPoint.class)
	void explicitRejectionRemainsRejectedEvenWhenResetAlsoFails(final RejectionPoint point) throws Exception {
		final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
		try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> rejectAt(peer, point));
			 Mailer mailer = builder(server).withMailSendObserver(observed::add).buildMailer()) {
			final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
			assertThat(failure).isInstanceOf(MailSubmissionException.class);
			final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(REJECTED);
			assertThat(receipt.getAcceptedRecipients()).isEmpty();
			assertThat(observed).singleElement().satisfies(outcome -> {
				assertThat(outcome.getFailure()).containsSame(failure);
				assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
			});
		}
	}

	private static void rejectAt(final SmtpConversation peer, final RejectionPoint point) throws IOException {
		peer.greet("250 localhost");
		expectCommand(peer, MAIL_FROM);
		if (point == RejectionPoint.MAIL) {
			rejectAndFailCleanup(peer);
			return;
		}
		peer.reply("250 sender accepted");
		peer.expect("RCPT TO:<" + RECIPIENT + ">");
		if (point == RejectionPoint.RECIPIENT) {
			rejectAndFailCleanup(peer);
			return;
		}
		peer.reply("250 recipient accepted");
		peer.expect("DATA");
		if (point == RejectionPoint.FINAL_REPLY) {
			peer.reply("354 continue");
			readMessage(peer);
		}
		rejectAndFailCleanup(peer);
	}

	private static void rejectAndFailCleanup(final SmtpConversation peer) throws IOException {
		// A malformed enhanced code must not turn the primary temporary rejection into acceptance or leak an old response.
		peer.reply("451 not.an.enhanced.code synthetic rejection");
		for (int attempts = 0; attempts < 4; attempts++) {
			final String command = peer.readLine();
			if (command == null) {
				return;
			}
			assertThat(command).isIn("RSET", "NOOP", "QUIT");
			peer.reply("421 cleanup also unavailable");
		}
		throw new AssertionError("SMTP cleanup kept issuing commands after rejection");
	}

	@Test
	void malformedGreetingFailsWithoutClaimingSubmission() throws Exception {
		final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
		try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
			peer.reply("this is not an SMTP greeting");
			peer.expectClosed();
		}); Mailer mailer = builder(server).withMailSendObserver(observed::add).buildMailer()) {
			final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
			assertThat(failure).isNotNull();
			assertThat(observed).singleElement().satisfies(outcome -> {
				assertThat(outcome.isSuccessful()).isFalse();
				assertThat(outcome.getFailure()).containsSame(failure);
				assertThat(outcome.getSubmissionReceipt()).isEmpty();
			});
		}
	}

	private static MailerRegularBuilder<?> builder(final ScriptedSmtpServer server) {
		return MAIL.mailerBuilder().withSMTPServer("localhost", server.port()).withSmtpClientHostname("probe.example.test")
				.withSessionTimeout(3000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1);
	}

	private static Email email() {
		return MAIL.emailBuilder().startingBlank().from("sender@example.test").withRecipients(new Recipient(null, RECIPIENT, TO, null))
				.withSubject("Synthetic boundary fixture").withPlainText("No application content").buildEmail();
	}

	private enum RejectionPoint {
		MAIL, RECIPIENT, DATA_INVITATION, FINAL_REPLY
	}
}
