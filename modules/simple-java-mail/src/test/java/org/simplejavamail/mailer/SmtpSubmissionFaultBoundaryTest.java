package org.simplejavamail.mailer;

import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.PARTIALLY_ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.UNKNOWN;

@Timeout(20)
class SmtpSubmissionFaultBoundaryTest {

	private static final int IO_TIMEOUT_MILLIS = 3000;

	@Test
	void disconnectWhileWaitingForMailFromReplyIsKnownToPrecedeMessageSubmission() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.DISCONNECT_AFTER_MAIL_FROM, false,
				"recipient@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(REJECTED);
		assertThat(failure.getSubmissionReceipt().hasServerAcceptanceInformation()).isTrue();
		assertThat(failure.getSubmissionReceipt().getValidUnsentRecipients()).containsExactly("recipient@example.org");
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).hasValueSatisfying(response -> {
			assertThat(response.getReturnCode()).isEqualTo(-1);
			assertThat(response.getResponse()).isEqualTo("[EOF]");
		});
		assertThat(failure.getCause()).isInstanceOfSatisfying(SMTPSendFailedException.class,
				providerFailure -> assertThat(providerFailure.getCommand()).startsWith("MAIL FROM:"));
	}

	@Test
	void disconnectWhileWaitingForRecipientReplyIsKnownToPrecedeMessageSubmission() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.DISCONNECT_AFTER_FIRST_RECIPIENT, false,
				"recipient@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(REJECTED);
		assertThat(failure.getSubmissionReceipt().hasServerAcceptanceInformation()).isTrue();
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).hasValueSatisfying(response -> {
			assertThat(response.getReturnCode()).isEqualTo(-1);
			assertThat(response.getResponse()).isEqualTo("[EOF]");
		});
		assertThat(failure.getCause()).isInstanceOfSatisfying(SMTPAddressFailedException.class,
				providerFailure -> assertThat(providerFailure.getCommand()).startsWith("RCPT TO:"));
	}

	@Test
	void resetWithoutAProviderResponseLeavesServerAcceptanceUnknown() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.DISCONNECT_DURING_MESSAGE_CONTENT, false,
				"recipient@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(UNKNOWN);
		assertThat(failure.getSubmissionReceipt().hasServerAcceptanceInformation()).isFalse();
		assertThat(failure.getSubmissionReceipt().isAcceptedByServer()).isFalse();
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getAcceptedRecipients()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getValidUnsentRecipients()).isEmpty();
		assertThat(failure.getCause())
				.isExactlyInstanceOf(MessagingException.class)
				.hasMessage("Exception reading response");
	}

	@Test
	void disconnectAfterDataTerminatorLeavesServerAcceptanceUnknown() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.DISCONNECT_AFTER_DATA_TERMINATOR, false,
				"recipient@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(UNKNOWN);
		assertThat(failure.getSubmissionReceipt().hasServerAcceptanceInformation()).isFalse();
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getAcceptedRecipients()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getValidUnsentRecipients()).isEmpty();
		assertThat(failure.getCause()).isInstanceOfSatisfying(SMTPSendFailedException.class, providerFailure -> {
			assertThat(providerFailure.getCommand()).isEqualTo(".");
			assertThat(providerFailure.getReturnCode()).isEqualTo(-1);
		});
	}

	@Test
	void finalReplyLossRetainsOnlyRecipientsRejectedBeforeData() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.REJECT_SECOND_RECIPIENT_THEN_DISCONNECT, true,
				"ambiguous@example.org", "rejected@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(UNKNOWN);
		assertThat(failure.getSubmissionReceipt().hasServerAcceptanceInformation()).isFalse();
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getAcceptedRecipients()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getValidUnsentRecipients()).isEmpty();
		assertThat(failure.getSubmissionReceipt().getInvalidRecipients()).containsExactly("rejected@example.org");
	}

	@Test
	void explicitFinalDataRejectionRemainsRejected() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.REJECT_MESSAGE_AFTER_DATA, false,
				"recipient@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(REJECTED);
		assertThat(failure.getSubmissionReceipt().getValidUnsentRecipients()).containsExactly("recipient@example.org");
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).hasValueSatisfying(response -> {
			assertThat(response.getReturnCode()).isEqualTo(550);
			assertThat(response.getResponse()).isEqualTo("550 5.7.1 message rejected\n");
		});
		assertThat(failure.getCause()).isInstanceOfSatisfying(SMTPSendFailedException.class, providerFailure -> {
			assertThat(providerFailure.getCommand()).isEqualTo(".");
			assertThat(providerFailure.getReturnCode()).isEqualTo(550);
		});
	}

	@Test
	void connectionCloseAfterFinalAcceptanceStillProducesAnAcceptedReceipt() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.ACCEPT_THEN_DISCONNECT, false,
				"recipient@example.org");

		final MailSubmissionReceipt receipt = attempt.requireReceipt();
		assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
		assertThat(receipt.getAcceptedRecipients()).containsExactly("recipient@example.org");
		assertThat(receipt.getSmtpResponse()).hasValueSatisfying(response -> {
			assertThat(response.getReturnCode()).isEqualTo(250);
			assertThat(response.getResponse()).isEqualTo("250 2.0.0 queued as fault-boundary-test\n");
		});
	}

	@Test
	void mixedRecipientRepliesRemainAvailableOnlyInTheAngusExceptionChain() throws Exception {
		final SubmissionAttempt attempt = submit(SmtpScenario.REJECT_SECOND_RECIPIENT, true,
				"accepted@example.org", "rejected@example.org");

		final MailSubmissionException failure = attempt.requireFailure();
		assertThat(failure.getStatus()).isEqualTo(PARTIALLY_ACCEPTED);
		assertThat(failure.getSubmissionReceipt().getAcceptedRecipients()).containsExactly("accepted@example.org");
		assertThat(failure.getSubmissionReceipt().getInvalidRecipients()).containsExactly("rejected@example.org");
		assertThat(failure.getSubmissionReceipt().getSmtpResponse()).hasValueSatisfying(response ->
				assertThat(response.getResponse()).isEqualTo("250 2.0.0 queued as fault-boundary-test\n"));

		final List<MessagingException> providerExceptions = providerExceptionChain(failure.getCause());
		assertThat(providerExceptions).filteredOn(SMTPAddressFailedException.class::isInstance)
				.singleElement()
				.isInstanceOfSatisfying(SMTPAddressFailedException.class, recipientFailure -> {
					assertThat(recipientFailure.getAddress().toString()).isEqualTo("rejected@example.org");
					assertThat(recipientFailure.getReturnCode()).isEqualTo(550);
					assertThat(recipientFailure.getMessage()).isEqualTo("550 5.1.1 no such recipient\n");
				});
	}

	private static SubmissionAttempt submit(final SmtpScenario scenario,
			final boolean sendPartially,
			final String... recipients) throws Exception {
		try (ScriptedSmtpServer smtpServer = new ScriptedSmtpServer(scenario, recipients.length)) {
			final MailerRegularBuilder<?> mailerBuilder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
					.withSMTPServer(InetAddress.getLoopbackAddress().getHostAddress(), smtpServer.getPort())
					.withProperty("mail.smtp.connectiontimeout", IO_TIMEOUT_MILLIS)
					.withProperty("mail.smtp.timeout", IO_TIMEOUT_MILLIS)
					.withProperty("mail.smtp.writetimeout", IO_TIMEOUT_MILLIS)
					.withProperty("mail.smtp.sendpartial", sendPartially);
			try (Mailer mailer = mailerBuilder.buildMailer()) {
				try {
					return SubmissionAttempt.succeeded(mailer.sendMailAndGetReceiptSync(emailFor(scenario, recipients)));
				} catch (final MailSubmissionException failure) {
					return SubmissionAttempt.failed(failure);
				}
			}
		}
	}

	private static Email emailFor(final SmtpScenario scenario, final String... recipients) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.from("sender@example.org")
				.withRecipients(EmailHelper.parsedRecipients(null, false, TO, recipients))
				.withSubject("SMTP fault boundary " + scenario)
				.withPlainText("Fault-boundary body")
				.buildEmail();
	}

	private static List<MessagingException> providerExceptionChain(final MessagingException rootFailure) {
		final List<MessagingException> providerExceptions = new ArrayList<>();
		Exception currentFailure = rootFailure;
		while (currentFailure instanceof MessagingException) {
			final MessagingException messagingFailure = (MessagingException) currentFailure;
			providerExceptions.add(messagingFailure);
			currentFailure = messagingFailure.getNextException();
		}
		return providerExceptions;
	}

	private enum SmtpScenario {
		DISCONNECT_AFTER_MAIL_FROM,
		DISCONNECT_AFTER_FIRST_RECIPIENT,
		DISCONNECT_DURING_MESSAGE_CONTENT,
		DISCONNECT_AFTER_DATA_TERMINATOR,
		ACCEPT_THEN_DISCONNECT,
		REJECT_SECOND_RECIPIENT,
		REJECT_SECOND_RECIPIENT_THEN_DISCONNECT,
		REJECT_MESSAGE_AFTER_DATA
	}

	private static final class SubmissionAttempt {

		private final MailSubmissionReceipt receipt;
		private final MailSubmissionException failure;

		private SubmissionAttempt(final MailSubmissionReceipt receipt, final MailSubmissionException failure) {
			this.receipt = receipt;
			this.failure = failure;
		}

		private static SubmissionAttempt succeeded(final MailSubmissionReceipt receipt) {
			return new SubmissionAttempt(receipt, null);
		}

		private static SubmissionAttempt failed(final MailSubmissionException failure) {
			return new SubmissionAttempt(null, failure);
		}

		private MailSubmissionReceipt requireReceipt() {
			assertThat(receipt).as("submission receipt").isNotNull();
			assertThat(failure).as("submission failure").isNull();
			return receipt;
		}

		private MailSubmissionException requireFailure() {
			assertThat(failure).as("submission failure").isNotNull();
			assertThat(receipt).as("submission receipt").isNull();
			return failure;
		}
	}

	private static final class ScriptedSmtpServer implements AutoCloseable {

		private final SmtpScenario scenario;
		private final int recipientCount;
		private final ServerSocket serverSocket;
		private final ExecutorService serverExecutor;
		private final Future<?> serverSession;
		private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
		private volatile boolean closing;

		private ScriptedSmtpServer(final SmtpScenario scenario, final int recipientCount) throws IOException {
			this.scenario = scenario;
			this.recipientCount = recipientCount;
			this.serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			this.serverExecutor = Executors.newSingleThreadExecutor(runnable -> {
				final Thread thread = new Thread(runnable, "scripted-smtp-peer");
				thread.setDaemon(true);
				return thread;
			});
			this.serverSession = serverExecutor.submit(this::serveOneSession);
		}

		private int getPort() {
			return serverSocket.getLocalPort();
		}

		private void serveOneSession() {
			try (Socket socket = serverSocket.accept()) {
				activeSocket.set(socket);
				socket.setSoTimeout(IO_TIMEOUT_MILLIS);
				conductSmtpConversation(socket);
			} catch (final SocketTimeoutException failure) {
				throw new AssertionError("Timed out waiting for the SMTP client", failure);
			} catch (final IOException failure) {
				if (!closing) {
					throw new AssertionError("Scripted SMTP server failed", failure);
				}
			} finally {
				activeSocket.set(null);
			}
		}

		private void conductSmtpConversation(final Socket socket) throws IOException {
			final BufferedReader client = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
			final BufferedWriter server = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));

			reply(server, "220 scripted.example ESMTP ready");
			requireCommand(client, "EHLO ");
			reply(server, "250-scripted.example", "250 ENHANCEDSTATUSCODES");

			requireCommand(client, "MAIL FROM:");
			if (scenario == SmtpScenario.DISCONNECT_AFTER_MAIL_FROM) {
				return;
			}
			reply(server, "250 2.1.0 sender accepted");

			for (int recipientIndex = 0; recipientIndex < recipientCount; recipientIndex++) {
				requireCommand(client, "RCPT TO:");
				if (scenario == SmtpScenario.DISCONNECT_AFTER_FIRST_RECIPIENT && recipientIndex == 0) {
					return;
				}
				if ((scenario == SmtpScenario.REJECT_SECOND_RECIPIENT
						|| scenario == SmtpScenario.REJECT_SECOND_RECIPIENT_THEN_DISCONNECT) && recipientIndex == 1) {
					reply(server, "550 5.1.1 no such recipient");
				} else {
					reply(server, "250 2.1.5 recipient accepted");
				}
			}

			requireCommand(client, "DATA");
			reply(server, "354 End data with <CR><LF>.<CR><LF>");
			if (scenario == SmtpScenario.DISCONNECT_DURING_MESSAGE_CONTENT) {
				requireMessageContent(client);
				disconnectWithReset(socket);
				return;
			}

			readMessageThroughTerminator(client);
			if (scenario == SmtpScenario.DISCONNECT_AFTER_DATA_TERMINATOR
					|| scenario == SmtpScenario.REJECT_SECOND_RECIPIENT_THEN_DISCONNECT) {
				return;
			}
			if (scenario == SmtpScenario.REJECT_MESSAGE_AFTER_DATA) {
				reply(server, "550 5.7.1 message rejected");
				requireCommand(client, "RSET");
				reply(server, "250 2.0.0 transaction reset");
				return;
			}
			reply(server, "250 2.0.0 queued as fault-boundary-test");
		}

		private static void requireMessageContent(final BufferedReader client) throws IOException {
			final String firstContentLine = client.readLine();
			if (firstContentLine == null || ".".equals(firstContentLine)) {
				throw new AssertionError("Expected message content before disconnecting");
			}
		}

		private static void readMessageThroughTerminator(final BufferedReader client) throws IOException {
			String contentLine;
			do {
				contentLine = client.readLine();
				if (contentLine == null) {
					throw new AssertionError("SMTP client disconnected before the DATA terminator");
				}
			} while (!".".equals(contentLine));
		}

		private static void requireCommand(final BufferedReader client, final String expectedPrefix) throws IOException {
			final String command = client.readLine();
			if (command == null || !command.startsWith(expectedPrefix)) {
				throw new AssertionError("Expected SMTP command starting with '" + expectedPrefix + "', but received: " + command);
			}
		}

		private static void reply(final BufferedWriter server, final String... lines) throws IOException {
			for (final String line : lines) {
				server.write(line);
				server.write("\r\n");
			}
			server.flush();
		}

		private static void disconnectWithReset(final Socket socket) throws IOException {
			socket.setSoLinger(true, 0);
			socket.close();
		}

		@Override
		public void close() throws IOException {
			closing = true;
			try {
				serverSession.get(10, TimeUnit.SECONDS);
			} catch (final InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while awaiting the scripted SMTP conversation", failure);
			} catch (final TimeoutException failure) {
				throw new AssertionError("Scripted SMTP conversation did not finish", failure);
			} catch (final ExecutionException failure) {
				throw new AssertionError("Scripted SMTP conversation failed", failure.getCause());
			} finally {
				final Socket socket = activeSocket.get();
				if (socket != null) {
					socket.close();
				}
				serverSocket.close();
				serverExecutor.shutdownNow();
			}
		}
	}
}
