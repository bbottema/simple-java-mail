package org.simplejavamail.mailer;

import com.sanctionco.jmail.EmailValidator;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.converter.EmailConverter;
import org.subethamail.wiser.WiserMessage;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.SmtpServerExtension;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExactEmailSendingTest {

	@RegisterExtension
	final SmtpServerExtension smtpServer = new SmtpServerExtension(0, null, null, false);

	private SimpleJavaMail simpleJavaMail;

	@BeforeEach
	void setUp() {
		simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	}

	@Test
	void angusSubmissionPreservesAllDataBytesAndUsesOnlyTheExplicitEnvelope() throws Exception {
		final byte[] exactEml = multipartExactEml();
		final Email email = exactEmail(exactEml, "actual-recipient@example.org", "actual-sender@example.org");

		final MailSubmissionReceipt receipt;
		try (Mailer mailer = smtpMailer()) {
			receipt = mailer.sendMailAndGetReceiptSync(email);
		}

		assertThat(receipt.getEmailId()).isEqualTo("<smtp-exact@simplejavamail.org>");
		assertThat(receipt.getAcceptedRecipients()).containsExactly("actual-recipient@example.org");
		assertThat(smtpServer.getMessages()).hasSize(1);
		final WiserMessage received = smtpServer.getMessages().get(0);
		assertThat(received.getEnvelopeSender()).isEqualTo("actual-sender@example.org");
		assertThat(received.getEnvelopeReceiver()).isEqualTo("actual-recipient@example.org");
		assertThat(received.getData()).containsExactly(exactEml);
	}

	@Test
	void absentMessageIdRemainsAbsentFromReceiptAndObserverOutcome() throws Exception {
		final byte[] exactEml = ("Subject: No identifier\r\n"
				+ "Content-Type: text/plain; charset=us-ascii\r\n"
				+ "\r\n"
				+ "body\r\n").getBytes(StandardCharsets.US_ASCII);
		final Email email = exactEmail(exactEml, "recipient@example.org", null);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();

		final MailSubmissionReceipt receipt;
		try (Mailer mailer = simpleJavaMail.mailerBuilder()
				.withSMTPServer("unreachable.example.invalid", 25)
				.withTransportModeLoggingOnly(true)
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			receipt = mailer.sendMailAndGetReceiptSync(email);
		}

		assertThat(receipt.getEmailId()).isNull();
		assertThat(email.getId()).isNull();
		assertThat(outcomes).singleElement().satisfies(outcome -> {
			assertThat(outcome.getInitialMessageId()).isNull();
			assertThat(outcome.getEffectiveMessageId()).isNull();
			assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
		});
	}

	@Test
	void customMailerReceivesTheExactEmailAndBytesWithoutGovernance() throws Exception {
		final byte[] exactEml = exactEml("custom-exact", "Original subject", "custom body");
		final Email email = exactEmail(exactEml, "actual-recipient@example.org", null);
		final Email defaults = simpleJavaMail.emailBuilder().startingBlank()
				.from("default-sender@example.org")
				.withSubject("Default subject")
				.buildEmail();
		final Email overrides = simpleJavaMail.emailBuilder().startingBlank()
				.withSubject("Overridden subject")
				.buildEmail();
		final EmailValidator validator = mock(EmailValidator.class);
		final CustomMailer customMailer = mock(CustomMailer.class);
		final ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);

		try (Mailer mailer = simpleJavaMail.mailerBuilder()
				.withSMTPServer("unreachable.example.invalid", 25)
				.withEmailDefaults(defaults)
				.withEmailOverrides(overrides)
				.withEmailValidator(validator)
				.withCustomMailer(customMailer)
				.buildMailer()) {
			mailer.sendMailSync(email);
		}

		verify(customMailer).sendMessage(any(OperationalConfig.class), any(Session.class), same(email), messageCaptor.capture());
		verifyNoInteractions(validator);
		assertThat(email.getSubject()).isEqualTo("Original subject");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("visible-sender@example.org");
		assertThat(EmailConverter.mimeMessageToEMLByteArray(messageCaptor.getValue())).containsExactly(exactEml);
	}

	@Test
	void loggingOnlyAsyncSendReportsTheUnchangedMessageId() throws Exception {
		final byte[] exactEml = exactEml("logging-exact", "Logging only", "logging body");
		final Email email = exactEmail(exactEml, "recipient@example.org", null);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();

		final MailSubmissionReceipt receipt;
		try (Mailer mailer = simpleJavaMail.mailerBuilder()
				.withSMTPServer("unreachable.example.invalid", 25)
				.withTransportModeLoggingOnly(true)
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			receipt = mailer.sendMailAndGetReceiptAsync(email).get(5, TimeUnit.SECONDS);
		}

		assertThat(receipt.getEmailId()).isEqualTo("<logging-exact@simplejavamail.org>");
		assertThat(outcomes).singleElement().satisfies(outcome -> {
			assertThat(outcome.getInitialMessageId()).isEqualTo("<logging-exact@simplejavamail.org>");
			assertThat(outcome.getEffectiveMessageId()).isEqualTo("<logging-exact@simplejavamail.org>");
			assertThat(outcome.isSuccessful()).isTrue();
			assertThat(outcome.isLoggingOnly()).isTrue();
			assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
		});
	}

	@Test
	void simpleBatchPreservesEachExactMessageInIterationOrder() throws Exception {
		final byte[] firstEml = exactEml("batch-one", "Batch one", "first body");
		final byte[] secondEml = exactEml("batch-two", "Batch two", "second body");
		final List<byte[]> submittedMessages = new CopyOnWriteArrayList<>();
		final CustomMailer customMailer = recordingCustomMailer(submittedMessages);

		try (Mailer mailer = simpleJavaMail.mailerBuilder()
				.withSMTPServer("unreachable.example.invalid", 25)
				.withCustomMailer(customMailer)
				.buildMailer()) {
			mailer.sendMailsInSimpleBatch(Arrays.asList(
					exactEmail(firstEml, "first@example.org", null),
					exactEmail(secondEml, "second@example.org", null)), false);
		}

		assertThat(submittedMessages).hasSize(2);
		assertThat(submittedMessages.get(0)).containsExactly(firstEml);
		assertThat(submittedMessages.get(1)).containsExactly(secondEml);
	}

	@Test
	void openConnectionCanSubmitSeveralExactMessagesWithoutRebuildingEither() throws Exception {
		final byte[] firstEml = exactEml("open-one", "Open one", "first body");
		final byte[] secondEml = exactEml("open-two", "Open two", "second body");

		try (Mailer mailer = smtpMailer()) {
			mailer.withOpenConnection(sender -> {
				sender.sendMail(exactEmail(firstEml, "first@example.org", null));
				sender.sendMailAndGetReceipt(exactEmail(secondEml, "second@example.org", null));
			});
		}

		assertThat(smtpServer.getMessages()).hasSize(2);
		assertThat(smtpServer.getMessages().get(0).getData()).containsExactly(firstEml);
		assertThat(smtpServer.getMessages().get(1).getData()).containsExactly(secondEml);
	}

	@NotNull
	private Mailer smtpMailer() {
		return simpleJavaMail.mailerBuilder()
				.withSMTPServer("localhost", smtpServer.getWiser().getServer().getPortAllocated())
				.buildMailer();
	}

	@NotNull
	private Email exactEmail(final byte[] exactEml, @NotNull final String envelopeRecipient, final String envelopeSender) {
		final ExactEmailBuilder builder = simpleJavaMail.emailBuilder()
				.startingFromExactEml(exactEml)
				.withEnvelopeRecipients(envelopeRecipient);
		if (envelopeSender != null) {
			builder.withEnvelopeSender(envelopeSender);
		}
		return builder.buildEmail();
	}

	@NotNull
	private static CustomMailer recordingCustomMailer(@NotNull final List<byte[]> submittedMessages) {
		return new CustomMailer() {
			@Override
			public void testConnection(@NotNull final OperationalConfig operationalConfig,
					@NotNull final Session session) {
			}

			@Override
			public void sendMessage(@NotNull final OperationalConfig operationalConfig,
					@NotNull final Session session,
					@NotNull final Email email,
					@NotNull final MimeMessage message) {
				submittedMessages.add(EmailConverter.mimeMessageToEMLByteArray(message));
			}
		};
	}

	private static byte[] exactEml(final String localMessageId, final String subject, final String body) {
		return ("Message-ID: <" + localMessageId + "@simplejavamail.org>\r\n"
				+ "From: visible-sender@example.org\r\n"
				+ "To: visible-recipient@example.org\r\n"
				+ "Bcc: hidden-recipient@example.org\r\n"
				+ "Resent-Bcc: resent-hidden@example.org\r\n"
				+ "DKIM-Signature: v=1; a=rsa-sha256;\r\n"
				+ "\tb=leave-this-folding-alone\r\n"
				+ "X-Original-Folding: first\r\n"
				+ "\tsecond\r\n"
				+ "Content-Length: " + body.length() + "\r\n"
				+ "Subject: " + subject + "\r\n"
				+ "Content-Type: text/plain; charset=us-ascii\r\n"
				+ "\r\n"
				+ body + "\r\n").getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] multipartExactEml() {
		return ("Message-ID: <smtp-exact@simplejavamail.org>\r\n"
				+ "From: visible-sender@example.org\r\n"
				+ "To: visible-recipient@example.org\r\n"
				+ "Bcc: hidden-recipient@example.org\r\n"
				+ "Resent-Bcc: resent-hidden@example.org\r\n"
				+ "DKIM-Signature: v=1; a=rsa-sha256;\r\n"
				+ "\tb=leave-this-folding-alone\r\n"
				+ "Content-Length: 777\r\n"
				+ "Subject: Exact SMTP\r\n"
				+ "MIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed;\r\n"
				+ "\tboundary=\"=_exact_boundary_713\"\r\n"
				+ "\r\n"
				+ "--=_exact_boundary_713\r\n"
				+ "Content-Type: text/plain; charset=us-ascii\r\n"
				+ "Content-Transfer-Encoding: quoted-printable\r\n"
				+ "\r\n"
				+ "line=20one\r\n"
				+ "--=_exact_boundary_713\r\n"
				+ "Content-Type: application/octet-stream\r\n"
				+ "Content-Transfer-Encoding: base64\r\n"
				+ "Content-Disposition: attachment; filename=bytes.bin\r\n"
				+ "\r\n"
				+ "AAECAwQ=\r\n"
				+ "--=_exact_boundary_713--\r\n").getBytes(StandardCharsets.US_ASCII);
	}
}
