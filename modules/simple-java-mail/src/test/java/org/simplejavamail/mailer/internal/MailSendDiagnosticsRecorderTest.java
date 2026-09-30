package org.simplejavamail.mailer.internal;

import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailSendDiagnosticsRecorderTest {
	@Test
	void usesMonotonicBoundariesWithOrchestrationGapsAndStopsBeforeNotification() {
		final AtomicLong clock = new AtomicLong(7);
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.useAsyncScheduling();
		clock.set(17);
		recorder.prepared();
		clock.set(37);
		recorder.started();
		clock.set(41);
		recorder.startConnectionAcquisition();
		clock.set(81);
		recorder.connectionAcquired(transport("selected.example", 2525));
		clock.set(101);
		recorder.startMimePreparation();
		clock.set(131);
		recorder.startSubmission();
		clock.set(191);
		recorder.receiptProduced(receipt());
		clock.set(201);
		recorder.startCleanup();
		clock.set(221);
		recorder.cleanupCompleted();
		clock.set(241);
		final MailSendDiagnostics report = recorder.finish(true);
		clock.set(1000000); // Application observer work cannot change the frozen report.
		assertThat(report.getElapsed()).isEqualTo(Duration.ofNanos(234));
		assertThat(report.getPreparation().getElapsed()).contains(Duration.ofNanos(10));
		assertThat(report.getScheduling().getElapsed()).contains(Duration.ofNanos(20));
		assertThat(report.getConnectionAcquisition().getElapsed()).contains(Duration.ofNanos(40));
		assertThat(report.getMimePreparation().getElapsed()).contains(Duration.ofNanos(30));
		assertThat(report.getSubmission().getElapsed()).contains(Duration.ofNanos(60));
		assertThat(report.getCleanup().getElapsed()).contains(Duration.ofNanos(20));
		assertThat(report.getSmtpHost()).contains("selected.example");
		assertThat(report.getSmtpPort()).contains(2525);
	}

	@Test
	void elapsedSubtractionWorksAcrossMonotonicCounterWrap() {
		final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		clock.set(Long.MIN_VALUE + 4);
		assertThat(recorder.finish(false).getPreparation().getElapsed()).contains(Duration.ofNanos(10));
	}

	@Test
	void cleanupCannotReplaceSubmissionFailureAndDoesNotInventAReceipt() {
		final AtomicLong clock = new AtomicLong();
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.prepared();
		recorder.startConnectionAcquisition();
		recorder.connectionAcquired(transport("relay", -1));
		recorder.startSubmission();
		clock.set(10);
		recorder.failed();
		recorder.startCleanup();
		clock.set(25);
		recorder.failed();
		final MailSendDiagnostics report = recorder.finish(false);
		assertThat(report.getSubmission().isFailureObservedHere()).isTrue();
		assertThat(report.getSubmission().getElapsed()).contains(Duration.ofNanos(10));
		assertThat(report.getCleanup().isFailureObservedHere()).isFalse();
		assertThat(report.getCleanup().getElapsed()).contains(Duration.ofNanos(15));
		assertThat(report.getSmtpPort()).isEmpty();
		assertThat(recorder.capturedReceipt()).isNull();
	}

	@Test
	void failureInUnmeasuredGapIsNotMisattributedToLaterCleanup() {
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
		recorder.prepared();
		recorder.startConnectionAcquisition();
		recorder.connectionAcquired(transport("relay", 25));
		recorder.failed();
		recorder.startCleanup();
		recorder.failed();
		assertThat(recorder.finish(false).getCleanup().isFailureObservedHere()).isFalse();
	}

	@Test
	void preparationAndUnstartedSchedulingFailuresHaveDistinctLocations() {
		final MailSendDiagnosticsRecorder preparation = new MailSendDiagnosticsRecorder(() -> 0);
		preparation.useAsyncScheduling();
		assertThat(preparation.finish(false).getPreparation().isFailureObservedHere()).isTrue();
		final MailSendDiagnosticsRecorder scheduling = new MailSendDiagnosticsRecorder(() -> 0);
		scheduling.useAsyncScheduling();
		scheduling.prepared();
		final MailSendDiagnostics report = scheduling.finish(false);
		assertThat(report.getScheduling().getElapsed()).contains(Duration.ZERO);
		assertThat(report.getScheduling().isFailureObservedHere()).isTrue();
		assertThat(report.getSubmission().getElapsed()).isEmpty();
	}

	@Test
	void retainedReceiptAndSharedScopeDoNotNeedTransportOwnership() {
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
		recorder.useSharedConnection();
		recorder.prepared();
		recorder.started();
		recorder.connectionAcquired(transport("shared", 25));
		recorder.startMimePreparation();
		recorder.startSubmission();
		final MailSubmissionReceipt receipt = receipt();
		recorder.receiptProduced(receipt);
		recorder.startCleanup();
		final MailSendDiagnostics report = recorder.finish(true);
		assertThat(recorder.capturedReceipt()).isSameAs(receipt);
		assertThat(report.getConnectionAcquisition().getUnavailableReason()).get().asString().contains("outside");
		assertThat(report.getCleanup().getElapsed()).isEmpty();
		recorder.releaseReceipt();
		assertThat(recorder.capturedReceipt()).isNull();
	}

	@Test
	void endpointIsOptionalAndCannotBreakTheAttempt() {
		final Transport transport = mock(Transport.class);
		when(transport.getURLName()).thenThrow(new IllegalStateException("provider metadata unavailable"));
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
		recorder.connectionAcquired(transport);
		assertThat(recorder.finish(true).getSmtpHost()).isEmpty();
	}

	@Test
	void unobservedRecorderRemainsStatelessAndDoesNotReadProviderMetadata() {
		final MailSendDiagnosticsRecorder recorder = MailSendDiagnosticsRecorder.unobserved();
		final Transport transport = mock(Transport.class);
		recorder.useAsyncScheduling();
		recorder.useSharedConnection();
		recorder.useLoggingOnly();
		recorder.useCustomMailer();
		recorder.prepared();
		recorder.started();
		recorder.startConnectionAcquisition();
		recorder.connectionAcquired(transport);
		recorder.startMimePreparation();
		recorder.startSubmission();
		recorder.receiptProduced(receipt());
		recorder.failed();
		recorder.startCleanup();
		recorder.cleanupCompleted();
		assertThat(recorder.finish(false)).isNull();
		assertThat(recorder.capturedReceipt()).isNull();
		verifyNoInteractions(transport);
	}

	private static Transport transport(final String host, final int port) {
		final Transport transport = mock(Transport.class);
		when(transport.getURLName()).thenReturn(new URLName("smtp", host, port, "/secret-path", "secret-user", "secret-password"));
		return transport;
	}

	private static MailSubmissionReceipt receipt() {
		return new MailSubmissionReceipt("message@example.org", null, Instant.EPOCH);
	}
}
