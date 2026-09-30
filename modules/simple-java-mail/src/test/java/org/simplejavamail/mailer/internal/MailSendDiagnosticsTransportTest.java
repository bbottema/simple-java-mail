package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.simplejavamail.recipient.RecipientBuilder.to;

class MailSendDiagnosticsTransportTest {
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void directCloseFailureRetainsAcceptanceAndTheCallerFacingFailure(final boolean asynchronous) throws Exception {
		final TransportState state = new TransportState();
		state.closeFailure = new MessagingException("close failed after acceptance");
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		try (MockedStatic<ModuleLoader> modules = withoutPool(); Mailer mailer = mailer(session(state), outcomes)) {
			final Throwable callerFailure = sendFailure(mailer, email("accepted"), asynchronous);
			final MailSendOutcome outcome = outcomes.get(0);
			assertThat(outcome.getFailure()).containsSame(callerFailure);
			assertThat(outcome.isSuccessful()).isFalse();
			assertThat(outcome.getSubmissionReceipt().orElseThrow().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
			assertThat(outcome.getDiagnostics().orElseThrow().getCleanup().isFailureObservedHere()).isTrue();
			assertThat(outcome.getDiagnostics().orElseThrow().getSubmission().isFailureObservedHere()).isFalse();
			assertThat(state.sent).isEqualTo(1);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void primarySubmissionFailureSurvivesSuppressedCloseFailure(final boolean asynchronous) throws Exception {
		final TransportState state = new TransportState();
		state.sendFailure = new MessagingException("submission lost");
		state.closeFailure = new MessagingException("secondary close failure");
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		try (MockedStatic<ModuleLoader> modules = withoutPool(); Mailer mailer = mailer(session(state), outcomes)) {
			final Throwable failure = sendFailure(mailer, email("failed"), asynchronous);
			assertThat(outcomes.get(0).getFailure()).containsSame(failure);
			assertThat(failure.getSuppressed()).contains(state.closeFailure);
			final MailSendDiagnostics report = outcomes.get(0).getDiagnostics().orElseThrow();
			assertThat(report.getSubmission().isFailureObservedHere()).isTrue();
			assertThat(report.getCleanup().isFailureObservedHere()).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void poolReleaseFailureRetainsTheSelectedSessionsAcceptance(final boolean asynchronous) throws Exception {
		final TransportState selectedState = new TransportState();
		selectedState.host = "selected-relay.example";
		selectedState.port = 2525;
		final Session selectedSession = session(selectedState);
		final Transport transport = selectedSession.getTransport();
		final BatchModule batch = mock(BatchModule.class);
		final LifecycleDelegatingTransport lease = mock(LifecycleDelegatingTransport.class);
		when(lease.getSessionUsedToObtainTransport()).thenReturn(selectedSession);
		when(lease.getTransport()).thenReturn(transport);
		when(batch.acquireTransport(any(), any(), anyBoolean(), any())).thenReturn(lease);
		when(batch.shutdownConnectionPools(any())).thenReturn(CompletableFuture.completedFuture(null));
		final RuntimeException releaseFailure = new IllegalStateException("lease release failed");
		doThrow(releaseFailure).when(lease).signalTransportUsed();
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
			modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
			try (Mailer selectedOwner = mailer(selectedSession, new ArrayList<>());
				 Mailer mailer = mailer(session(new TransportState()), outcomes)) {
				final Throwable callerFailure = sendFailure(mailer, email("release failure"), asynchronous);
				final MailSendOutcome outcome = outcomes.get(0);
				assertThat(outcome.getFailure()).containsSame(callerFailure);
				assertThat(callerFailure).hasCause(releaseFailure);
				assertThat(outcome.getSubmissionReceipt().orElseThrow().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
				final MailSendDiagnostics report = outcome.getDiagnostics().orElseThrow();
				assertThat(report.getSmtpHost()).contains("selected-relay.example");
				assertThat(report.getSmtpPort()).contains(2525);
				assertThat(report.getCleanup().isFailureObservedHere()).isTrue();
				assertThat(selectedState.sent).isEqualTo(1);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void acquisitionAndMimeFailuresDoNotPretendToReachSubmission(final boolean asynchronous) throws Exception {
		final TransportState state = new TransportState();
		state.connectFailure = new MessagingException("connection unavailable");
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		try (MockedStatic<ModuleLoader> modules = withoutPool(); Mailer mailer = mailer(session(state), outcomes)) {
			assertThat(sendFailure(mailer, email("connection failure"), asynchronous)).isNotNull();
			assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getConnectionAcquisition().isFailureObservedHere()).isTrue();
			assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmission().getElapsed()).isEmpty();
			state.connectFailure = null;
			try (MockedStatic<SessionBasedEmailToMimeMessageConverter> conversion = mockStatic(SessionBasedEmailToMimeMessageConverter.class)) {
				conversion.when(() -> SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(any(), any()))
						.thenThrow(new MessagingException("cannot prepare MIME"));
				assertThat(sendFailure(mailer, email("MIME failure"), asynchronous)).isNotNull();
			}
			final MailSendDiagnostics report = outcomes.get(1).getDiagnostics().orElseThrow();
			assertThat(report.getMimePreparation().isFailureObservedHere()).isTrue();
			assertThat(report.getSubmission().getElapsed()).isEmpty();
			assertThat(state.sent).isZero();
		}
	}

	@Test
	void simpleBatchAndOpenConnectionReportOnlyPerEmailWork() throws Exception {
		final TransportState state = new TransportState();
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		try (MockedStatic<ModuleLoader> modules = withoutPool(); Mailer mailer = mailer(session(state), outcomes)) {
			mailer.sync().sendMailsInSimpleBatch(Arrays.asList(email("batch one"), email("batch two")));
			mailer.withOpenConnection(sender -> {
				sender.sendMail(email("scope one"));
				assertThat(state.closed).isEqualTo(1); // Only the earlier batch has closed; observer already ran.
				sender.sendMail(email("scope two"));
			});
			assertThat(outcomes).hasSize(4).allSatisfy(outcome -> {
				final MailSendDiagnostics report = outcome.getDiagnostics().orElseThrow();
				assertThat(report.getScheduling().getElapsed()).isEmpty();
				assertThat(report.getConnectionAcquisition().getUnavailableReason()).get().asString().contains("outside");
				assertThat(report.getCleanup().getElapsed()).isEmpty();
				assertThat(report.getSubmission().getElapsed()).isPresent();
				assertThat(report.getSmtpHost()).contains(state.host);
			});
			assertThat(state.connected).isEqualTo(2);
			assertThat(state.closed).isEqualTo(2);
		}
	}

	@Test
	void observationDoesNotAddSerializationConnectionsOrSubmissions() throws Exception {
		final TransportState plain = new TransportState();
		final TransportState observed = new TransportState();
		final Email email = email("work counts");
		try (MockedStatic<ModuleLoader> modules = withoutPool();
			 Mailer unobservedMailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session(plain)).buildMailer();
			 Mailer observedMailer = mailer(session(observed), new ArrayList<>())) {
			unobservedMailer.sync().sendMail(email);
			observedMailer.sync().sendMail(email);
			assertThat(observed.connected).isEqualTo(plain.connected).isEqualTo(1);
			assertThat(observed.sent).isEqualTo(plain.sent).isEqualTo(1);
			assertThat(observed.serializations).isEqualTo(plain.serializations).isEqualTo(1);
			assertThat(observed.bytes).isEqualTo(plain.bytes);
		}
	}

	@Test
	void observationDoesNotReadAttachmentsAgain() throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final ByteArrayDataSource attachment = new ByteArrayDataSource(new byte[4096], "application/octet-stream") {
			@Override
			public InputStream getInputStream() throws IOException {
				reads.incrementAndGet();
				return super.getInputStream();
			}
		};
		final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().copying(email("attachment"))
				.withAttachment("attachment.bin", attachment).buildEmail();
		try (MockedStatic<ModuleLoader> modules = withoutPool();
			 Mailer unobserved = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session(new TransportState())).buildMailer();
			 Mailer observed = mailer(session(new TransportState()), new ArrayList<>())) {
			reads.set(0);
			unobserved.sync().sendMail(email);
			final int unobservedReads = reads.getAndSet(0);
			observed.sync().sendMail(email);
			assertThat(reads.get()).isEqualTo(unobservedReads).isPositive();
		}
	}

	private static MockedStatic<ModuleLoader> withoutPool() {
		final MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
		modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
		return modules;
	}

	private static Mailer mailer(final Session session, final List<MailSendOutcome> outcomes) {
		// Inline execution keeps fault injection deterministic while exercising the async completion contract as well as throwing sync calls.
		final ExecutorService executor = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(executor).execute(any());
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
				.withExecutorService(executor).withMailSendObserver(outcomes::add).buildMailer();
	}

	private static Throwable sendFailure(final Mailer mailer, final Email email, final boolean asynchronous) {
		return asynchronous ? mailer.async().sendMail(email).getCompletion().handle((receipt, failure) -> failure).join()
				: catchThrowable(() -> mailer.sync().sendMail(email));
	}

	private static Email email(final String subject) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().fixingMessageId("diagnostics@example.org")
				.fixingSentDate(Instant.EPOCH)
				.from("sender@example.org").withRecipients(to(null, "recipient@example.org")).withSubject(subject).withPlainText("diagnostic test").buildEmail();
	}

	private static Session session(final TransportState state) throws Exception {
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "configured-but-not-selected.example");
		properties.setProperty("mail.smtp.port", "25");
		properties.setProperty("mail.from", "sender@example.org");
		properties.put(TransportState.class.getName(), state);
		final Session session = Session.getInstance(properties);
		final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", DiagnosticTransport.class.getName(), "test", "1");
		session.addProvider(provider);
		session.setProvider(provider);
		return session;
	}

	public static final class DiagnosticTransport extends Transport {
		private final TransportState state;

		public DiagnosticTransport(final Session session, final URLName name) {
			super(session, name);
			state = (TransportState) session.getProperties().get(TransportState.class.getName());
		}

		@Override
		protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
			state.connected++;
			if (state.connectFailure != null) {
				throw state.connectFailure;
			}
			return true;
		}

		@Override
		public URLName getURLName() {
			return new URLName("smtp", state.host, state.port, "/private", "private-user", "private-password");
		}

		@Override
		public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
			state.sent++;
			if (state.sendFailure != null) {
				throw state.sendFailure;
			}
			try {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				message.writeTo(bytes);
				state.bytes = bytes.toByteArray();
				state.serializations++;
			} catch (IOException failure) {
				throw new MessagingException("test serialization", failure);
			}
		}

		@Override
		public void close() throws MessagingException {
			state.closed++;
			setConnected(false);
			if (state.closeFailure != null) {
				throw state.closeFailure;
			}
		}
	}

	private static final class TransportState {
		private String host = "actual-relay.example";
		private int port = 2525;
		private MessagingException connectFailure;
		private MessagingException sendFailure;
		private MessagingException closeFailure;
		private int connected;
		private int sent;
		private int closed;
		private int serializations;
		private byte[] bytes;
	}
}
