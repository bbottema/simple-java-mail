package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
	@ValueSource(booleans = {false, true})
	void collectsSuccessRepliesRegardlessOfSessionReportingPreference(final boolean reportSuccess) throws Exception {
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withProperty("mail.smtp.reportsuccess", reportSuccess).buildMailer()) {
			final MailSubmissionReceipt receipt = mailer.sendMailAndGetReceiptSync(email("accepted-one", "accepted-two"));
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
			mailer.sendMailAndGetReceiptAsync(email("accepted-warmup")).get(10, TimeUnit.SECONDS);
			final List<CompletableFuture<MailSubmissionReceipt>> attempts = new ArrayList<>();
			for (int index = 0; index < 12; index++) {
				final String tag = "accepted-" + index;
				attempts.add(mailer.sendMailAndGetReceiptAsync(email(tag)));
			}
			for (final CompletableFuture<MailSubmissionReceipt> attempt : attempts) {
				attempt.get(10, TimeUnit.SECONDS);
			}
			for (int index = 0; index < 4; index++) {
				final CompletableFuture<MailSubmissionReceipt> rejected = mailer.sendMailAndGetReceiptAsync(
						email("accepted-partial-" + index, "temporary-" + index));
				assertThat(rejected.handle((receipt, failure) -> failure).get(10, TimeUnit.SECONDS)).isNotNull();
				mailer.sendMailAndGetReceiptAsync(email("accepted-after-failure-" + index)).get(10, TimeUnit.SECONDS);
			}
			mailer.sendMailAndGetReceiptAsync(email("accepted-recovery")).get(10, TimeUnit.SECONDS);
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
					attempts.add(mailer.sendMailAndGetReceiptAsync(index == 1 ? email("accepted-" + tag)
							: email("accepted-" + tag, "temporary-" + tag)));
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
			final ExecutionException failedBatch = catchThrowableOfType(() -> mailer.sendMailsInSimpleBatch(
					List.of(email("accepted-first"), email("temporary-second"), email("untouched-third")), true)
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
						configuredMailer.get().sendMailAndGetReceiptSync(email("accepted-reentrant"));
					}
				}).buildMailer()) {
			configuredMailer.set(mailer);
			mailer.sendMailAndGetReceiptSync(email("accepted-first"));
			assertThat(receipts).hasSize(2);
			assertThat(server.connections.get()).isEqualTo(1);
		}
	}

	@Test
	void simpleBatchAndOpenConnectionPublishEachReceiptWhileSharingTheTransport() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (RecipientSmtpServer server = new RecipientSmtpServer(); Mailer mailer = builder(server)
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withMailSendObserver(outcomes::add).buildMailer()) {
			mailer.sendMailsInSimpleBatch(List.of(email("accepted-batch-one"), email("accepted-batch-two")), true).get(10, TimeUnit.SECONDS);
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
		final MailSubmissionException failure = catchThrowableOfType(() -> mailer.sendMailAndGetReceiptSync(email), MailSubmissionException.class);
		assertThat(failure).isNotNull();
		return failure;
	}

	private static MailerRegularBuilder<?> builder(final RecipientSmtpServer server) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer(InetAddress.getLoopbackAddress().getHostAddress(), server.port())
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

	/** A reusable SMTP connection is needed here to prove that replies remain local to each pooled attempt. */
	private static final class RecipientSmtpServer implements AutoCloseable {
		private final ServerSocket listener;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
		private final List<Throwable> errors = new CopyOnWriteArrayList<>();
		private final AtomicInteger connections = new AtomicInteger();
		private final AtomicInteger messages = new AtomicInteger();
		private final CyclicBarrier replyBarrier;
		private volatile boolean closed;

		private RecipientSmtpServer() throws IOException {
			this(null);
		}

		private RecipientSmtpServer(final CyclicBarrier replyBarrier) throws IOException {
			this.replyBarrier = replyBarrier;
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
						firstRecipient = "";
						reply(server, "250 2.1.0 sender accepted");
					} else if (command.startsWith("RCPT TO:")) {
						final String recipient = command.substring(command.indexOf('<') + 1, command.indexOf('>'));
						if (firstRecipient.isEmpty()) {
							firstRecipient = recipient;
						}
						if (recipient.startsWith("rcpteof")) {
							return;
						}
						reply(server, recipientReply(recipient));
					} else if (command.equals("DATA")) {
						reply(server, "354 send content");
						String line;
						do {
							line = client.readLine();
						} while (line != null && !line.equals("."));
						if (line == null) {
							return;
						}
						messages.incrementAndGet();
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
