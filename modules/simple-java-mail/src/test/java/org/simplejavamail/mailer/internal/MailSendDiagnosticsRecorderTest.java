package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailRetryDisposition;

import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.util.Collections;

import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.SmtpServerResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailSendDiagnosticsRecorderTest {
	@Test
	void replacementWithoutEndpointMetadataDoesNotRetainEarlierConnectionFacts() {
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
		recorder.connectionAcquired(transport("first.example.test", 2525));
		recorder.connectionAcquired(mock(Transport.class));
		final MailSendDiagnostics report = recorder.finish(true);
		assertThat(report.getSmtpHost()).isEmpty();
		assertThat(report.getSmtpPort()).isEmpty();
	}
	@Test
	void providerSentinelCodesDoNotBecomeDiagnosticFailuresOrFakeReplyFacts() {
		final AtomicLong clock = new AtomicLong();
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.enableSubmissionHistory();
		recorder.startSubmission();
		recorder.submissionInvoked();
		final MailSubmissionReceipt receipt = MailSubmissionReceiptImpl.builder()
			.emailId(null)
			.smtpResponse(new SmtpServerResponse(-1, "EOF"))
			.submittedAt(Instant.EPOCH)
			.status((new SmtpServerResponse(-1, "EOF")).isPositiveCompletionReply() ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED)
			.recipientResults(Collections.emptyList())
			.retryDisposition((new SmtpServerResponse(-1, "EOF")).isPositiveCompletionReply() ? MailRetryDisposition.DO_NOT_RETRY : MailRetryDisposition.CALLER_POLICY_REQUIRED)
			.build();
		recorder.receiptProduced(receipt);
		recorder.submissionStep(1, receipt, null, false);
		assertThat(recorder.finish(true).getSubmissionHistory().get(0).getSmtpReplyCode()).isEmpty();
	}
	@Test
	void retryWaitAndActualInvocationHistoryAccumulateWithBoundedRetention() {
		final AtomicLong clock = new AtomicLong();
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.enableSubmissionHistory();
		for (int attempt = 1; attempt <= 80; attempt++) {
			recorder.startSubmission();
			recorder.submissionInvoked();
			clock.addAndGet(5);
			final MailSubmissionReceipt accepted = receipt();
			recorder.receiptProduced(accepted);
			recorder.submissionStep(attempt, accepted, attempt == 80 ? null : Duration.ofNanos(10), false);
			if (attempt != 80) {
				recorder.startRetryWait();
				clock.addAndGet(10);
				recorder.retryWaitCompleted();
			}
		}
		final MailSendDiagnostics report = recorder.finish(true);
		assertThat(report.getSubmission().getElapsed()).contains(Duration.ofNanos(400));
		assertThat(report.getRetryWait().getElapsed()).contains(Duration.ofNanos(790));
		assertThat(report.getSubmissionHistory()).hasSize(64);
		assertThat(report.getSubmissionHistory().get(62).getAttemptNumber()).isEqualTo(63);
		assertThat(report.getSubmissionHistory().get(63).getAttemptNumber()).isEqualTo(80);
		assertThat(report.getOmittedSubmissionSteps()).isEqualTo(16);
		assertThat(report.toString()).contains("Intermediate submissions omitted: 16");
	}
	@Test
	void historyRetainsFirst63AndLatestAndPreparationAccumulatesAcrossDestinations() {
		final AtomicLong clock = new AtomicLong();
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		for (int i = 0; i < 80; i++) {
			recorder.startPreparation();
			clock.addAndGet(2);
			recorder.prepared();
			recorder.beginConnectionStep("destination-" + i, null, null);
			clock.addAndGet(3);
			recorder.failedConnectionStep("Connection refused");
			recorder.continuingAfterConnectionFailure();
		}
		final MailSendDiagnostics report = recorder.finish(false);
		assertThat(report.getPreparation().getElapsed()).contains(Duration.ofNanos(160));
		assertThat(report.getConnectionHistory()).hasSize(64);
		assertThat(report.getConnectionHistory().get(62).getConfiguredName()).contains("destination-62");
		assertThat(report.getConnectionHistory().get(63).getConfiguredName()).contains("destination-79");
		assertThat(report.getOmittedConnectionSteps()).isEqualTo(16);
	}

	@Test
	void rateWaitingIsSeparateFromSelectionAndAcquisition() {
		final AtomicLong clock = new AtomicLong();
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.prepared();
		recorder.started();
		recorder.startConnectionAcquisition();
		clock.set(5);
		recorder.startRateLimitWait();
		clock.set(45);
		recorder.rateLimitWaitCompleted();
		clock.set(48);
		recorder.startConnectionAcquisition();
		clock.set(58);
		recorder.connectionAcquired(transport("relay", 25));
		final MailSendDiagnostics report = recorder.finish(true);
		assertThat(report.getRateLimitWait().getElapsed()).contains(Duration.ofNanos(40));
		assertThat(report.getConnectionAcquisition().getElapsed()).contains(Duration.ofNanos(15));
		assertThat(report.getElapsed()).isEqualTo(Duration.ofNanos(58));
	}

	@Test
	void usesMonotonicBoundariesWithOrchestrationGapsAndStopsBeforeNotification() {
		final AtomicLong clock = new AtomicLong(7);
		final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
		recorder.useAsyncScheduling();
		recorder.readyForAdmission();
		clock.set(17);
		recorder.started();
		recorder.startPreparation();
		clock.set(37);
		recorder.prepared();
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
		assertThat(report.getPreparation().getElapsed()).contains(Duration.ofNanos(20));
		assertThat(report.getScheduling().getElapsed()).contains(Duration.ofNanos(10));
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
		recorder.startPreparation();
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
		preparation.readyForAdmission();
		preparation.started();
		preparation.startPreparation();
		assertThat(preparation.finish(false).getPreparation().isFailureObservedHere()).isTrue();
		final MailSendDiagnosticsRecorder scheduling = new MailSendDiagnosticsRecorder(() -> 0);
		scheduling.useAsyncScheduling();
		scheduling.readyForAdmission();
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
		recorder.readyForAdmission();
		recorder.startPreparation();
		recorder.beginConnectionStep(null, null, null);
		recorder.failedConnectionStep("unobserved");
		recorder.skippedConnection(null, null, null, "unobserved");
		recorder.useLoggingOnly();
		recorder.useCustomMailer();
		recorder.useUnlimitedSending();
		recorder.startRateLimitWait();
		recorder.rateLimitWaitCompleted();
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
		return MailSubmissionReceiptImpl.builder()
			.emailId("message@example.org")
			.smtpResponse(null)
			.submittedAt(Instant.EPOCH)
			.status(MailSubmissionStatus.UNKNOWN)
			.recipientResults(Collections.emptyList())
			.retryDisposition(MailRetryDisposition.CALLER_POLICY_REQUIRED)
			.build();
	}
}
