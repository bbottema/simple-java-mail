package org.simplejavamail.mailer;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;

class MailSubmissionPoolingTest {

	private static final String TRANSPORT_STATE_KEY = MailSubmissionPoolingTest.class.getName() + ".transportState";

	@BeforeEach
	void requireRealBatchModule() {
		assertThat(ModuleLoader.batchModuleAvailable())
				.as("These tests must exercise the real batch-module connection pool")
				.isTrue();
	}

	@Test
	void asyncPoolDoesNotLeakResponsesOrRecipientFactsAcrossReusedAndReplacementTransports() throws Exception {
		final PooledTransportState state = new PooledTransportState();
		final Session session = session(state);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final SendFailedException partialProviderFailure = new SendFailedException("partial failure", null,
				internetAddresses("accepted-current@example.com"), internetAddresses("unsent-current@example.com"),
				internetAddresses("invalid-current@example.com"));
		final MessagingException unknownProviderFailure = new MessagingException("connection dropped after DATA");

		state.plan("first success", Attempt.success(250, "250 queued first"));
		state.plan("partial after success", Attempt.failurePreservingPreviousResponse(partialProviderFailure));
		state.plan("replacement success", Attempt.success(250, "250 queued replacement"));
		state.plan("unknown after replacement", Attempt.failurePreservingPreviousResponse(unknownProviderFailure));

		final MailSubmissionReceipt firstReceipt;
		final MailSubmissionException partialFailure;
		final MailSubmissionReceipt replacementReceipt;
		final MailSubmissionException unknownFailure;
		try (Mailer mailer = pooledMailer(session, UUID.randomUUID(), 1, 2, outcomes::add)) {
			firstReceipt = mailer.sendMailAndGetReceipt(email("first success", "first@example.com"), true)
					.get(5, TimeUnit.SECONDS);
			partialFailure = submissionFailure(mailer.sendMailAndGetReceipt(email("partial after success",
					"accepted-current@example.com", "unsent-current@example.com", "invalid-current@example.com"), true));
			replacementReceipt = mailer.sendMailAndGetReceipt(email("replacement success", "replacement@example.com"), true)
					.get(5, TimeUnit.SECONDS);
			unknownFailure = submissionFailure(mailer.sendMailAndGetReceipt(
					email("unknown after replacement", "unknown@example.com"), true));
		}

		assertThat(state.transportId("first success")).isEqualTo(state.transportId("partial after success"));
		assertThat(state.transportId("replacement success")).isNotEqualTo(state.transportId("partial after success"));
		assertThat(state.transportId("replacement success")).isEqualTo(state.transportId("unknown after replacement"));
		assertThat(state.connectCount.get()).isGreaterThanOrEqualTo(2);
		assertThat(state.connectedTransportIds).contains(
				state.transportId("first success"), state.transportId("replacement success"));

		assertThat(firstReceipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
		assertThat(firstReceipt.getAcceptedRecipients()).containsExactly("first@example.com");
		assertThat(firstReceipt.getValidUnsentRecipients()).isEmpty();
		assertThat(firstReceipt.getInvalidRecipients()).isEmpty();
		assertThat(firstReceipt.getSmtpResponse()).get().extracting(response -> response.getResponse())
				.isEqualTo("250 queued first");

		assertThat(partialFailure.getCause()).isSameAs(partialProviderFailure);
		assertThat(partialFailure.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
		final MailSubmissionReceipt partialReceipt = partialFailure.getSubmissionReceipt();
		assertThat(partialReceipt.getSmtpResponse())
				.as("The preceding attempt's 250 response must not leak into this failure")
				.isEmpty();
		assertThat(partialReceipt.getAcceptedRecipients()).containsExactly("accepted-current@example.com");
		assertThat(partialReceipt.getValidUnsentRecipients()).containsExactly("unsent-current@example.com");
		assertThat(partialReceipt.getInvalidRecipients()).containsExactly("invalid-current@example.com");

		assertThat(replacementReceipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
		assertThat(replacementReceipt.getAcceptedRecipients()).containsExactly("replacement@example.com");
		assertThat(replacementReceipt.getValidUnsentRecipients()).isEmpty();
		assertThat(replacementReceipt.getInvalidRecipients()).isEmpty();
		assertThat(replacementReceipt.getSmtpResponse()).get().extracting(response -> response.getResponse())
				.isEqualTo("250 queued replacement");

		assertThat(unknownFailure.getCause()).isSameAs(unknownProviderFailure);
		assertThat(unknownFailure.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
		final MailSubmissionReceipt unknownReceipt = unknownFailure.getSubmissionReceipt();
		assertThat(unknownReceipt.getSmtpResponse())
				.as("The replacement transport's successful response must not leak into its next failure")
				.isEmpty();
		assertThat(unknownReceipt.getAcceptedRecipients()).isEmpty();
		assertThat(unknownReceipt.getValidUnsentRecipients()).isEmpty();
		assertThat(unknownReceipt.getInvalidRecipients()).isEmpty();

		assertThat(outcomes).hasSize(4);
		assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(firstReceipt);
		assertThat(outcomes.get(0).getFailure()).isEmpty();
		assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(partialReceipt);
		assertThat(outcomes.get(1).getFailure()).containsSame(partialFailure);
		assertThat(outcomes.get(2).getSubmissionReceipt()).containsSame(replacementReceipt);
		assertThat(outcomes.get(2).getFailure()).isEmpty();
		assertThat(outcomes.get(3).getSubmissionReceipt()).containsSame(unknownReceipt);
		assertThat(outcomes.get(3).getFailure()).containsSame(unknownFailure);
		assertThat(outcomes).extracting(outcome -> outcome.getSubmissionReceipt().orElseThrow().getAcceptedRecipients())
				.containsExactly(
						Collections.singletonList("first@example.com"),
						Collections.singletonList("accepted-current@example.com"),
						Collections.singletonList("replacement@example.com"),
						Collections.emptyList());
	}

	@Test
	void asyncPoolMapsRejectedFailureAndRecoversOnAReplacementTransport() throws Exception {
		final PooledTransportState state = new PooledTransportState();
		final Session session = session(state);
		final SendFailedException rejectedProviderFailure = new SendFailedException("all recipients rejected", null, null,
				internetAddresses("valid-unsent@example.com"), internetAddresses("invalid@example.com"));
		state.plan("rejected", Attempt.failure(rejectedProviderFailure, 550, "550 all recipients rejected"));
		state.plan("after rejection", Attempt.success(250, "250 queued after rejection"));

		final MailSubmissionException rejectedFailure;
		final MailSubmissionReceipt recoveryReceipt;
		try (Mailer mailer = pooledMailer(session, UUID.randomUUID(), 1, 2)) {
			rejectedFailure = submissionFailure(mailer.sendMailAndGetReceipt(
					email("rejected", "valid-unsent@example.com", "invalid@example.com"), true));
			recoveryReceipt = mailer.sendMailAndGetReceipt(email("after rejection", "recovery@example.com"), true)
					.get(5, TimeUnit.SECONDS);
		}

		assertThat(rejectedFailure.getCause()).isSameAs(rejectedProviderFailure);
		assertThat(rejectedFailure.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
		assertThat(rejectedFailure.getSubmissionReceipt().hasServerAcceptanceInformation()).isTrue();
		assertThat(rejectedFailure.getSubmissionReceipt().isAcceptedByServer()).isFalse();
		assertThat(rejectedFailure.getSubmissionReceipt().getAcceptedRecipients()).isEmpty();
		assertThat(rejectedFailure.getSubmissionReceipt().getValidUnsentRecipients())
				.containsExactly("valid-unsent@example.com");
		assertThat(rejectedFailure.getSubmissionReceipt().getInvalidRecipients()).containsExactly("invalid@example.com");
		assertThat(rejectedFailure.getSubmissionReceipt().getSmtpResponse()).get()
				.extracting(response -> response.getResponse()).isEqualTo("550 all recipients rejected");

		assertThat(state.transportId("after rejection")).isNotEqualTo(state.transportId("rejected"));
		assertThat(recoveryReceipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
		assertThat(recoveryReceipt.getAcceptedRecipients()).containsExactly("recovery@example.com");
		assertThat(recoveryReceipt.getValidUnsentRecipients()).isEmpty();
		assertThat(recoveryReceipt.getInvalidRecipients()).isEmpty();
		assertThat(state.connectCount.get()).isGreaterThanOrEqualTo(2);
		assertThat(state.connectedTransportIds).contains(
				state.transportId("rejected"), state.transportId("after rejection"));
	}

	@Test
	void concurrentAsyncSubmissionsUseSeparateLeasesAndKeepTheirReceiptsCorrelated() throws Exception {
		final PooledTransportState state = new PooledTransportState();
		final Session session = session(state);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final CountDownLatch bothSending = new CountDownLatch(2);
		final CountDownLatch releaseSends = new CountDownLatch(1);
		state.plan("concurrent one", Attempt.blockingSuccess(250, "250 queued concurrent one", bothSending, releaseSends));
		state.plan("concurrent two", Attempt.blockingSuccess(250, "250 queued concurrent two", bothSending, releaseSends));

		final MailSubmissionReceipt firstReceipt;
		final MailSubmissionReceipt secondReceipt;
		try (Mailer mailer = pooledMailer(session, UUID.randomUUID(), 2, 2, outcomes::add)) {
			final CompletableFuture<MailSubmissionReceipt> first = mailer.sendMailAndGetReceipt(
					email("concurrent one", "one@example.com"), true);
			final CompletableFuture<MailSubmissionReceipt> second = mailer.sendMailAndGetReceipt(
					email("concurrent two", "two@example.com"), true);
			try {
				assertThat(bothSending.await(5, TimeUnit.SECONDS))
						.as("Both async sends should hold distinct pooled transport leases concurrently")
						.isTrue();
				assertThat(state.connectCount.get()).isGreaterThanOrEqualTo(2);
			} finally {
				releaseSends.countDown();
			}
			firstReceipt = first.get(5, TimeUnit.SECONDS);
			secondReceipt = second.get(5, TimeUnit.SECONDS);
		}

		assertThat(state.transportId("concurrent one")).isNotEqualTo(state.transportId("concurrent two"));
		assertThat(state.connectedTransportIds).contains(
				state.transportId("concurrent one"), state.transportId("concurrent two"));
		assertThat(firstReceipt.getAcceptedRecipients()).containsExactly("one@example.com");
		assertThat(firstReceipt.getSmtpResponse()).get().extracting(response -> response.getResponse())
				.isEqualTo("250 queued concurrent one");
		assertThat(secondReceipt.getAcceptedRecipients()).containsExactly("two@example.com");
		assertThat(secondReceipt.getSmtpResponse()).get().extracting(response -> response.getResponse())
				.isEqualTo("250 queued concurrent two");
		assertThat(outcomes).hasSize(2);
		assertThat(outcomes).extracting(outcome -> outcome.getSubmissionReceipt().orElseThrow())
				.containsExactlyInAnyOrder(firstReceipt, secondReceipt);
		assertThat(outcomes).extracting(outcome -> outcome.getSubmissionReceipt().orElseThrow().getAcceptedRecipients())
				.containsExactlyInAnyOrder(Collections.singletonList("one@example.com"), Collections.singletonList("two@example.com"));
	}

	@Test
	void observerRunsAfterPooledLeaseReleaseAndCanSendReentrantlyWithPoolSizeOne() throws Exception {
		final PooledTransportState state = new PooledTransportState();
		final Session session = session(state);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final AtomicReference<Mailer> mailerReference = new AtomicReference<>();
		final AtomicReference<MailSubmissionReceipt> nestedReceipt = new AtomicReference<>();
		final AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
		state.plan("outer send", Attempt.success(250, "250 queued outer"));
		state.plan("nested send", Attempt.success(250, "250 queued nested"));

		final MailSendObserver observer = outcome -> {
			outcomes.add(outcome);
			if ("outer-send@example.org".equals(outcome.getInitialMessageId())) {
				try {
					nestedReceipt.set(mailerReference.get().sendMailAndGetReceiptSync(
							emailWithMessageId("nested-send@example.org", "nested send", "nested@example.com")));
				} catch (final Throwable failure) {
					nestedFailure.set(failure);
				}
			}
		};

		final MailSubmissionReceipt outerReceipt;
		try (Mailer mailer = pooledMailer(session, UUID.randomUUID(), 1, 1, observer)) {
			mailerReference.set(mailer);
			outerReceipt = mailer.sendMailAndGetReceiptSync(
					emailWithMessageId("outer-send@example.org", "outer send", "outer@example.com"));
		}

		assertThat(nestedFailure.get()).isNull();
		assertThat(nestedReceipt.get()).isNotNull();
		assertThat(state.transportId("nested send")).isEqualTo(state.transportId("outer send"));
		assertThat(outerReceipt.getAcceptedRecipients()).containsExactly("outer@example.com");
		assertThat(nestedReceipt.get().getAcceptedRecipients()).containsExactly("nested@example.com");
		assertThat(outcomes).extracting(MailSendOutcome::getInitialMessageId)
				.containsExactly("outer-send@example.org", "nested-send@example.org");
	}

	@Test
	void separateAsyncPoolClustersNeverCrossSessionsOrSubmissionOutcomes() throws Exception {
		final PooledTransportState stateA = new PooledTransportState();
		final PooledTransportState stateB = new PooledTransportState();
		final Session sessionA = session(stateA);
		final Session sessionB = session(stateB);
		final List<CompletableFuture<MailSubmissionReceipt>> clusterAResults = new ArrayList<>();
		final List<CompletableFuture<MailSubmissionReceipt>> clusterBResults = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			stateA.plan("cluster-a-" + index, Attempt.success(250, "250 cluster A " + index));
			stateB.plan("cluster-b-" + index, Attempt.success(250, "250 cluster B " + index));
		}

		try (Mailer mailerA = pooledMailer(sessionA, UUID.randomUUID(), 2, 2);
			 Mailer mailerB = pooledMailer(sessionB, UUID.randomUUID(), 2, 2)) {
			for (int index = 0; index < 4; index++) {
				clusterAResults.add(mailerA.sendMailAndGetReceipt(
						email("cluster-a-" + index, "a" + index + "@example.com"), true));
				clusterBResults.add(mailerB.sendMailAndGetReceipt(
						email("cluster-b-" + index, "b" + index + "@example.com"), true));
			}
			CompletableFuture.allOf(clusterAResults.toArray(new CompletableFuture<?>[0])).get(5, TimeUnit.SECONDS);
			CompletableFuture.allOf(clusterBResults.toArray(new CompletableFuture<?>[0])).get(5, TimeUnit.SECONDS);
		}

		for (int index = 0; index < 4; index++) {
			final MailSubmissionReceipt receiptA = clusterAResults.get(index).get();
			final MailSubmissionReceipt receiptB = clusterBResults.get(index).get();
			assertThat(receiptA.getAcceptedRecipients()).containsExactly("a" + index + "@example.com");
			assertThat(receiptA.getSmtpResponse()).get().extracting(response -> response.getResponse())
					.isEqualTo("250 cluster A " + index);
			assertThat(receiptB.getAcceptedRecipients()).containsExactly("b" + index + "@example.com");
			assertThat(receiptB.getSmtpResponse()).get().extracting(response -> response.getResponse())
					.isEqualTo("250 cluster B " + index);
		}
		assertThat(stateA.submittedSubjects).allMatch(subject -> subject.startsWith("cluster-a-"));
		assertThat(stateB.submittedSubjects).allMatch(subject -> subject.startsWith("cluster-b-"));
	}

	private static Mailer pooledMailer(final Session session, final UUID clusterKey, final int maxPoolSize, final int threadPoolSize) {
		return pooledMailer(session, clusterKey, maxPoolSize, threadPoolSize, null);
	}

	private static Mailer pooledMailer(final Session session, final UUID clusterKey, final int maxPoolSize, final int threadPoolSize,
			@Nullable final MailSendObserver mailSendObserver) {
		final MailerFromSessionBuilder<?> builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
				.withClusterKey(clusterKey)
				.withConnectionPoolCoreSize(0)
				.withConnectionPoolMaxSize(maxPoolSize)
				.withConnectionPoolClaimTimeoutMillis(5000)
				.withConnectionPoolExpireAfterMillis(0)
				.withThreadPoolSize(threadPoolSize);
		if (mailSendObserver != null) {
			builder.withMailSendObserver(mailSendObserver);
		}
		return builder.buildMailer();
	}

	private static Session session(final PooledTransportState state) throws MessagingException {
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "localhost");
		properties.put(TRANSPORT_STATE_KEY, state);
		final Session session = Session.getInstance(properties);
		final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", PooledTestTransport.class.getName(),
				"Simple Java Mail", "test");
		session.addProvider(provider);
		session.setProvider(provider);
		return session;
	}

	private static Email email(final String subject, final String... recipients) {
		return emailWithMessageId(null, subject, recipients);
	}

	private static Email emailWithMessageId(@Nullable final String messageId, final String subject, final String... recipients) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.fixingMessageId(messageId)
				.from("sender@example.com")
				.withRecipients(EmailHelper.parsedRecipients(null, false, TO, recipients))
				.withSubject(subject)
				.withPlainText("Async pool submission body")
				.buildEmail();
	}

	private static Address[] internetAddresses(final String... mailboxAddresses) throws Exception {
		final Address[] internetAddresses = new Address[mailboxAddresses.length];
		for (int index = 0; index < mailboxAddresses.length; index++) {
			internetAddresses[index] = new InternetAddress(mailboxAddresses[index]);
		}
		return internetAddresses;
	}

	private static MailSubmissionException submissionFailure(
			final CompletableFuture<MailSubmissionReceipt> submission) throws Exception {
		try {
			submission.get(5, TimeUnit.SECONDS);
			throw new AssertionError("Expected asynchronous submission to fail");
		} catch (final ExecutionException expected) {
			assertThat(expected.getCause()).isInstanceOf(MailSubmissionException.class);
			return (MailSubmissionException) expected.getCause();
		}
	}

	public static final class PooledTestTransport extends SMTPTransport {

		private final PooledTransportState state;
		private final int transportId;
		private int lastReturnCode = -1;
		@Nullable private String lastServerResponse;

		public PooledTestTransport(final Session session, final URLName urlName) {
			super(session, urlName);
			state = (PooledTransportState) session.getProperties().get(TRANSPORT_STATE_KEY);
			transportId = state.createdTransportCount.incrementAndGet();
		}

		@Override
		protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
			state.connectCount.incrementAndGet();
			state.connectedTransportIds.add(transportId);
			return true;
		}

		@Override
		public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
			final String subject = message.getSubject();
			final Attempt attempt = state.take(subject);
			state.transportIdsBySubject.put(subject, transportId);
			state.submittedSubjects.add(subject);
			attempt.awaitRelease(subject);
			if (attempt.updateResponse) {
				lastReturnCode = attempt.returnCode;
				lastServerResponse = attempt.serverResponse;
			}
			if (attempt.failure != null) {
				throw attempt.failure;
			}
		}

		@Override
		public synchronized int getLastReturnCode() {
			return lastReturnCode;
		}

		@Override
		public synchronized String getLastServerResponse() {
			return lastServerResponse;
		}

		@Override
		public synchronized void close() {
			setConnected(false);
		}
	}

	private static final class PooledTransportState {

		private final AtomicInteger createdTransportCount = new AtomicInteger();
		private final AtomicInteger connectCount = new AtomicInteger();
		private final Set<Integer> connectedTransportIds = ConcurrentHashMap.newKeySet();
		private final Map<String, Attempt> attemptsBySubject = new ConcurrentHashMap<>();
		private final Map<String, Integer> transportIdsBySubject = new ConcurrentHashMap<>();
		private final List<String> submittedSubjects = new CopyOnWriteArrayList<>();

		private void plan(final String subject, final Attempt attempt) {
			assertThat(attemptsBySubject.put(subject, attempt))
					.as("No duplicate submission plan should exist for " + subject)
					.isNull();
		}

		private Attempt take(final String subject) throws MessagingException {
			final Attempt attempt = attemptsBySubject.remove(subject);
			if (attempt == null) {
				throw new MessagingException("No submission plan for subject " + subject);
			}
			return attempt;
		}

		private int transportId(final String subject) {
			final Integer transportId = transportIdsBySubject.get(subject);
			if (transportId == null) {
				throw new AssertionError("No transport recorded for subject " + subject);
			}
			return transportId;
		}
	}

	private static final class Attempt {

		@Nullable private final MessagingException failure;
		private final boolean updateResponse;
		private final int returnCode;
		@Nullable private final String serverResponse;
		@Nullable private final CountDownLatch started;
		@Nullable private final CountDownLatch release;

		private Attempt(@Nullable final MessagingException failure, final boolean updateResponse,
				final int returnCode, @Nullable final String serverResponse,
				@Nullable final CountDownLatch started, @Nullable final CountDownLatch release) {
			this.failure = failure;
			this.updateResponse = updateResponse;
			this.returnCode = returnCode;
			this.serverResponse = serverResponse;
			this.started = started;
			this.release = release;
		}

		private static Attempt success(final int returnCode, final String serverResponse) {
			return new Attempt(null, true, returnCode, serverResponse, null, null);
		}

		private static Attempt blockingSuccess(final int returnCode, final String serverResponse,
				final CountDownLatch started, final CountDownLatch release) {
			return new Attempt(null, true, returnCode, serverResponse, started, release);
		}

		private static Attempt failure(final MessagingException failure, final int returnCode, final String serverResponse) {
			return new Attempt(failure, true, returnCode, serverResponse, null, null);
		}

		private static Attempt failurePreservingPreviousResponse(final MessagingException failure) {
			return new Attempt(failure, false, -1, null, null, null);
		}

		private void awaitRelease(final String subject) throws MessagingException {
			if (started == null || release == null) {
				return;
			}
			started.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) {
					throw new MessagingException("Timed out waiting to release submission " + subject);
				}
			} catch (final InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new MessagingException("Interrupted while waiting to release submission " + subject, interrupted);
			}
		}
	}
}
