package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendObserverTest {

	@Test
	void builderRejectsNullAndUsesTheLastConfiguredObserver() throws Exception {
		final AtomicInteger replacedObserverCalls = new AtomicInteger();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final MailerRegularBuilder<?> builder = mailerBuilder(new SuccessfulCustomMailer());

		assertThatThrownBy(() -> builder.withMailSendObserver(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("mailSendObserver");

		try (Mailer mailer = builder
				.withMailSendObserver(outcome -> replacedObserverCalls.incrementAndGet())
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			mailer.sendMailSync(completeEmail("builder-observer@example.org", "builder observer"));
		}

		assertThat(replacedObserverCalls).hasValue(0);
		assertThat(outcomes).hasSize(1);
	}

	@Test
	void builderCallbackIsExcludedFromTheCliApi() throws Exception {
		final Method builderMethod = MailerGenericBuilder.class.getMethod("withMailSendObserver", MailSendObserver.class);

		assertThat(builderMethod.getAnnotation(Cli.ExcludeApi.class))
				.isNotNull()
				.extracting(Cli.ExcludeApi::reason)
				.asString()
				.contains("runtime Java callbacks");
	}

	@Test
	void synchronousSuccessReportsTheExactReceiptBeforeReturningOnTheCallerThread() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final AtomicReference<Thread> observerThread = new AtomicReference<>();
		final Thread callerThread = Thread.currentThread();
		final Email email = completeEmail(null, "sync observer");

		final MailSubmissionReceipt submissionReceipt;
		try (Mailer mailer = mailerBuilder(new SuccessfulCustomMailer())
				.withMailSendObserver(outcome -> {
					observerThread.set(Thread.currentThread());
					outcomes.add(outcome);
				})
				.buildMailer()) {
			submissionReceipt = mailer.sendMailAndGetReceiptSync(email);
			assertThat(outcomes).as("The observer runs before the synchronous call returns").hasSize(1);
		}

		final MailSendOutcome outcome = outcomes.get(0);
		assertThat(observerThread).hasValue(callerThread);
		assertThat(outcome.isSuccessful()).isTrue();
		assertThat(outcome.isLoggingOnly()).isFalse();
		assertThat(outcome.getInitialMessageId()).isNull();
		assertThat(outcome.getEffectiveMessageId()).isEqualTo(submissionReceipt.getEmailId()).isNotBlank();
		assertThat(outcome.getSubmissionReceipt()).containsSame(submissionReceipt);
		assertThat(outcome.getFailure()).isEmpty();
		assertCompleteTiming(outcome);
	}

	@Test
	void asynchronousSuccessNotifiesOnTheWorkerBeforeCompletingTheFuture() throws Exception {
		final BlockingCustomMailer customMailer = new BlockingCustomMailer();
		final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "mail-send-observer-worker"));
		final List<String> completionOrder = new CopyOnWriteArrayList<>();
		final AtomicReference<MailSendOutcome> observedOutcome = new AtomicReference<>();
		try (Mailer mailer = mailerBuilder(customMailer)
				.withExecutorService(executorService)
				.withMailSendObserver(outcome -> {
					observedOutcome.set(outcome);
					completionOrder.add("observer");
				})
				.buildMailer()) {
			final CompletableFuture<MailSubmissionReceipt> send = mailer.sendMailAndGetReceiptAsync(
					completeEmail("async-observer@example.org", "async observer"));
			assertThat(customMailer.awaitSendStarted()).isTrue();
			send.whenComplete((receipt, failure) -> completionOrder.add("future"));
			customMailer.releaseSend();

			final MailSubmissionReceipt submissionReceipt = send.get(5, TimeUnit.SECONDS);
			assertThat(completionOrder).containsExactly("observer", "future");
			assertThat(observedOutcome.get().getSubmissionReceipt()).containsSame(submissionReceipt);
			assertThat(observedOutcome.get().getStartedAt()).isPresent();
			assertThat(customMailer.sendThread.get().getName()).isEqualTo("mail-send-observer-worker");
		} finally {
			customMailer.releaseSend();
			executorService.shutdownNow();
		}
	}

	@Test
	void preparationFailureReportsTheExactFailureWithoutReadyOrStartedTimestamps() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final Email incompleteEmail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().buildEmail();
		try (Mailer mailer = mailerBuilder(new SuccessfulCustomMailer())
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			final RuntimeException synchronousFailure = synchronousFailure(() -> mailer.sendMailSync(incompleteEmail));
			assertPreparationFailure(outcomes.get(0), synchronousFailure);

			outcomes.clear();
			final CompletableFuture<Void> asynchronousSend = mailer.sendMailAsync(incompleteEmail);
			final Throwable asynchronousFailure = futureFailure(asynchronousSend);
			assertPreparationFailure(outcomes.get(0), asynchronousFailure);
		}
	}

	@Test
	void schedulingFailureReportsOneReadyButUnstartedAttemptAndLeavesBatchIterableLazy() throws Exception {
		final ExecutorService executorService = Executors.newSingleThreadExecutor();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final Thread callerThread = Thread.currentThread();
		final AtomicReference<Thread> callbackThread = new AtomicReference<>();
		try (Mailer mailer = mailerBuilder(new SuccessfulCustomMailer())
				.withExecutorService(executorService)
				.withMailSendObserver(outcome -> {
					callbackThread.set(Thread.currentThread());
					outcomes.add(outcome);
				})
				.buildMailer()) {
			executorService.shutdown();
			final Throwable schedulingFailure = futureFailure(mailer.sendMailAsync(
					completeEmail("scheduling-failure@example.org", "scheduling failure")));
			assertThat(outcomes).hasSize(1);
			final MailSendOutcome outcome = outcomes.get(0);
			assertThat(outcome.getFailure()).containsSame(schedulingFailure);
			assertThat(outcome.getReadyAt()).isPresent();
			assertThat(outcome.getStartedAt()).isEmpty();
			assertThat(outcome.getSubmissionReceipt()).isEmpty();
			assertThat(callbackThread).hasValue(callerThread);

			outcomes.clear();
			final CountingIterable emails = new CountingIterable(
					Collections.singletonList(completeEmail("lazy-batch@example.org", "lazy batch")));
			futureFailure(mailer.sendMailsInSimpleBatch(emails, true));
			assertThat(emails.iteratorCalls).hasValue(0);
			assertThat(outcomes).isEmpty();
		} finally {
			executorService.shutdownNow();
		}
	}

	@Test
	void loggingOnlySuccessIsExplicitAndStillCarriesAReceipt() throws Exception {
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", 0)
				.withTransportModeLoggingOnly(true)
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			mailer.sendMailSync(completeEmail("logging-only@example.org", "logging only"));
		}

		assertThat(outcomes).singleElement().satisfies(outcome -> {
			assertThat(outcome.isSuccessful()).isTrue();
			assertThat(outcome.isLoggingOnly()).isTrue();
			assertThat(outcome.getSubmissionReceipt()).get()
					.extracting(MailSubmissionReceipt::getStatus)
					.isEqualTo(MailSubmissionStatus.UNKNOWN);
		});
	}

	@Test
	void observerRuntimeExceptionNeverChangesSyncOrAsyncSendResults() throws Exception {
		final AtomicInteger observerCalls = new AtomicInteger();
		try (Mailer mailer = mailerBuilder(new SuccessfulCustomMailer())
				.withMailSendObserver(outcome -> {
					observerCalls.incrementAndGet();
					throw new IllegalStateException("observer unavailable");
				})
				.buildMailer()) {
			mailer.sendMailAndGetReceiptSync(completeEmail("observer-failure-sync@example.org", "sync observer failure"));
			mailer.sendMailAndGetReceiptAsync(completeEmail("observer-failure-async@example.org", "async observer failure"))
					.get(5, TimeUnit.SECONDS);
		}

		assertThat(observerCalls).hasValue(2);
	}

	@Test
	void simpleBatchReportsEachReachedEmailAndStopsObservingAtTheFirstFailure() throws Exception {
		final SuccessfulCustomMailer customMailer = new SuccessfulCustomMailer();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final Email first = completeEmail("batch-first@example.org", "batch first");
		final Email invalid = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.fixingMessageId("batch-invalid@example.org")
				.buildEmail();
		final Email untouched = completeEmail("batch-untouched@example.org", "batch untouched");

		try (Mailer mailer = mailerBuilder(customMailer)
				.withMailSendObserver(outcomes::add)
				.buildMailer()) {
			final RuntimeException batchFailure = synchronousFailure(() ->
					mailer.sendMailsInSimpleBatch(Arrays.asList(first, invalid, untouched), false));

			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(0).isSuccessful()).isTrue();
			assertThat(outcomes.get(0).getInitialMessageId()).isEqualTo("batch-first@example.org");
			assertThat(outcomes.get(1).isSuccessful()).isFalse();
			assertThat(outcomes.get(1).getInitialMessageId()).isEqualTo("batch-invalid@example.org");
			assertThat(outcomes.get(1).getFailure()).containsSame(batchFailure);
			assertThat(outcomes.get(1).getReadyAt()).isEmpty();
			assertThat(customMailer.sentSubjects).containsExactly("batch first");
		}
	}

	private static void assertCompleteTiming(final MailSendOutcome outcome) {
		assertThat(outcome.getReadyAt()).isPresent();
		assertThat(outcome.getStartedAt()).isPresent();
		assertThat(outcome.getRequestedAt()).isBeforeOrEqualTo(outcome.getReadyAt().get());
		assertThat(outcome.getReadyAt().get()).isBeforeOrEqualTo(outcome.getStartedAt().get());
		assertThat(outcome.getStartedAt().get()).isBeforeOrEqualTo(outcome.getCompletedAt());
	}

	private static void assertPreparationFailure(final MailSendOutcome outcome, final Throwable failure) {
		assertThat(outcome.isSuccessful()).isFalse();
		assertThat(outcome.getFailure()).containsSame(failure);
		assertThat(outcome.getSubmissionReceipt()).isEmpty();
		assertThat(outcome.getReadyAt()).isEmpty();
		assertThat(outcome.getStartedAt()).isEmpty();
		assertThat(outcome.getRequestedAt()).isBeforeOrEqualTo(outcome.getCompletedAt());
	}

	private static RuntimeException synchronousFailure(final Runnable send) {
		try {
			send.run();
			throw new AssertionError("Expected synchronous send to fail");
		} catch (final RuntimeException failure) {
			return failure;
		}
	}

	private static Throwable futureFailure(final CompletableFuture<?> future) throws Exception {
		try {
			future.get(5, TimeUnit.SECONDS);
			throw new AssertionError("Expected asynchronous send to fail");
		} catch (final ExecutionException failure) {
			return failure.getCause();
		}
	}

	private static MailerRegularBuilder<?> mailerBuilder(final CustomMailer customMailer) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", 0)
				.withCustomMailer(customMailer);
	}

	private static Email completeEmail(final String messageId, final String subject) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.fixingMessageId(messageId)
				.from("sender@example.org")
				.withRecipients(EmailHelper.parsedRecipients(null, false, TO, "recipient@example.org"))
				.withSubject(subject)
				.withPlainText("Mail send observer test body")
				.buildEmail();
	}

	private static class SuccessfulCustomMailer implements CustomMailer {

		private final List<String> sentSubjects = new CopyOnWriteArrayList<>();

		@Override
		public void testConnection(final OperationalConfig operationalConfig, final Session session) {
		}

		@Override
		public void sendMessage(final OperationalConfig operationalConfig, final Session session, final Email email, final MimeMessage message) {
			sentSubjects.add(email.getSubject());
		}
	}

	private static final class BlockingCustomMailer extends SuccessfulCustomMailer {

		private final CountDownLatch sendStarted = new CountDownLatch(1);
		private final CountDownLatch releaseSend = new CountDownLatch(1);
		private final AtomicReference<Thread> sendThread = new AtomicReference<>();

		@Override
		public void sendMessage(final OperationalConfig operationalConfig, final Session session, final Email email, final MimeMessage message) {
			sendThread.set(Thread.currentThread());
			sendStarted.countDown();
			try {
				if (!releaseSend.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting to release custom mailer");
				}
			} catch (final InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting to release custom mailer", interrupted);
			}
			super.sendMessage(operationalConfig, session, email, message);
		}

		private boolean awaitSendStarted() throws InterruptedException {
			return sendStarted.await(5, TimeUnit.SECONDS);
		}

		private void releaseSend() {
			releaseSend.countDown();
		}
	}

	private static final class CountingIterable implements Iterable<Email> {

		private final List<Email> emails;
		private final AtomicInteger iteratorCalls = new AtomicInteger();

		private CountingIterable(final List<Email> emails) {
			this.emails = emails;
		}

		@Override
		public Iterator<Email> iterator() {
			iteratorCalls.incrementAndGet();
			return emails.iterator();
		}
	}
}
