package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import testutil.ConfigLoaderTestHelper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.recipient.RecipientBuilder.to;

@Timeout(20)
class MailSendDiagnosticsSchedulingTest {
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancellationOrTimeoutBeforeExecutionEndsTheSchedulingMeasurement(final boolean timeout) throws Exception {
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		final CountDownLatch occupied = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		executor.execute(() -> occupy(occupied, release));
		assertThat(occupied.await(5, SECONDS)).isTrue();
		final MailerRegularBuilder<?> builder = builder().withExecutorService(executor).withMailSendObserver(outcomes::add);
		if (timeout) {
			builder.withMailSendTimeout(Duration.ofSeconds(1));
		}
		try (Mailer mailer = builder.buildMailer()) {
			final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
			if (!timeout) {
				send.requestCancellation();
			}
			final Throwable failure = send.getCompletion().handle((receipt, cause) -> cause).get(5, SECONDS);
			assertThat(failure).isInstanceOf(timeout ? MailSendTimeoutException.class : MailSendCancelledException.class);
			assertThat(outcomes).hasSize(1);
			final MailSendOutcome outcome = outcomes.get(0);
			assertThat(outcome.getFailure()).containsSame(failure);
			assertThat(outcome.getStartedAt()).isEmpty();
			assertThat(outcome.getSubmissionReceipt()).isEmpty();
			assertThat(outcome.getDiagnostics().orElseThrow().getScheduling().isFailureObservedHere()).isTrue();
			assertThat(outcome.getDiagnostics().orElseThrow().getMimePreparation().getElapsed()).isEmpty();
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void callerRunsExecutionMeasuresSchedulingWithoutWaitingForExecuteToReturn() throws Exception {
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
		final CountDownLatch occupied = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<Thread> callbackThread = new AtomicReference<>();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		executor.execute(() -> occupy(occupied, release));
		assertThat(occupied.await(5, SECONDS)).isTrue();
		try (Mailer mailer = builder().withExecutorService(executor).withMailSendObserver(outcome -> {
			callbackThread.set(Thread.currentThread());
			outcomes.add(outcome);
		}).buildMailer()) {
			final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
			assertThat(send.getCompletion()).isCompleted();
			assertThat(callbackThread).hasValue(Thread.currentThread());
			assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getScheduling().getElapsed()).isPresent();
			assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getPreparation().isFailureObservedHere()).isFalse();
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	private static void occupy(final CountDownLatch occupied, final CountDownLatch release) {
		occupied.countDown();
		try {
			release.await();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static MailerRegularBuilder<?> builder() {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withSMTPServer("localhost", 25)
				.withTransportModeLoggingOnly(true);
	}

	private static Email email() {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().from("sender@example.org")
				.withRecipients(to(null, "recipient@example.org")).withPlainText("scheduling diagnostics").buildEmail();
	}
}
