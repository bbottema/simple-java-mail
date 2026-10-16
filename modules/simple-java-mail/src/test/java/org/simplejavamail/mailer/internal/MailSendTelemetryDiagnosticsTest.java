package org.simplejavamail.mailer.internal;

import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MailSendTelemetryDiagnosticsTest {
    @ParameterizedTest
    @MethodSource("failurePhases")
    void telemetryCapturesEveryFailurePhaseWithoutBuildingPublicDiagnostics(final String phase,
            final Consumer<MailSendDiagnosticsRecorder> startPhase) {
        final AtomicLong clock = new AtomicLong();
        final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
        startPhase.accept(recorder);
        clock.set(25);
        final MailSendTelemetryReport telemetry = recorder.telemetryReport(false, false, null, new IllegalStateException("failed"));
        assertThat(telemetry.getFailurePhase()).isEqualTo(phase);
        assertThat(telemetry.getElapsed()).isEqualTo(Duration.ofNanos(25));
        final MailSendDiagnostics diagnostics = recorder.finish(false);
        assertThat(Stream.of(diagnostics.getPreparation(), diagnostics.getScheduling(), diagnostics.getRateLimitWait(),
                diagnostics.getConnectionAcquisition(), diagnostics.getMimePreparation(), diagnostics.getSubmission(),
                diagnostics.getRetryWait(), diagnostics.getCleanup()).filter(measurement -> measurement.isFailureObservedHere()).count()).isEqualTo(1);
    }

    private static Stream<Arguments> failurePhases() {
        return Stream.of(
                phase("preparation", MailSendDiagnosticsRecorder::startPreparation),
                phase("scheduling", recorder -> { recorder.useAsyncScheduling(); recorder.readyForAdmission(); }),
                phase("rate_limit_wait", MailSendDiagnosticsRecorder::startRateLimitWait),
                phase("connection_acquisition", MailSendDiagnosticsRecorder::startConnectionAcquisition),
                phase("mime_preparation", MailSendDiagnosticsRecorder::startMimePreparation),
                phase("submission", MailSendDiagnosticsRecorder::startSubmission),
                phase("retry_wait", MailSendDiagnosticsRecorder::startRetryWait),
                phase("cleanup", recorder -> { recorder.startConnectionAcquisition(); recorder.startCleanup(); }));
    }

    private static Arguments phase(final String name, final Consumer<MailSendDiagnosticsRecorder> start) {
        return Arguments.of(name, start);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothReportOrdersShareFrozenDurationsAndThePrimaryFailure(final boolean telemetryFirst) {
        final AtomicLong clock = new AtomicLong();
        final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(clock::get);
        recorder.startConnectionAcquisition();
        clock.set(20);
        recorder.startMimePreparation();
        clock.set(30);
        recorder.failed();
        recorder.startCleanup();
        clock.set(50);
        recorder.failed();
        recorder.cleanupCompleted();
        final MailSendDiagnostics diagnostics;
        final MailSendTelemetryReport telemetry;
        if (telemetryFirst) {
            telemetry = recorder.telemetryReport(false, false, null, null);
            clock.set(5000);
            diagnostics = recorder.finish(false);
        } else {
            diagnostics = recorder.finish(false);
            clock.set(5000);
            telemetry = recorder.telemetryReport(false, false, null, null);
        }
        assertThat(telemetry.getElapsed()).isEqualTo(Duration.ofNanos(50)).isEqualTo(diagnostics.getElapsed());
        assertThat(telemetry.getConnectionAcquisition()).isEqualTo(diagnostics.getConnectionAcquisition().getElapsed().orElseThrow());
        assertThat(telemetry.getMimePreparation()).isEqualTo(diagnostics.getMimePreparation().getElapsed().orElseThrow());
        assertThat(telemetry.getCleanup()).isEqualTo(diagnostics.getCleanup().getElapsed().orElseThrow());
        assertThat(telemetry.getFailurePhase()).isEqualTo("mime_preparation");
        assertThat(diagnostics.getMimePreparation().isFailureObservedHere()).isTrue();
        assertThat(diagnostics.getCleanup().isFailureObservedHere()).isFalse();
    }

    @Test
    void anUnmeasuredPrimaryFailureDoesNotMoveIntoLaterCleanup() {
        final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
        recorder.startConnectionAcquisition();
        recorder.connectionAcquired(mock(Transport.class));
        recorder.failed();
        recorder.startCleanup();
        recorder.failed();
        assertThat(recorder.telemetryReport(false, false, null, null).getFailurePhase()).isNull();
        assertThat(recorder.finish(false).getCleanup().isFailureObservedHere()).isFalse();
    }

    @Test
    void recoveredConnectionAndSubmissionFailuresDoNotSurviveIntoTheFinalReport() {
        final MailSendDiagnosticsRecorder recorder = new MailSendDiagnosticsRecorder(() -> 0);
        recorder.startConnectionAcquisition();
        recorder.failed();
        recorder.continuingAfterConnectionFailure();
        recorder.startSubmission();
        recorder.failed();
        recorder.continuingAfterSubmissionRefusal();
        assertThat(recorder.telemetryReport(true, false, null, null).getFailurePhase()).isNull();
    }
}
