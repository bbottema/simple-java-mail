package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

class MailSendAttemptDiagnosticsTest {

	@Test
	void snapshotEndsBeforeObserverHandoffAndDeferredApplicationWork() {
		final AtomicLong clock = new AtomicLong(100);
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::incrementAndGet);
		final List<Runnable> pending = new ArrayList<>();
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		final AtomicLong beforeHandoff = new AtomicLong();
		final MailSendObserverNotifier notifier = new MailSendObserverNotifier(outcomes::add, notification -> {
			beforeHandoff.set(clock.get());
			clock.addAndGet(1000000); // Even a slow Executor.execute is outside the attempt snapshot.
			pending.add(notification);
		}, true);
		try (MockedStatic<MailSendDiagnosticsRecorder> recorders = mockStatic(MailSendDiagnosticsRecorder.class, CALLS_REAL_METHODS)) {
			recorders.when(MailSendDiagnosticsRecorder::observed).thenReturn(recorder);
			final MailSendAttempt attempt = notifier.beginAttempt(mock(Email.class));
			attempt.prepared(mock(Email.class));
			attempt.started();
			recorder.startMimePreparation();
			final MailSubmissionReceipt receipt = receipt();
			recorder.receiptProduced(receipt);
			attempt.completeSuccessfully(receipt);
			assertThat(outcomes).isEmpty();
			assertThat(recorder.capturedReceipt()).isNull();
			clock.addAndGet(1000000);
			pending.get(0).run();
			assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getElapsed())
					.isEqualTo(Duration.ofNanos(beforeHandoff.get() - 101));
			assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
		}
	}

	@Test
	void genericCleanupFailureRetainsTheExactProducedReceiptAndPublishesOnlyOnce() {
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		final MailSendAttempt attempt = new MailSendObserverNotifier(outcomes::add, false).beginAttempt(mock(Email.class));
		attempt.prepared(mock(Email.class));
		attempt.started();
		attempt.diagnostics().startConnectionAcquisition();
		attempt.diagnostics().startSubmission();
		final MailSubmissionReceipt receipt = receipt();
		attempt.diagnostics().receiptProduced(receipt);
		attempt.diagnostics().startCleanup();
		final RuntimeException failure = new IllegalStateException("cleanup failed");
		attempt.completeWithFailure(failure);
		attempt.completeSuccessfully(receipt);
		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).isSuccessful()).isFalse();
		assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
		assertThat(outcomes.get(0).getFailure()).containsSame(failure);
		assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getCleanup().isFailureObservedHere()).isTrue();
		assertThat(attempt.diagnostics().capturedReceipt()).isNull();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void exceptionReceiptsArePreservedOnlyAfterExecutionStarted(final boolean started) {
		final MailSubmissionReceipt exceptionReceipt = receipt();
		final RuntimeException[] failures = {
				new MailSubmissionException("submission failed", new MessagingException(), exceptionReceipt),
				new MailSendCancelledException(null, exceptionReceipt),
				new MailSendTimeoutException(null, exceptionReceipt)
		};
		for (RuntimeException failure : failures) {
			final List<MailSendOutcome> outcomes = new ArrayList<>();
			final MailSendAttempt attempt = new MailSendObserverNotifier(outcomes::add, false).beginAttempt(mock(Email.class));
			if (started) {
				attempt.prepared(mock(Email.class));
				attempt.started();
				attempt.diagnostics().receiptProduced(receipt()); // Must not replace the exception's exact facts.
			}
			attempt.completeWithFailure(failure);
			assertThat(outcomes.get(0).getFailure()).containsSame(failure);
			if (started) {
				assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(exceptionReceipt);
			} else {
				assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
			}
		}
	}

	@Test
	void unobservedAttemptsDoNotCreateARecorderOrReadItsClock() {
		try (MockedStatic<MailSendDiagnosticsRecorder> recorders = mockStatic(MailSendDiagnosticsRecorder.class, CALLS_REAL_METHODS)) {
			final MailSendAttempt attempt = new MailSendObserverNotifier(null, false).beginAttempt(mock(Email.class));
			attempt.prepared(mock(Email.class));
			attempt.started();
			attempt.completeSuccessfully(receipt());
			recorders.verify(MailSendDiagnosticsRecorder::observed, never());
			assertThat(attempt.diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
		}
	}

	private static MailSubmissionReceipt receipt() {
		return new MailSubmissionReceipt("test@example.org", null, Instant.EPOCH);
	}
}
