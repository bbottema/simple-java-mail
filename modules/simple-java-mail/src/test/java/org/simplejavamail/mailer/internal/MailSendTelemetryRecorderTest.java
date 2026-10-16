package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static java.util.Collections.emptyList;

class MailSendTelemetryRecorderTest {
    @Test
    void disabledObservationDoesNotReadTheEmailOrCreateTimingAndScopes() throws Exception {
        final Email email = mock(Email.class);
        final MailSendAttempt attempt = MailSendObserverNotifier.builder().build().beginAttempt(email);
        assertThat(attempt.observesTelemetry()).isFalse();
        assertThat(attempt.attachTelemetry()).isNull();
        assertThat(attempt.diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
        assertThat(field(attempt, "observationId")).isNull();
        assertThat(field(attempt.diagnostics(), "clock")).isNull();
        verifyNoInteractions(email);
    }

    @Test
    void exceptionalSdkCallsCannotReplaceSendWorkAndReferencesAreRetired() throws Exception {
        final MailSendTelemetryBridge.CapturedContext captured = mock(MailSendTelemetryBridge.CapturedContext.class);
        final MailSendTelemetryBridge.Attempt parent = mock(MailSendTelemetryBridge.Attempt.class);
        final MailSendTelemetryBridge.Submission child = mock(MailSendTelemetryBridge.Submission.class);
        final MailSendTelemetryBridge.Scope parentScope = mock(MailSendTelemetryBridge.Scope.class);
        final MailSendTelemetryBridge.Scope childScope = mock(MailSendTelemetryBridge.Scope.class);
        when(captured.beginSend(any())).thenReturn(parent);
        when(parent.makeCurrent()).thenReturn(parentScope);
        when(parent.beginSubmission(false)).thenReturn(child);
        when(child.makeCurrent()).thenReturn(childScope);
        doThrow(new IllegalStateException("private SDK text")).when(childScope).close();
        doThrow(new IllegalStateException("private SDK text")).when(parentScope).close();
        doThrow(new IllegalStateException("private SDK text")).when(parent).complete(any());
        final MailSendTelemetryRecorder recorder = MailSendTelemetryRecorder.begin(captured, UUID.randomUUID());
        final MailSendTelemetryBridge.Scope work = recorder.attach();
        recorder.submissionInvoked(false);
        assertThatCode(() -> {
            recorder.submissionCompleted(true, MailSubmissionStatus.ACCEPTED, null, null, null);
            work.close();
            recorder.complete(report(true, null));
            recorder.complete(report(true, null));
        }).doesNotThrowAnyException();
        verify(child).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
        verify(parent, times(1)).complete(any());
        final var order = inOrder(childScope, parentScope);
        order.verify(childScope).close();
        order.verify(parentScope).close();
        for (String field : new String[] { "attempt", "submission", "submissionScope" }) {
            assertThat(field(recorder, field)).isNull();
        }
    }

    @Test
    void failedSubmissionAttachmentStillTracksTheActualInvocationResultAndRetiresReferences() throws Exception {
        final MailSendTelemetryBridge.CapturedContext captured = mock(MailSendTelemetryBridge.CapturedContext.class);
        final MailSendTelemetryBridge.Attempt parent = mock(MailSendTelemetryBridge.Attempt.class);
        final MailSendTelemetryBridge.Submission child = mock(MailSendTelemetryBridge.Submission.class);
        when(captured.beginSend(any())).thenReturn(parent);
        when(parent.beginSubmission(true)).thenReturn(child);
        when(child.makeCurrent()).thenThrow(new IllegalStateException("private SDK text"));
        final MailSendTelemetryRecorder recorder = MailSendTelemetryRecorder.begin(captured, UUID.randomUUID());
        assertThatCode(() -> recorder.submissionInvoked(true)).doesNotThrowAnyException();
        recorder.submissionCompleted(true, MailSubmissionStatus.ACCEPTED, null, null, null);
        recorder.complete(report(true, null));
        verify(child, times(1)).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
        verify(parent, times(1)).complete(any());
        assertThat(field(recorder, "submission")).isNull();
        assertThat(field(recorder, "submissionScope")).isNull();
    }

    @Test
    void acceptedReceiptAndCallerFailureSurviveCleanupFailureAndCompleteOnlyOnce() {
        final MailSendTelemetryBridge.Runtime runtime = mock(MailSendTelemetryBridge.Runtime.class);
        final MailSendTelemetryBridge.CapturedContext captured = mock(MailSendTelemetryBridge.CapturedContext.class);
        final MailSendTelemetryBridge.Attempt telemetry = mock(MailSendTelemetryBridge.Attempt.class);
        final MailSendTelemetryBridge.Submission submission = mock(MailSendTelemetryBridge.Submission.class);
        when(runtime.captureContext()).thenReturn(captured);
        when(captured.beginSend(any())).thenReturn(telemetry);
        when(telemetry.beginSubmission(anyBoolean())).thenReturn(submission);
        when(submission.makeCurrent()).thenReturn(() -> { });
        final AtomicReference<MailSendOutcome> outcome = new AtomicReference<>();
        final MailSendAttempt attempt = MailSendObserverNotifier.builder()
                .resultObserver(outcome::set)
                .telemetry(runtime)
                .build()
                .beginAttempt(SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank().buildEmail());
        final MailSubmissionReceipt receipt = MailSubmissionReceiptImpl.builder()
                .submittedAt(Instant.EPOCH)
                .status(MailSubmissionStatus.ACCEPTED)
                .recipientResults(emptyList())
                .retryDisposition(MailRetryDisposition.DO_NOT_RETRY)
                .build();
        final RuntimeException failure = new IllegalStateException("cleanup failed");
        attempt.diagnostics().startSubmission();
        attempt.diagnostics().submissionInvoked();
        attempt.diagnostics().submissionReturned(receipt, true);
        attempt.diagnostics().startCleanup();
        attempt.completeWithFailure(failure);
        attempt.completeWithFailure(failure);
        assertThat(outcome.get().getSubmissionReceipt()).containsSame(receipt);
        assertThat(outcome.get().getFailure()).containsSame(failure);
        verify(submission).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
        verify(telemetry).complete(argThat(report -> !report.isSuccessful()
                && report.getSubmissionStatus() == MailSubmissionStatus.ACCEPTED && report.getFailure() == failure));
    }

    @Test
    void telemetryOnlyMeasurementsDoNotRetainPublicHistoriesOrProgress() throws Exception {
        final MailSendTelemetryBridge.CapturedContext captured = mock(MailSendTelemetryBridge.CapturedContext.class);
        when(captured.beginSend(any())).thenReturn(mock(MailSendTelemetryBridge.Attempt.class));
        final MailSendTelemetryRecorder telemetry = MailSendTelemetryRecorder.begin(captured, UUID.randomUUID());
        final MailSendDiagnosticsRecorder diagnostics = MailSendDiagnosticsRecorder.forObservers(false, null, telemetry);
        diagnostics.enableSubmissionHistory();
        diagnostics.beginConnectionStep("relay", "localhost", 25);
        diagnostics.failedConnectionStep("test");
        diagnostics.skippedConnection("relay", "localhost", 25, "cached");
        assertThat(field(diagnostics, "connectionHistory")).isNull();
        assertThat(field(diagnostics, "submissionHistory")).isNull();
        assertThat(field(diagnostics, "progress")).isNull();
    }

    @Test
    void publicAndTelemetryReportsFreezeTheSameMonotonicTerminalTime() {
        final AtomicLong clock = new AtomicLong();
        final MailSendDiagnosticsRecorder diagnostics = new MailSendDiagnosticsRecorder(clock::get);
        diagnostics.startPreparation();
        clock.set(17);
        diagnostics.prepared();
        final Duration elapsed = diagnostics.finish(true).getElapsed();
        clock.set(100000);
        assertThat(diagnostics.telemetryReport(true, false, null, null).getElapsed()).isEqualTo(elapsed).isEqualTo(Duration.ofNanos(17));
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static MailSendTelemetryReport report(final boolean successful, final Throwable failure) {
        return MailSendTelemetryReport.builder()
                .successful(successful)
                .failure(failure)
                .elapsed(Duration.ZERO)
                .build();
    }
}
