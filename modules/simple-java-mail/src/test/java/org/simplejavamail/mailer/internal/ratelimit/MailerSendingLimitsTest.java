package org.simplejavamail.mailer.internal.ratelimit;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.MailerFromSessionBuilderImpl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Iterator;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.simplejavamail.recipient.RecipientBuilder.bcc;
import static org.simplejavamail.recipient.RecipientBuilder.to;

@Timeout(20)
class MailerSendingLimitsTest {

	private static final Duration PERIOD = Duration.ofHours(1);

	@Test
	void anotherMailerUsingTheSameSessionCannotReplaceTheInvokingMailersLimits() throws Exception {
		final Clock clock = new Clock();
		final AtomicInteger invoked = new AtomicInteger();
		final MailerFromSessionBuilderImpl firstBuilder = builder(new FactorySendingLimits(clock), new State());
		try (Mailer limited = firstBuilder.withMessageRateLimit(1, PERIOD).withCustomMailer(countingCustomMailer(invoked)).buildMailer();
			 Mailer unlimited = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(limited.getSession())
					.withCustomMailer(countingCustomMailer(invoked)).buildMailer()) {
			limited.sync().sendMail(email("limited"));
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> waiting = limited.async().sendMail(email("still limited"));
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				unlimited.sync().sendMail(email("independent"));
				assertThat(invoked.get()).isEqualTo(2);
				assertThat(waiting.getCompletion()).isNotDone();
			} finally {
				waiting.requestCancellation();
			}
			assertThat(waiting.getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS)).isInstanceOf(MailSendCancelledException.class);
		}
	}

	@Test
	void failedMailerConstructionDoesNotKeepItsProvisionalGroupRules() throws Exception {
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServer("localhost", 25).withTransportStrategy(TransportStrategy.SMTP_TLS)
				.withProperty("mail.smtp.starttls.required", "false").withRateLimitGroup("failed-build").withMessageRateLimit(30, PERIOD).buildMailer())
				.hasMessageContaining("requires STARTTLS");
		try (Mailer valid = factory.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true)
				.withRateLimitGroup("failed-build").withMessageRateLimit(60, PERIOD).buildMailer()) {
			assertThat(valid.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(60);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void reusedSessionKeepsLimitsAcrossIndividualAndRetainedSendPaths(final boolean custom) throws Exception {
		final State state = new State();
		final MailerFromSessionBuilderImpl configured = builder(new FactorySendingLimits(), state).withRecipientRateLimit(1, PERIOD);
		if (custom) {
			configured.withCustomMailer(countingCustomMailer(state.submissions));
		}
		try (Mailer limited = configured.buildMailer();
			 Mailer unlimited = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(limited.getSession())
					.withClusterKey(UUID.randomUUID()).withConnectionPoolCoreSize(0).buildMailer()) {
			final Email oversized = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("two recipients"))
					.withRecipients(bcc(null, "archive@example.org")).buildEmail();
			assertThatThrownBy(() -> limited.sync().sendMail(oversized)).hasMessageContaining("2 envelope recipients");
			assertThat(limited.async().sendMail(oversized).getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS))
					.hasMessageContaining("2 envelope recipients");
			assertThatThrownBy(() -> limited.sync().sendMailsInSimpleBatch(List.of(oversized))).hasRootCauseMessage(
					"This email has 2 envelope recipients, but the sending limit allows only 1 in a whole period. "
							+ "Use fewer recipients or increase the configured recipient limit; the email has not been submitted.");
			if (!custom) {
				assertThatThrownBy(() -> limited.withOpenConnection(sender -> sender.sendMail(oversized)))
						.hasMessageContaining("2 envelope recipients");
			}
			assertThat(state.submissions.get()).isZero();
			unlimited.sync().sendMail(oversized);
			assertThat(state.submissions.get()).isEqualTo(1);
		}
	}

	@Test
	void failedPoolRegistrationAlsoReleasesItsProvisionalGroup() throws Exception {
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
		final Session invalid = Session.getInstance(new Properties());
		invalid.getProperties().setProperty("mail.transport.protocol", "smtppool");
		assertThatThrownBy(() -> factory.mailerBuilder(invalid).withRateLimitGroup("failed-pool").withMessageRateLimit(1, PERIOD).buildMailer())
				.hasMessageContaining("exactly one component");
		try (Mailer valid = factory.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true)
				.withRateLimitGroup("failed-pool").withMessageRateLimit(2, PERIOD).buildMailer()) {
			assertThat(valid.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(2);
		}
	}

	@Test
	void gracefulShutdownWaitsForAnAcceptedRateWaiterWithoutDiscardingItsCharge() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final State state = new State();
		try (Mailer mailer = builder(limits, state).withMessageRateLimit(1, PERIOD).buildMailer()) {
			mailer.sync().sendMail(email("first"));
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> waiting = mailer.async().sendMail(email("drain me"));
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				final Future<Void> shutdown = mailer.shutdownConnectionPool();
				assertThat(shutdown.isDone()).isFalse();
				assertThat(state.submissions.get()).isEqualTo(1);
				clock.now.set(PERIOD.toNanos());
				limits.register("account", new SendingRateLimit(1, PERIOD), null, true, unused -> { }).wakeWaiters();
				assertThat(waiting.getCompletion().get(5, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
				shutdown.get(5, SECONDS);
				assertThat(state.submissions.get()).isEqualTo(2);
			} finally {
				waiting.requestCancellation();
			}
		}
	}

	@Test
	void cancellationDuringARetainedBatchWaitStopsBeforeTheNextEmailIsRead() throws Exception {
		final Clock clock = new Clock();
		final State state = new State();
		final AtomicInteger supplied = new AtomicInteger();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final Iterable<Email> emails = () -> new Iterator<>() {
			@Override public boolean hasNext() { return supplied.get() < 2; }
			@Override public Email next() { return email("retained " + supplied.incrementAndGet()); }
		};
		try (Mailer mailer = builder(new FactorySendingLimits(clock), state).withMessageRateLimit(1, PERIOD).withMailSendObserver(outcomes::add).buildMailer()) {
			mailer.sync().sendMail(email("exhaust allowance"));
			clock.reads.drainPermits();
			final MailSend<Void> batch = mailer.async().sendMailsInSimpleBatch(emails);
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				assertThat(state.connections.get()).isEqualTo(2); // The dedicated batch connection remains open while it waits.
				assertThat(batch.getCompletion()).isNotDone();
			} finally {
				batch.requestCancellation();
			}
			assertThat(batch.getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS)).isInstanceOf(MailSendCancelledException.class);
			assertThat(supplied.get()).isEqualTo(1);
			assertThat(state.submissions.get()).isEqualTo(1);
			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(1).getDiagnostics().orElseThrow().getRateLimitWait().isFailureObservedHere()).isTrue();
		}
	}

	@Test
	void pooledWaitHoldsNoLeaseAndCancellationDoesNotConsumeAllowance() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final State state = new State();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = builder(limits, state).withMessageRateLimit(1, PERIOD).withMailSendObserver(outcomes::add).buildMailer()) {
			mailer.sync().sendMail(email("first"));
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> waiting = mailer.async().sendMail(email("waiting"));
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				assertThat(state.connections.get()).isEqualTo(1);
				assertThat(state.submissions.get()).isEqualTo(1);
				final LifecycleDelegatingTransport available = ModuleLoader.loadBatchModule().acquireTransport(
						mailer.getOperationalConfig().getClusterKey(), mailer.getSession(), true, null);
				available.signalTransportUsed(); // Pool-size one still has its lease available while the send waits at the rate gate.
			} finally {
				waiting.requestCancellation();
			}
			assertThat(waiting.getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS)).isInstanceOf(MailSendCancelledException.class);
			assertThat(outcomes.get(1).getDiagnostics().orElseThrow().getRateLimitWait().isFailureObservedHere()).isTrue();
			assertThat(outcomes.get(1).getSubmissionReceipt()).isEmpty();
			clock.now.set(PERIOD.toNanos());
			mailer.sync().sendMail(email("after cancellation"));
			assertThat(state.connections.get()).isEqualTo(1);
			assertThat(state.submissions.get()).isEqualTo(2);
		}
	}

	@Test
	void failedAcquisitionAndMimePreparationReleaseReservationButProviderFailureStaysCharged() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final State state = new State();
		state.failConnect.set(1);
		try (Mailer mailer = builder(limits, state).withMessageRateLimit(1, PERIOD).withMaximumEmailSize(1000).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMail(email("connection failure"))).isInstanceOf(RuntimeException.class);
			final Email tooLarge = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("MIME failure"))
					.withPlainText("x".repeat(2000)).buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(tooLarge)).isInstanceOf(RuntimeException.class);
			state.failSend.set(1);
			assertThatThrownBy(() -> mailer.sync().sendMail(email("provider failure"))).isInstanceOf(RuntimeException.class);
			assertThat(state.submissions.get()).isEqualTo(1);
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> waiting = mailer.async().sendMail(email("charged"));
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				assertThat(waiting.getCompletion()).isNotDone();
			} finally {
				clock.now.set(PERIOD.toNanos());
				limits.register("account", new SendingRateLimit(1, PERIOD), null, true, unused -> { }).wakeWaiters();
			}
			assertThat(waiting.getCompletion().get(5, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
			assertThat(state.submissions.get()).isEqualTo(2);
		}
	}

	@Test
	void recipientCostIncludesBccAdditionalHeadersDuplicatesAndEnvelopeOverridesBeforeAcquisition() throws Exception {
		final State state = new State();
		final FactorySendingLimits limits = new FactorySendingLimits();
		try (Mailer mailer = builder(limits, state).withRecipientRateLimit(3, PERIOD).buildMailer()) {
			final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
			final Email fourRecipients = factory.emailBuilder().copying(email("duplicates"))
					.withRecipients(bcc(null, "archive@example.org"))
					.withHeader("To", "recipient@example.org, another@example.org").buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(fourRecipients)).hasMessageContaining("4 envelope recipients").hasMessageContaining("only 3");
			assertThat(state.connections.get()).isZero();
			final Email overridden = factory.emailBuilder().copying(fourRecipients).withOverrideReceivers(to(null, "only@example.org")).buildEmail();
			mailer.sync().sendMail(overridden);
			assertThat(state.envelopes.get(0)).containsExactly("only@example.org");
		}
	}

	@Test
	void retainedBatchAndOpenConnectionWaitPerEmailWithoutReconnecting() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final State state = new State();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = builder(limits, state).withMessageRateLimit(1, PERIOD).withMailSendObserver(outcome -> {
			outcomes.add(outcome);
			clock.now.addAndGet(PERIOD.toNanos());
		}).buildMailer()) {
			mailer.async().sendMailsInSimpleBatch(List.of(email("a"), email("b"))).getCompletion().get(5, SECONDS);
			mailer.withOpenConnection(sender -> {
				sender.sendMail(email("c"));
				sender.sendMail(email("d"));
			});
			assertThat(state.submissions.get()).isEqualTo(4);
			assertThat(state.connections.get()).isEqualTo(2);
			assertThat(outcomes).allSatisfy(outcome -> {
				assertThat(outcome.getDiagnostics().orElseThrow().getRateLimitWait().getElapsed()).isPresent();
				assertThat(outcome.getDiagnostics().orElseThrow().getConnectionAcquisition().getElapsed()).isEmpty();
			});
		}
	}

	@Test
	void namedHistorySurvivesParticipantClosureAndAnotherFactoryRemainsIndependent() throws Exception {
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
		final AtomicInteger invoked = new AtomicInteger();
		try (Mailer first = factory.mailerBuilder().withSMTPServer("localhost", 25).withRateLimitGroup("account").withMessageRateLimit(1, PERIOD)
				.withCustomMailer(countingCustomMailer(invoked)).buildMailer()) {
			first.sync().sendMail(email("first"));
		}
		try (Mailer replacement = factory.mailerBuilder().withSMTPServer("localhost", 25).withRateLimitGroup("account").withMessageRateLimit(1, PERIOD)
				.withCustomMailer(countingCustomMailer(invoked)).buildMailer();
			 Mailer independent = SimpleJavaMail.withConfig(factory.getConfig()).mailerBuilder().withSMTPServer("localhost", 25)
					.withRateLimitGroup("account").withMessageRateLimit(1, PERIOD)
					.withCustomMailer(countingCustomMailer(invoked)).buildMailer()) {
			final MailSend<MailSubmissionReceipt> waiting = replacement.async().sendMail(email("replacement"));
			try {
				independent.sync().sendMail(email("independent"));
			} finally {
				waiting.requestCancellation();
			}
			assertThat(waiting.getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS)).isInstanceOf(MailSendCancelledException.class);
			assertThat(invoked.get()).isEqualTo(2);
		}
	}

	@Test
	void selectedClusterConfigurationSuppliesTheRuleRatherThanTheInvokingMailer() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final UUID cluster = UUID.randomUUID();
		final State firstState = new State();
		final State secondState = new State();
		try (Mailer first = builder(limits, firstState).withClusterKey(cluster).withRateLimitGroup("first")
				.withMessageRateLimit(1, PERIOD).withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).buildMailer();
			 Mailer second = builder(limits, secondState).withClusterKey(cluster).withRateLimitGroup("second")
					.withMessageRateLimit(2, PERIOD).withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).buildMailer()) {
			first.sync().sendMail(email("one"));
			first.sync().sendMail(email("two"));
			assertThat(firstState.submissions.get()).isEqualTo(1);
			assertThat(secondState.submissions.get()).isEqualTo(1);
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> third = first.async().sendMail(email("three"));
			// Whichever destination round-robin started with, one of the next two hits the exhausted one-message configuration.
			final MailSend<MailSubmissionReceipt> fourth = first.async().sendMail(email("four"));
			try {
				assertThat(clock.reads.tryAcquire(2, 5, SECONDS)).isTrue();
				clock.now.set(PERIOD.toNanos());
				limits.register("first", new SendingRateLimit(1, PERIOD), null, true, unused -> { }).wakeWaiters();
				limits.register("second", new SendingRateLimit(2, PERIOD), null, true, unused -> { }).wakeWaiters();
				third.getCompletion().get(5, SECONDS);
				fourth.getCompletion().get(5, SECONDS);
			} finally {
				third.requestCancellation();
				fourth.requestCancellation();
			}
			assertThat(firstState.submissions.get()).isEqualTo(2);
			assertThat(secondState.submissions.get()).isEqualTo(2);
		}
	}

	@Test
	void waitingParticipantDoesNotStartItsAuthenticatedProxyOrResetTheClosedParticipantsHistory() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		try (Mailer original = builder(limits, new State()).withMessageRateLimit(1, PERIOD)
				.withCustomMailer(countingCustomMailer(new AtomicInteger())).buildMailer()) {
			original.sync().sendMail(email("original"));
		}
		final State state = new State();
		try (Mailer replacement = builder(limits, state).withMessageRateLimit(1, PERIOD)
				.withProxy("localhost", 1080, "proxy-user", "proxy-password").buildMailer()) {
			clock.reads.drainPermits();
			final MailSend<MailSubmissionReceipt> waiting = replacement.async().sendMail(email("waiting"));
			try {
				assertThat(clock.reads.tryAcquire(5, SECONDS)).isTrue();
				assertThat(waiting.getCompletion()).isNotDone();
				assertThat(replacement.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("0");
				assertThat(state.connections.get()).isZero();
				assertThat(state.submissions.get()).isZero();
			} finally {
				waiting.requestCancellation();
			}
			assertThat(waiting.getCompletion().handle((receipt, failure) -> failure).get(5, SECONDS))
					.isInstanceOf(MailSendCancelledException.class);
		}
	}

	@Test
	void localAdapterRejectionDoesNotConsumeAnAttempt() throws Exception {
		final State state = new State();
		try (Mailer mailer = builder(new FactorySendingLimits(), state).withMessageRateLimit(1, PERIOD).buildMailer()) {
			final Email unsupported = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("unsupported"))
					.withTlsRequiredForOnwardDelivery().buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(unsupported)).isInstanceOf(RuntimeException.class);
			mailer.sync().sendMail(email("ordinary"));
			assertThat(state.submissions.get()).isEqualTo(1);
		}
	}

	@Test
	void directSendsWithoutTheBatchModuleStillApplyLimitsBeforeConnecting() throws Exception {
		final Clock clock = new Clock();
		final State state = new State();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
			try (Mailer mailer = builder(new FactorySendingLimits(clock), state).withRecipientRateLimit(1, PERIOD)
					.withMailSendObserver(outcomes::add).buildMailer()) {
				final Email oversized = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("too many"))
						.withRecipients(bcc(null, "archive@example.org")).buildEmail();
				assertThatThrownBy(() -> mailer.sync().sendMail(oversized)).hasMessageContaining("2 envelope recipients");
				assertThat(state.connections.get()).isZero();
				mailer.sync().sendMail(email("first direct"));
				clock.now.set(PERIOD.toNanos());
				mailer.sync().sendMail(email("second direct"));
				assertThat(state.connections.get()).isEqualTo(2);
				assertThat(state.submissions.get()).isEqualTo(2);
				assertThat(clock.reads.availablePermits()).isEqualTo(4);
				assertThat(outcomes).filteredOn(MailSendOutcome::isSuccessful).hasSize(2)
						.allSatisfy(outcome -> assertThat(outcome.getDiagnostics().orElseThrow().getRateLimitWait().getElapsed()).isPresent());
			}
		}
	}

	private static CustomMailer countingCustomMailer(final AtomicInteger invoked) {
		final CustomMailer custom = mock(CustomMailer.class);
		doAnswer(invocation -> { invoked.incrementAndGet(); return null; }).when(custom).sendMessage(any(), any(), any(), any());
		return custom;
	}

	@Test
	void poolSizeOneObserverCanSendAgainAfterTheFirstReservationAndLeaseAreReleased() throws Exception {
		final State state = new State();
		final AtomicReference<Mailer> owner = new AtomicReference<>();
		final AtomicInteger observations = new AtomicInteger();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = builder(new FactorySendingLimits(() -> 0), state).withMessageRateLimit(2, PERIOD).withMailSendObserver(outcome -> {
			outcomes.add(outcome);
			if (observations.incrementAndGet() == 1) {
				owner.get().sync().sendMail(email("reentrant"));
			}
		}).buildMailer()) {
			owner.set(mailer);
			mailer.sync().sendMail(email("original"));
			assertThat(state.submissions.get()).isEqualTo(2);
			assertThat(state.connections.get()).isEqualTo(1);
			assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
		}
	}

	@Test
	void groupedHeaderMembersCountIndividuallyBeforeTransportAcquisition() throws Exception {
		final State state = new State();
		try (Mailer mailer = builder(new FactorySendingLimits(), state).withRecipientRateLimit(2, PERIOD).buildMailer()) {
			final Email grouped = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("grouped"))
					.withHeader("Cc", "Team: a@example.org, b@example.org;").buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(grouped)).hasMessageContaining("3 envelope recipients");
			assertThat(state.connections.get()).isZero();
			assertThat(state.submissions.get()).isZero();
		}
	}

	@Test
	void customBatchStopsAtFirstFailureWithoutReadingUntouchedEmails() throws Exception {
		final AtomicInteger supplied = new AtomicInteger();
		final AtomicInteger invoked = new AtomicInteger();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final CustomMailer custom = mock(CustomMailer.class);
		doAnswer(invocation -> {
			if (invoked.incrementAndGet() == 2) {
				throw new IllegalStateException("test callback rejection");
			}
			return null;
		}).when(custom).sendMessage(any(), any(), any(), any());
		final Iterable<Email> emails = () -> new Iterator<>() {
			@Override public boolean hasNext() { return supplied.get() < 3; }
			@Override public Email next() { return email("batch " + supplied.incrementAndGet()); }
		};
		try (Mailer mailer = builder(new FactorySendingLimits(() -> 0), new State()).withMessageRateLimit(2, PERIOD)
				.withCustomMailer(custom).withMailSendObserver(outcomes::add).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMailsInSimpleBatch(emails)).isInstanceOf(RuntimeException.class);
			assertThat(supplied.get()).isEqualTo(2);
			assertThat(invoked.get()).isEqualTo(2);
			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(0).isSuccessful()).isTrue();
			assertThat(outcomes.get(1).getDiagnostics().orElseThrow().getSubmission().isFailureObservedHere()).isTrue();
		}
	}

	@Test
	void loggingOnlyIgnoresLimitsAndAdmissionDoesNotReadAttachmentsAgain() throws Exception {
		final Clock clock = new Clock();
		final FactorySendingLimits limits = new FactorySendingLimits(clock);
		final State state = new State();
		final AtomicInteger reads = new AtomicInteger();
		final ByteArrayDataSource attachment = new ByteArrayDataSource(new byte[128], "application/octet-stream") {
			@Override public InputStream getInputStream() throws IOException {
				reads.incrementAndGet();
				return super.getInputStream();
			}
		};
		final Email attached = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().copying(email("attachment"))
				.withAttachment("file.bin", attachment).buildEmail();
		try (Mailer unbounded = builder(new FactorySendingLimits(), new State()).buildMailer();
			 Mailer limited = builder(limits, state).withMessageRateLimit(1, PERIOD).buildMailer()) {
			unbounded.sync().sendMail(attached);
			final int baselineReads = reads.getAndSet(0);
			limited.sync().sendMail(attached);
			assertThat(reads.get()).isEqualTo(baselineReads);
		}
		final Clock loggingClock = new Clock();
		try (Mailer logging = builder(new FactorySendingLimits(loggingClock), new State()).withMessageRateLimit(1, PERIOD)
				.withTransportModeLoggingOnly(true).buildMailer()) {
			logging.sync().sendMail(email("log a"));
			logging.sync().sendMail(email("log b"));
			assertThat(loggingClock.reads.availablePermits()).isZero();
		}
	}

	private static MailerFromSessionBuilderImpl builder(final FactorySendingLimits limits, final State state) throws Exception {
		final Properties properties = new Properties();
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "localhost");
		properties.setProperty("mail.smtp.port", "25");
		properties.setProperty("mail.from", "sender@example.org");
		properties.put(State.class.getName(), state);
		final Session session = Session.getInstance(properties);
		final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", RecordingTransport.class.getName(), "test", "1");
		session.addProvider(provider);
		session.setProvider(provider);
		return new MailerFromSessionBuilderImpl(ConfigLoader.builder().load(), limits).usingSession(session).withClusterKey(UUID.randomUUID())
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(1000).withRateLimitGroup("account");
	}

	private static Email email(final String subject) {
		return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(to(null, "recipient@example.org")).withSubject(subject).withPlainText("test")
				.fixingMessageId("rate-test@example.org").fixingSentDate(Instant.EPOCH).buildEmail();
	}

	private static final class Clock implements LongSupplier {
		private final AtomicLong now = new AtomicLong();
		private final Semaphore reads = new Semaphore(0);
		@Override public long getAsLong() {
			reads.release();
			return now.get();
		}
	}

	private static final class State {
		private final AtomicInteger connections = new AtomicInteger();
		private final AtomicInteger submissions = new AtomicInteger();
		private final AtomicInteger failConnect = new AtomicInteger();
		private final AtomicInteger failSend = new AtomicInteger();
		private final List<List<String>> envelopes = new CopyOnWriteArrayList<>();
	}

	public static final class RecordingTransport extends Transport {
		private final State state;
		public RecordingTransport(final Session session, final URLName name) {
			super(session, name);
			state = (State) session.getProperties().get(State.class.getName());
		}
		@Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
			if (state.failConnect.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				throw new MessagingException("test connection failure");
			}
			state.connections.incrementAndGet();
			return true;
		}
		@Override public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
			state.submissions.incrementAndGet();
			if (state.failSend.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				throw new MessagingException("test provider failure");
			}
			state.envelopes.add(stream(addresses).map(Object::toString).collect(toList()));
			try {
				message.writeTo(new ByteArrayOutputStream());
			} catch (IOException failure) {
				throw new MessagingException("test serialization", failure);
			}
		}
	}
}
