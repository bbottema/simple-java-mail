package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.SmtpRecipientStatus;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@Timeout(40)
class SmtpRecipientRepliesTest {

	@ParameterizedTest
	@CsvSource({"true, true", "true, false", "false, true", "false, false"})
	void perEmailChoiceControlsDataButPartialSendingStillFailsTheOperation(final boolean continueSending, final boolean async) throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
			final Email email = emailWithChoice(continueSending, "accepted", "temporary", "permanent");
			final MailSubmissionException failure;
			if (async) {
				final ExecutionException completionFailure = catchThrowableOfType(() -> mailer.async().sendMail(email).getCompletion()
						.get(10, TimeUnit.SECONDS), ExecutionException.class);
				assertThat(completionFailure).isNotNull();
				failure = (MailSubmissionException) completionFailure.getCause();
			} else {
				failure = failure(mailer, email);
			}
			final MailSubmissionReceipt receipt = failure.getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(continueSending ? MailSubmissionStatus.PARTIALLY_ACCEPTED : MailSubmissionStatus.REJECTED);
			assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getRcptStatus).containsExactly(
					SmtpRecipientStatus.ACCEPTED, SmtpRecipientStatus.TEMPORARILY_REJECTED, SmtpRecipientStatus.PERMANENTLY_REJECTED);
			assertThat(receipt.getAcceptedRecipients()).containsExactlyElementsOf(continueSending ? List.of("accepted@example.org") : List.of());
			assertThat(receipt.getRetryableRecipients()).extracting(MailRecipientResult::getOriginalAddress).containsExactlyElementsOf(
					continueSending ? List.of("temporary@example.org") : List.of("accepted@example.org", "temporary@example.org"));
			assertThat(outcomes).singleElement().satisfies(outcome -> {
				assertThat(outcome.isSuccessful()).isFalse();
				assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
				assertThat(outcome.getFailure()).containsSame(failure);
			});
			assertThat(server.dataCommands).hasValue(continueSending ? 1 : 0);
			assertThat(server.messages).hasValue(continueSending ? 1 : 0);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void allRejectedRecipientsNeverReceiveContentRegardlessOfChoice(final boolean continueSending) throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			assertThat(failure(mailer, emailWithChoice(continueSending, "temporary", "permanent")).getSubmissionReceipt().getStatus())
					.isEqualTo(MailSubmissionStatus.REJECTED);
			assertThat(server.dataCommands).hasValue(0);
			assertThat(server.messages).hasValue(0);
		}
	}

	@Test
	void trueFalseAndUnsetAlternateOnOneHealthyPooledConnectionWithoutLeakingChoicesOrResponses() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withMailSendObserver(outcomes::add).buildMailer()) {
			for (final Boolean choice : Arrays.asList(true, false, null, true, null, false)) {
				final MailSubmissionReceipt receipt = mailer.sync().sendMail(emailWithChoice(choice, "accepted-" + outcomes.size()));
				assertThat(receipt.getRecipientResults()).singleElement().satisfies(result ->
						assertThat(result.getRcptResponse().orElseThrow().getResponse()).contains(result.getEnvelopeAddress().orElseThrow()));
			}
			assertThat(server.connections).hasValue(1);
			assertThat(server.messages).hasValue(6);
			assertThat(mailer.getSession().getProperty("mail.smtp.sendpartial")).isNull();
			assertThat(failure(mailer, emailWithChoice(true, "accepted-partial", "temporary-partial")).getSubmissionReceipt().getStatus())
					.isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
			assertThat(failure(mailer, emailWithChoice(false, "accepted-stop", "temporary-stop")).getSubmissionReceipt().getStatus())
					.isEqualTo(MailSubmissionStatus.REJECTED);
			assertThat(failure(mailer, email("accepted-unset", "temporary-unset")).getSubmissionReceipt().getStatus())
					.isEqualTo(MailSubmissionStatus.REJECTED);
			final MailSubmissionReceipt recovery = mailer.sync().sendMail(email("accepted-recovery"));
			assertThat(recovery.getAcceptedRecipients()).containsExactly("accepted-recovery@example.org");
			assertThat(server.messages).hasValue(8);
			assertThat(outcomes).hasSize(10);
		}
	}

	@Test
	void concurrentPooledChoicesAndRecipientFactsRemainAttemptLocal() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final List<Boolean> choices = Arrays.asList(true, false, null);
		try (RecipientSmtpServer server = new RecipientSmtpServer(null, new CyclicBarrier(3)); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(3).withThreadPoolSize(3).withMailSendObserver(outcomes::add).buildMailer()) {
			final List<CompletableFuture<MailSubmissionReceipt>> warmups = new ArrayList<>();
			for (int index = 0; index < choices.size(); index++) {
				warmups.add(mailer.async().sendMail(emailWithChoice(choices.get(index), "accepted-warmup-" + index)).getCompletion());
			}
			for (final CompletableFuture<MailSubmissionReceipt> warmup : warmups) {
				assertThat(warmup.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
			}
			assertThat(server.connections).hasValue(3);
			for (int wave = 0; wave < 3; wave++) {
				final List<CompletableFuture<MailSubmissionReceipt>> attempts = new ArrayList<>();
				for (int index = 0; index < choices.size(); index++) {
					final String tag = wave + "-" + index;
					final Boolean choice = choices.get((wave + index) % choices.size());
					// Synchronize at the first RCPT reply: stopping attempts never reach DATA.
					attempts.add(mailer.async().sendMail(emailWithChoice(choice, "accepted-" + tag, "temporary-" + tag, "permanent-" + tag))
							.getCompletion());
				}
				for (int index = 0; index < attempts.size(); index++) {
					final String tag = wave + "-" + index;
					final boolean continueSending = Boolean.TRUE.equals(choices.get((wave + index) % choices.size()));
					final CompletableFuture<MailSubmissionReceipt> attempt = attempts.get(index);
					final ExecutionException completionFailure = catchThrowableOfType(() -> attempt.get(10, TimeUnit.SECONDS), ExecutionException.class);
					assertThat(completionFailure).isNotNull().hasCauseInstanceOf(MailSubmissionException.class);
					final MailSubmissionException failure = (MailSubmissionException) completionFailure.getCause();
					final MailSubmissionReceipt receipt = failure.getSubmissionReceipt();
					assertThat(receipt.getStatus()).isEqualTo(continueSending ? MailSubmissionStatus.PARTIALLY_ACCEPTED : MailSubmissionStatus.REJECTED);
					assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress).containsExactly(
							"accepted-" + tag + "@example.org", "temporary-" + tag + "@example.org", "permanent-" + tag + "@example.org");
					assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getRcptStatus).containsExactly(
							SmtpRecipientStatus.ACCEPTED, SmtpRecipientStatus.TEMPORARILY_REJECTED, SmtpRecipientStatus.PERMANENTLY_REJECTED);
					assertThat(receipt.getAcceptedRecipients()).containsExactlyElementsOf(
							continueSending ? List.of("accepted-" + tag + "@example.org") : List.of());
					assertThat(receipt.getRetryableRecipients()).extracting(MailRecipientResult::getOriginalAddress).containsExactlyElementsOf(continueSending
							? List.of("temporary-" + tag + "@example.org")
							: List.of("accepted-" + tag + "@example.org", "temporary-" + tag + "@example.org"));
					assertThat(receipt.getRecipientResults()).allSatisfy(recipient -> {
						assertThat(recipient.getEnvelopeAddress()).contains(recipient.getOriginalAddress());
						assertThat(recipient.getRcptResponse().orElseThrow().getResponse()).contains(recipient.getOriginalAddress());
					});
					assertThat(server.contents.stream().filter(content -> content.contains("accepted-" + tag + "@example.org")).count())
							.isEqualTo(continueSending ? 1 : 0);
					assertThat(outcomes.stream().filter(outcome -> outcome.getFailure().orElse(null) == failure)).singleElement().satisfies(outcome -> {
						assertThat(outcome.isSuccessful()).isFalse();
						assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
						assertThat(outcome.getFailure()).containsSame(failure);
					});
				}
				// The first rejection wave reuses the warmed connections; later waves replace the invalidated leases.
				assertThat(server.connections).hasValue(3 * (wave + 1));
				assertThat(server.dataCommands).hasValue(4 + wave);
				assertThat(server.messages).hasValue(4 + wave);
			}
			assertThat(outcomes).hasSize(12);
			assertThat(outcomes.stream().filter(MailSendOutcome::isSuccessful).count()).isEqualTo(3);
			assertThat(server.mailCommands).hasValue(12);
			assertThat(mailer.getSession().getProperty("mail.smtp.sendpartial")).isNull();
		}
	}

	@Test
	void localSessionConflictDoesNotIssueMailFromOrInvalidateAHealthyLease() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).withProperty("mail.smtp.sendpartial", true)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).buildMailer()) {
			mailer.sync().sendMail(email("accepted-before"));
			final MailSubmissionReceipt receipt = failure(mailer, emailWithChoice(false, "accepted-conflict")).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
			assertThat(receipt.getSmtpResponse()).isEmpty();
			assertThat(receipt.getRecipientResults()).allSatisfy(recipient -> assertThat(recipient.getRcptResponse()).isEmpty());
			assertThat(server.mailCommands).hasValue(1);
			mailer.sync().sendMail(emailWithChoice(true, "accepted-after"));
			assertThat(server.mailCommands).hasValue(2);
			assertThat(server.connections).hasValue(1);
			assertThat(mailer.getSession().getProperty("mail.smtp.sendpartial")).isEqualTo("true");
		}
	}

	@Test
	void callerOwnedSessionCanSendWithEachChoiceButCannotOverrideItsAdvancedTrue() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer()) {
			final Properties properties = new Properties();
			properties.setProperty("mail.transport.protocol", "smtp");
			properties.setProperty("mail.smtp.host", InetAddress.getLoopbackAddress().getHostAddress());
			properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
			properties.setProperty("mail.smtp.localhost", "probe.example.test");
			final Session session = Session.getInstance(properties);
			try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
					.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).buildMailer()) {
				assertThat(failure(mailer, emailWithChoice(true, "accepted-first", "temporary-first")).getSubmissionReceipt().getStatus())
						.isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
				assertThat(failure(mailer, emailWithChoice(false, "accepted-second", "temporary-second")).getSubmissionReceipt().getStatus())
						.isEqualTo(MailSubmissionStatus.REJECTED);
				properties.setProperty("mail.smtp.sendpartial", "true");
				final MailSubmissionException conflict = failure(mailer, emailWithChoice(false, "accepted-conflict"));
				assertThat(conflict.getCause()).hasMessageContaining("mail.smtp.sendpartial=true");
				assertThat(server.mailCommands).hasValue(2);
			}
		}
	}

	@Test
	void clusteredConflictChecksUseTheSelectedSessionsAdvancedSetting() throws Exception {
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
		final UUID cluster = UUID.randomUUID();
		try (RecipientSmtpServer firstServer = new RecipientSmtpServer(); RecipientSmtpServer secondServer = new RecipientSmtpServer();
			 Mailer first = builder(factory, firstServer).withClusterKey(cluster).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
					.withProperty("mail.smtp.sendpartial", true).buildMailer();
			 Mailer second = builder(factory, secondServer).withClusterKey(cluster).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).buildMailer()) {
			int localConflicts = 0;
			int accepted = 0;
			for (int index = 0; index < 6; index++) {
				try {
					assertThat(first.sync().sendMail(emailWithChoice(false, "accepted-cluster-" + index)).getStatus())
							.isEqualTo(MailSubmissionStatus.ACCEPTED);
					accepted++;
				} catch (final MailSubmissionException failure) {
					assertThat(failure.getCause()).hasMessageContaining("mail.smtp.sendpartial=true");
					assertThat(failure.getSubmissionReceipt().getSmtpResponse()).isEmpty();
					localConflicts++;
				}
			}
			assertThat(localConflicts).isPositive();
			assertThat(accepted).isPositive();
			assertThat(firstServer.mailCommands).hasValue(0);
			assertThat(secondServer.mailCommands).hasValue(accepted);
			assertThat(first.getSession().getProperty("mail.smtp.sendpartial")).isEqualTo("true");
			assertThat(second.getSession().getProperty("mail.smtp.sendpartial")).isNull();
		}
	}

	@ParameterizedTest
	@CsvSource({"true, true", "true, false", "false, true", "false, false"})
	void batchesStayLazyAndStopEvenWhenAcceptedRecipientsReceiveTheFailedEmail(final boolean continueSending, final boolean async) throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final AtomicInteger traversed = new AtomicInteger();
		final Iterable<Email> emails = () -> List.of(email("accepted-first"), emailWithChoice(continueSending, "accepted-second", "temporary-second"),
				email("accepted-untouched")).stream().peek(email -> traversed.incrementAndGet()).iterator();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
			final Throwable failure;
			if (async) {
				final ExecutionException completionFailure = catchThrowableOfType(() -> mailer.async().sendMailsInSimpleBatch(emails).getCompletion()
						.get(10, TimeUnit.SECONDS), ExecutionException.class);
				assertThat(completionFailure).isNotNull();
				failure = completionFailure.getCause();
			} else {
				failure = catchThrowableOfType(() -> mailer.sync().sendMailsInSimpleBatch(emails), MailSubmissionException.class);
				assertThat(failure).isNotNull();
			}
			assertThat(traversed).hasValue(2);
			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(1).getFailure()).containsSame(failure);
			assertThat(server.messages).hasValue(continueSending ? 2 : 1);
		}
	}

	@Test
	void openConnectionUsesEachEmailsChoiceWithoutLeakingItToLaterSends() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			mailer.withOpenConnection(sender -> {
				final MailSubmissionException continued = catchThrowableOfType(
						() -> sender.sendMail(emailWithChoice(true, "accepted-first", "temporary-first")),
						MailSubmissionException.class);
				assertThat(continued.getSubmissionReceipt().getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
				final MailSubmissionException stopped = catchThrowableOfType(
						() -> sender.sendMail(emailWithChoice(false, "accepted-second", "temporary-second")),
						MailSubmissionException.class);
				assertThat(stopped.getSubmissionReceipt().getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
				assertThat(sender.sendMailAndGetReceipt(email("accepted-last")).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
			});
			assertThat(server.connections).hasValue(1);
			assertThat(server.messages).hasValue(2);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void exactBytesAndEnvelopeOverridesSurviveTheRecipientChoice(final boolean continueSending) throws Exception {
		final String eml = "From: sender@example.org\r\nTo: header-only@example.org\r\nBcc: hidden-header@example.org\r\n"
				+ "X-Signature: keep-this-byte-for-byte\r\n\r\nbody\r\n";
		final Email exact = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder()
				.startingFromExactEml(eml.getBytes(StandardCharsets.US_ASCII))
				.withEnvelopeRecipients("accepted@example.org", "accepted@example.org", "temporary@example.org")
				.withSendingToAcceptedRecipients(continueSending).buildEmail();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, exact).getSubmissionReceipt();
			assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getOriginalAddress)
					.containsExactly("accepted@example.org", "accepted@example.org", "temporary@example.org");
			assertThat(server.contents).containsExactlyElementsOf(continueSending ? List.of(eml) : List.of());
			assertThat(server.dataCommands).hasValue(continueSending ? 1 : 0);
		}
	}

	@Test
	void lostFinalReplyStillMeansUncertainAcceptanceWhenPerEmailContinuationWasRequested() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, emailWithChoice(true, "finaleof", "temporary", "permanent")).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
			assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
			assertThat(receipt.getAcceptedRecipients()).isEmpty();
			assertThat(receipt.getValidUnsentRecipients()).containsExactly("temporary@example.org");
			assertThat(receipt.getInvalidRecipients()).containsExactly("permanent@example.org");
		}
	}

	@ParameterizedTest
	@CsvSource({"true, /pkcs12/test messages/S_MIME test message signed.eml", "false, /pkcs12/test messages/S_MIME test message signed.eml",
			"true, /openpgpjs/signed-mixed.eml", "false, /openpgpjs/signed-mixed.eml"})
	void recipientChoicePreservesRealSignedEmlOnTheWire(final boolean continueSending, final String resource) throws Exception {
		final byte[] signedBytes;
		try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(resource))) {
			signedBytes = input.readAllBytes();
		}
		final Email exact = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingFromExactEml(signedBytes)
				.withEnvelopeRecipients("accepted@example.org", "temporary@example.org").withSendingToAcceptedRecipients(continueSending).buildEmail();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(signedBytes);
			final MailSubmissionReceipt receipt = failure(mailer, exact).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(continueSending ? MailSubmissionStatus.PARTIALLY_ACCEPTED : MailSubmissionStatus.REJECTED);
			assertThat(server.contents).containsExactlyElementsOf(continueSending ? List.of(new String(signedBytes, StandardCharsets.US_ASCII)) : List.of());
		}
	}

	@Test
	void choosingRecipientRejectionHandlingDoesNotAddAttachmentReads() throws Exception {
		final List<Integer> readCounts = new ArrayList<>();
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			for (final Boolean choice : Arrays.asList(null, true, false)) {
				final CountingAttachment attachment = new CountingAttachment();
				final Email email = factory.emailBuilder().copying(emailWithChoice(choice, "accepted-attachment"))
						.withAttachment("counted.bin", attachment).buildEmail();
				mailer.sync().sendMail(email);
				readCounts.add(attachment.readCount);
			}
			assertThat(readCounts.get(0)).as("The baseline must actually read the attachment").isPositive();
			assertThat(readCounts).containsOnly(readCounts.get(0));
			assertThat(server.messages).hasValue(3);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void collectsSuccessRepliesRegardlessOfSessionReportingPreference(final boolean reportSuccess) throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withProperty("mail.smtp.reportsuccess", reportSuccess).buildMailer()) {
			final MailSubmissionReceipt receipt = mailer.sync().sendMail(email("accepted-one", "accepted-two"));
			assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
			assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.DO_NOT_RETRY);
			assertThat(receipt.getRecipientResults()).hasSize(2).allSatisfy(recipient -> {
				assertThat(recipient.getDisposition()).isEqualTo(MailRecipientDisposition.ACCEPTED);
				assertThat(recipient.getRcptAttempted()).contains(true);
				assertThat(recipient.getRcptResponse()).hasValueSatisfying(reply -> {
					assertThat(reply.getReturnCode()).isEqualTo(250);
					assertThat(reply.getEnhancedStatusCode()).contains("2.1.5");
					assertThat(reply.getResponse()).contains(recipient.getEnvelopeAddress().orElseThrow());
				});
			});
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void mixedRecipientsKeepOriginalOrderAndSeparateTemporaryFromPermanent(final boolean sendPartial) throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withProperty("mail.smtp.sendpartial", sendPartial).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, email("temporary", "accepted", "permanent", "quota")).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(sendPartial ? MailSubmissionStatus.PARTIALLY_ACCEPTED : MailSubmissionStatus.REJECTED);
			assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getRcptStatus).containsExactly(
					SmtpRecipientStatus.TEMPORARILY_REJECTED, SmtpRecipientStatus.ACCEPTED,
					SmtpRecipientStatus.PERMANENTLY_REJECTED, SmtpRecipientStatus.PERMANENTLY_REJECTED);
			assertThat(receipt.getRecipientResults()).extracting(recipient -> recipient.getRcptResponse().orElseThrow().getReturnCode())
					.containsExactly(450, 250, 550, 552);
			assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_UNACCEPTED);
			assertThat(receipt.getRetryableRecipients()).extracting(MailRecipientResult::getOriginalAddress)
					.containsExactlyElementsOf(sendPartial ? List.of("temporary@example.org") : List.of("temporary@example.org", "accepted@example.org"));
			assertThat(receipt.getValidUnsentRecipients()).contains("quota@example.org");
			assertThat(receipt.getInvalidRecipients()).containsExactly("permanent@example.org");
			assertThat(server.messages.get()).isEqualTo(sendPartial ? 1 : 0);
		}
	}

	@Test
	void allTemporaryAndAllPermanentRejectionsProduceDifferentAdvice() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			assertThat(failure(mailer, email("temporary-one", "temporary-two")).getSubmissionReceipt().getRetryDisposition())
					.isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_ALL);
			assertThat(failure(mailer, email("permanent-one", "quota-two")).getSubmissionReceipt().getRetryDisposition())
					.isEqualTo(MailRetryDisposition.DO_NOT_RETRY);
			assertThat(server.messages.get()).isZero();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"finaltemporary", "finalpermanent", "finaleof", "finalreset", "finaltimeout"})
	void finalDataReplyControlsRetryEvenAfterPositiveRecipientReplies(final String scenario) throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, email(scenario)).getSubmissionReceipt();
			if (scenario.equals("finaltemporary") || scenario.equals("finalpermanent")) {
				assertThat(receipt.getRecipientResults().get(0).getRcptStatus()).isEqualTo(SmtpRecipientStatus.ACCEPTED);
				assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
				assertThat(receipt.getRetryDisposition()).isEqualTo(scenario.equals("finaltemporary")
						? MailRetryDisposition.SAFE_TO_RETRY_ALL : MailRetryDisposition.DO_NOT_RETRY);
			} else {
				assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
				assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.DUPLICATE_RISK);
				assertThat(receipt.getSmtpResponse()).isEmpty();
				assertThat(receipt.getValidUnsentRecipients()).isEmpty();
				assertThat(receipt.getRetryableRecipients()).isEmpty();
			}
		}
	}

	@Test
	void lostFinalReplyRetainsKnownTemporaryAndPermanentRcptRejections() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withProperty("mail.smtp.sendpartial", true).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, email("finaleof", "temporary", "permanent", "quota")).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
			assertThat(receipt.getAcceptedRecipients()).isEmpty();
			assertThat(receipt.getValidUnsentRecipients()).containsExactly("temporary@example.org", "quota@example.org");
			assertThat(receipt.getInvalidRecipients()).containsExactly("permanent@example.org");
			assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getRcptStatus).containsExactly(
					SmtpRecipientStatus.ACCEPTED, SmtpRecipientStatus.TEMPORARILY_REJECTED,
					SmtpRecipientStatus.PERMANENTLY_REJECTED, SmtpRecipientStatus.PERMANENTLY_REJECTED);
		}
	}

	@Test
	void earlyRcptDisconnectMarksUntouchedRecipientsWithoutInventingAReply() throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server).buildMailer()) {
			final MailSubmissionReceipt receipt = failure(mailer, email("rcpteof", "untouched")).getSubmissionReceipt();
			assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_ALL);
			assertThat(receipt.getRecipientResults().get(0).getRcptAttempted()).contains(true);
			assertThat(receipt.getRecipientResults().get(0).getRcptResponse()).isEmpty();
			assertThat(receipt.getRecipientResults().get(1).getRcptStatus()).isEqualTo(SmtpRecipientStatus.NOT_ATTEMPTED);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	void pooledAsyncAttemptsNeverShareRecipientReplies(final int poolSize) throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(poolSize).withThreadPoolSize(6)
				.withProperty("mail.smtp.sendpartial", true).withMailSendObserver(outcomes::add).buildMailer()) {
			mailer.async().sendMail(email("accepted-warmup")).getCompletion().get(10, TimeUnit.SECONDS);
			final List<CompletableFuture<MailSubmissionReceipt>> attempts = new ArrayList<>();
			for (int index = 0; index < 12; index++) {
				final String tag = "accepted-" + index;
				attempts.add(mailer.async().sendMail(email(tag)).getCompletion());
			}
			for (final CompletableFuture<MailSubmissionReceipt> attempt : attempts) {
				attempt.get(10, TimeUnit.SECONDS);
			}
			for (int index = 0; index < 4; index++) {
				final CompletableFuture<MailSubmissionReceipt> rejected = mailer.async().sendMail(
						email("accepted-partial-" + index, "temporary-" + index)).getCompletion();
				assertThat(rejected.handle((receipt, failure) -> failure).get(10, TimeUnit.SECONDS)).isNotNull();
				mailer.async().sendMail(email("accepted-after-failure-" + index)).getCompletion().get(10, TimeUnit.SECONDS);
			}
			mailer.async().sendMail(email("accepted-recovery")).getCompletion().get(10, TimeUnit.SECONDS);
			assertThat(outcomes).hasSize(22);
			assertThat(outcomes.stream().filter(MailSendOutcome::isSuccessful).count()).isEqualTo(18);
			for (final MailSendOutcome outcome : outcomes) {
				final MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElseThrow();
				assertThat(receipt.getRecipientResults()).allSatisfy(recipient ->
						assertThat(recipient.getRcptResponse().orElseThrow().getResponse())
								.contains(recipient.getEnvelopeAddress().orElseThrow()));
			}
			assertThat(server.connections.get()).as("Failed leases require replacement connections").isGreaterThan(1);
			assertThat(server.messages.get()).isEqualTo(22);
		}
	}

	@Test
	void overlappingPartialFailuresKeepRepliesLocalToEachConnection() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(new CyclicBarrier(3)); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(3).withThreadPoolSize(3)
				.withProperty("mail.smtp.sendpartial", true).withMailSendObserver(outcomes::add).buildMailer()) {
			for (int wave = 0; wave < 3; wave++) {
				final List<CompletableFuture<MailSubmissionReceipt>> attempts = new ArrayList<>();
				for (int index = 0; index < 3; index++) {
					final String tag = wave + "-" + index;
					attempts.add(mailer.async().sendMail(index == 1 ? email("accepted-" + tag)
							: email("accepted-" + tag, "temporary-" + tag)).getCompletion());
				}
				for (final CompletableFuture<MailSubmissionReceipt> attempt : attempts) {
					attempt.handle((receipt, failure) -> null).get(10, TimeUnit.SECONDS);
				}
			}
			assertThat(outcomes).hasSize(9);
			assertThat(outcomes.stream().filter(MailSendOutcome::isSuccessful).count()).isEqualTo(3);
			for (final MailSendOutcome outcome : outcomes) {
				final MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElseThrow();
				assertThat(receipt.getRecipientResults()).allSatisfy(recipient ->
						assertThat(recipient.getRcptResponse().orElseThrow().getResponse())
								.contains(recipient.getEnvelopeAddress().orElseThrow()));
			}
		}
	}

	@Test
	void simpleBatchStopsAfterTheFirstTransportFailureAndDoesNotReportUntouchedEmails() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
				.withMailSendObserver(outcomes::add).buildMailer()) {
			final ExecutionException failedBatch = catchThrowableOfType(() -> mailer.async().sendMailsInSimpleBatch(
					List.of(email("accepted-first"), email("temporary-second"), email("untouched-third"))).getCompletion()
					.get(10, TimeUnit.SECONDS), ExecutionException.class);
			assertThat(failedBatch).isNotNull();
			final Throwable failure = failedBatch.getCause();
			assertThat(failure).isInstanceOf(MailSubmissionException.class);
			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(((MailSubmissionException) failure).getSubmissionReceipt());
			assertThat(outcomes.get(1).getSubmissionReceipt().orElseThrow().getRecipientResults()).singleElement()
					.satisfies(recipient -> assertThat(recipient.getRcptStatus()).isEqualTo(SmtpRecipientStatus.TEMPORARILY_REJECTED));
			assertThat(server.messages.get()).isEqualTo(1);
		}
	}

	@Test
	void successfulReportingReleasesPoolBeforeReentrantObserverSend() throws Exception {
		final AtomicReference<Mailer> configuredMailer = new AtomicReference<>();
		final List<MailSubmissionReceipt> receipts = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(2000)
				.withMailSendObserver(outcome -> {
					receipts.add(outcome.getSubmissionReceipt().orElseThrow());
					if (receipts.size() == 1) {
						configuredMailer.get().sync().sendMail(email("accepted-reentrant"));
					}
				}).buildMailer()) {
			configuredMailer.set(mailer);
			mailer.sync().sendMail(email("accepted-first"));
			assertThat(receipts).hasSize(2);
			assertThat(server.connections.get()).isEqualTo(1);
		}
	}

	@Test
	void simpleBatchAndOpenConnectionPublishEachReceiptWhileSharingTheTransport() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withMailSendObserver(outcomes::add).buildMailer()) {
			mailer.async().sendMailsInSimpleBatch(List.of(email("accepted-batch-one"), email("accepted-batch-two"))).getCompletion().get(10, TimeUnit.SECONDS);
			mailer.withOpenConnection(sender -> {
				final MailSubmissionReceipt first = sender.sendMailAndGetReceipt(email("accepted-open-one"));
				assertThat(outcomes).hasSize(3);
				assertThat(outcomes.get(2).getSubmissionReceipt()).containsSame(first);
				sender.sendMail(email("accepted-open-two"));
			});
			assertThat(outcomes).hasSize(4).allSatisfy(outcome ->
					assertThat(outcome.getSubmissionReceipt().orElseThrow().getRecipientResults().get(0).getRcptStatus())
							.isEqualTo(SmtpRecipientStatus.ACCEPTED));
			assertThat(server.connections.get()).as("Batch and open-connection callbacks each own their shared transport").isEqualTo(2);
		}
	}

	private static MailSubmissionException failure(final Mailer mailer, final Email email) {
		final MailSubmissionException failure = catchThrowableOfType(() -> mailer.sync().sendMail(email), MailSubmissionException.class);
		assertThat(failure).isNotNull();
		return failure;
	}

	private static MailerRegularBuilder<?> builder(final RecipientSmtpServer server) {
		return builder(SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()), server);
	}

	private static MailerRegularBuilder<?> builder(final SimpleJavaMail factory, final RecipientSmtpServer server) {
		// Keep EHLO and Message-ID hostname lookup outside batch completion and pooled-attempt timing.
		return factory.mailerBuilder()
				.withSMTPServer(InetAddress.getLoopbackAddress().getHostAddress(), server.port())
				.withSmtpClientHostname("probe.example.test").withProperty("mail.from", "sender@example.org")
				.withSessionTimeout(1200)
				.withConnectionPoolClaimTimeoutMillis(5000)
				.withProperty("mail.smtp.connectiontimeout", 3000).withProperty("mail.smtp.timeout", 1200)
				.withProperty("mail.smtp.writetimeout", 3000);
	}

	private static Email email(final String... recipientNames) {
		final String[] addresses = Arrays.stream(recipientNames).map(name -> name + "@example.org").toArray(String[]::new);
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.from("sender@example.org").withRecipients(EmailHelper.parsedRecipients(null, false, TO, addresses))
				.withSubject("Recipient reply test").withPlainText("body").buildEmail();
	}

	private static Email emailWithChoice(final Boolean choice, final String... recipientNames) {
		final EmailPopulatingBuilder builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig())
				.emailBuilder().copying(email(recipientNames));
		if (choice != null) {
			builder.withSendingToAcceptedRecipients(choice);
		}
		return builder.buildEmail();
	}

	private static final class CountingAttachment extends ByteArrayDataSource {
		private int readCount;

		private CountingAttachment() {
			super(new byte[512], "application/octet-stream");
		}

		@Override
		public InputStream getInputStream() throws IOException {
			readCount++;
			return super.getInputStream();
		}
	}

	/** A reusable SMTP connection is needed here to prove that replies remain local to each pooled attempt. */
	private static final class RecipientSmtpServer implements AutoCloseable {
		private final ServerSocket listener;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
		private final List<Throwable> errors = new CopyOnWriteArrayList<>();
		private final AtomicInteger connections = new AtomicInteger();
		private final AtomicInteger messages = new AtomicInteger();
		private final AtomicInteger mailCommands = new AtomicInteger();
		private final AtomicInteger dataCommands = new AtomicInteger();
		private final List<String> contents = new CopyOnWriteArrayList<>();
		private final CyclicBarrier replyBarrier;
		private final CyclicBarrier firstRecipientBarrier;
		private volatile boolean closed;

		private RecipientSmtpServer() throws IOException {
			this(null);
		}

		private RecipientSmtpServer(final CyclicBarrier replyBarrier) throws IOException {
			this(replyBarrier, null);
		}

		private RecipientSmtpServer(final CyclicBarrier replyBarrier, final CyclicBarrier firstRecipientBarrier) throws IOException {
			this.replyBarrier = replyBarrier;
			this.firstRecipientBarrier = firstRecipientBarrier;
			listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
			executor.submit(this::acceptConnections);
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					final Socket socket = listener.accept();
					sockets.add(socket);
					connections.incrementAndGet();
					executor.submit(() -> serve(socket));
				} catch (final IOException failure) {
					if (!closed) {
						errors.add(failure);
					}
				}
			}
		}

		private int port() {
			return listener.getLocalPort();
		}

		private void serve(final Socket socket) {
			try (Socket connection = socket) {
				connection.setSoTimeout(15000);
				final BufferedReader client = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
				final BufferedWriter server = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.US_ASCII));
				reply(server, "220 replies.example ESMTP ready");
				String firstRecipient = "";
				String command;
				while ((command = client.readLine()) != null) {
					if (command.startsWith("EHLO")) {
						reply(server, "250-replies.example\r\n250 ENHANCEDSTATUSCODES");
					} else if (command.startsWith("MAIL FROM:")) {
						mailCommands.incrementAndGet();
						firstRecipient = "";
						reply(server, "250 2.1.0 sender accepted");
					} else if (command.startsWith("RCPT TO:")) {
						final String recipient = command.substring(command.indexOf('<') + 1, command.indexOf('>'));
						if (firstRecipient.isEmpty()) {
							firstRecipient = recipient;
							if (firstRecipientBarrier != null) {
								firstRecipientBarrier.await(5, TimeUnit.SECONDS);
							}
						}
						if (recipient.startsWith("rcpteof")) {
							return;
						}
						reply(server, recipientReply(recipient));
					} else if (command.equals("DATA")) {
						dataCommands.incrementAndGet();
						reply(server, "354 send content");
						final StringBuilder content = new StringBuilder();
						String line;
						while ((line = client.readLine()) != null && !line.equals(".")) {
							content.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
						}
						if (line == null) {
							return;
						}
						messages.incrementAndGet();
						contents.add(content.toString());
						if (replyBarrier != null) {
							replyBarrier.await(5, TimeUnit.SECONDS);
						}
						if (!finishData(connection, client, server, firstRecipient)) {
							return;
						}
					} else if (command.equals("RSET") || command.equals("NOOP")) {
						reply(server, "250 OK");
					} else if (command.equals("QUIT")) {
						reply(server, "221 bye");
						return;
					} else {
						throw new AssertionError("Unexpected SMTP command: " + command);
					}
				}
			} catch (final Throwable failure) {
				if (!closed) {
					errors.add(failure);
				}
			} finally {
				sockets.remove(socket);
			}
		}

		private static boolean finishData(final Socket connection, final BufferedReader client, final BufferedWriter server,
				final String firstRecipient) throws IOException {
			if (firstRecipient.startsWith("finaleof")) {
				return false;
			}
			if (firstRecipient.startsWith("finalreset")) {
				connection.setSoLinger(true, 0);
				return false;
			}
			if (firstRecipient.startsWith("finaltimeout")) {
				client.readLine();
				return false;
			}
			if (firstRecipient.startsWith("finaltemporary")) {
				reply(server, "450 4.3.0 try later");
			} else if (firstRecipient.startsWith("finalpermanent")) {
				reply(server, "550 5.7.1 blocked");
			} else {
				reply(server, "250 2.0.0 queued " + firstRecipient);
			}
			return true;
		}

		private static String recipientReply(final String recipient) {
			if (recipient.startsWith("temporary")) {
				return "450 4.2.0 busy " + recipient;
			}
			if (recipient.startsWith("permanent")) {
				return "550 5.1.1 unknown " + recipient;
			}
			if (recipient.startsWith("quota")) {
				return "552 5.2.2 full " + recipient;
			}
			return "250 2.1.5 accepted " + recipient;
		}

		private static void reply(final BufferedWriter server, final String reply) throws IOException {
			server.write(reply + "\r\n");
			server.flush();
		}

		@Override
		public void close() throws Exception {
			closed = true;
			listener.close();
			for (final Socket socket : sockets) {
				socket.close();
			}
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
			assertThat(errors).isEmpty();
		}
	}
}
