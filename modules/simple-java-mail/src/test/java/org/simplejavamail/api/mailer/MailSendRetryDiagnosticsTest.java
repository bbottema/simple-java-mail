package org.simplejavamail.api.mailer;

import org.simplejavamail.mailer.internal.MailSendSubmissionStepImpl;

import org.simplejavamail.mailer.internal.MailSendMeasurementImpl;

import org.junit.jupiter.api.Test;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl;
import org.simplejavamail.mailer.internal.MailSendDiagnosticsImpl.MailSendDiagnosticsImplBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSendRetryDiagnosticsTest {
    @Test
    void copiesRendersAndSerializesBoundedSubmissionHistory() throws Exception {
        final MailSendSubmissionStep refused = MailSendSubmissionStepImpl.builder()
            .attemptNumber(1)
            .submissionStatus(MailSubmissionStatus.REJECTED)
            .smtpReplyCode(451)
            .elapsed(Duration.ZERO)
            .retryDelay(Duration.ofSeconds(30))
            .explanation("temporary\nrefusal")
            .build();
        final MailSendSubmissionStep accepted = MailSendSubmissionStepImpl.builder()
            .attemptNumber(2)
            .submissionStatus(MailSubmissionStatus.ACCEPTED)
            .smtpReplyCode(250)
            .elapsed(Duration.ofMillis(20))
            .retryDelay(null)
            .explanation(null)
            .build();
        final List<MailSendSubmissionStep> source = new ArrayList<>(List.of(refused, accepted));
        final MailSendMeasurement measured = MailSendMeasurementImpl.builder()
            .elapsed(Duration.ZERO)
            .unavailableReason(null)
            .failureObservedHere(false)
            .build();
        final MailSendDiagnostics report = MailSendDiagnosticsImpl.builder()
            .elapsed(Duration.ofSeconds(31))
            .preparation(measured)
            .scheduling(measured)
            .connectionAcquisition(measured)
            .mimePreparation(measured)
            .submission(measured)
            .cleanup(measured)
            .smtpHost("secret-host")
            .smtpPort(587)
            .rateLimitWait(measured)
            .retryWait(measured)
            .submissionHistory(source)
            .omittedSubmissionSteps(3)
            .build();
        source.clear();
        assertThat(report.getSubmissionHistory()).containsExactly(refused, accepted);
        assertThatThrownBy(() -> report.getSubmissionHistory().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(report.toString()).contains("Attempt 1: REJECTED (SMTP 451)", "next delay=PT30S", "Attempt 2: ACCEPTED (SMTP 250)",
                "Intermediate submissions omitted: 3", "temporary\\nrefusal").doesNotContain("secret-host", "587", "temporary\nrefusal");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final MailSendDiagnostics copy = (MailSendDiagnostics) input.readObject();
            assertThat(copy.toString()).isEqualTo(report.toString());
            assertThat(copy.getSubmissionHistory().get(0).getExplanation()).isEqualTo(refused.getExplanation());
            assertThat(copy.getOmittedSubmissionSteps()).isEqualTo(3);
        }
    }

    @Test
    void absentObservationsAndInvalidStepDataRemainExplicit() {
        final MailSendMeasurement absent = MailSendMeasurementImpl.builder()
            .elapsed(null)
            .unavailableReason("not reached")
            .failureObservedHere(false)
            .build();
        final MailSendDiagnostics report = MailSendDiagnosticsImpl.builder()
            .elapsed(Duration.ZERO)
            .preparation(absent)
            .scheduling(absent)
            .connectionAcquisition(absent)
            .mimePreparation(absent)
            .submission(absent)
            .cleanup(absent)
            .build();
        assertThat(report.getSubmissionHistory()).isEmpty();
        assertThat(report.getRetryWait().getElapsed()).isEmpty();
        assertThat(report.getOmittedSubmissionSteps()).isZero();
        assertThatThrownBy(() -> MailSendSubmissionStepImpl.builder()
            .attemptNumber(0)
            .submissionStatus(MailSubmissionStatus.ACCEPTED)
            .smtpReplyCode(250)
            .elapsed(Duration.ZERO)
            .retryDelay(null)
            .explanation(null)
            .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailSendSubmissionStepImpl.builder()
            .attemptNumber(1)
            .submissionStatus(MailSubmissionStatus.ACCEPTED)
            .smtpReplyCode(-1)
            .elapsed(Duration.ZERO)
            .retryDelay(null)
            .explanation(null)
            .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailSendSubmissionStepImpl.builder()
            .attemptNumber(1)
            .submissionStatus(MailSubmissionStatus.ACCEPTED)
            .smtpReplyCode(null)
            .elapsed(Duration.ofNanos(-1))
            .retryDelay(null)
            .explanation(null)
            .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
