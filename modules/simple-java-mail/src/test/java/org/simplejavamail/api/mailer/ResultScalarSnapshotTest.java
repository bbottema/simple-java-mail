package org.simplejavamail.api.mailer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultScalarSnapshotTest {
    @Test
    void unavailableFactsRemainAbsentWhenReadingPreChangeSnapshots() throws Exception {
        final Map<String, Object> values = beforeChange();
        final MailSendOutcome unstarted = (MailSendOutcome) values.get("unstarted");
        assertThat(unstarted.getReadyAt()).isNull();
        assertThat(unstarted.getStartedAt()).isNull();
        assertThat(unstarted.getObservationId()).isNull();
        assertThat(unstarted.getInitialMessageId()).isNull();
        assertThat(unstarted.getEffectiveMessageId()).isNull();
        final MailSendDiagnostics noEndpoint = (MailSendDiagnostics) values.get("noEndpoint");
        assertThat(noEndpoint.getSmtpHost()).isNull();
        assertThat(noEndpoint.getSmtpPort()).isNull();
        final MailSendMeasurement unavailable = (MailSendMeasurement) values.get("unavailable");
        assertThat(unavailable.getElapsed()).isNull();
        assertThat(unavailable.getUnavailableReason()).isEqualTo("Shared scope setup.");
        final MailSendConnectionStep skipped = (MailSendConnectionStep) values.get("skipped");
        assertThat(skipped.getConfiguredName()).isNull();
        assertThat(skipped.getSmtpHost()).isNull();
        assertThat(skipped.getSmtpPort()).isNull();
        assertThat(skipped.getElapsed()).isNull();
        assertThat(skipped.getExplanation()).isEqualTo("Cached failure.");
        final MailSendSubmissionStep unknownSubmission = (MailSendSubmissionStep) values.get("unknownSubmission");
        assertThat(unknownSubmission.getSmtpReplyCode()).isNull();
        assertThat(unknownSubmission.getRetryDelay()).isNull();
        assertThat(unknownSubmission.getExplanation()).isNull();
        final MailSendProgress noBytes = (MailSendProgress) values.get("noBytes");
        assertThat(noBytes.getBytesWritten()).isNull();
        assertThat(noBytes.getTotalBytes()).isNull();
        final SmtpConnectionReport failedProbe = (SmtpConnectionReport) values.get("failedProbe");
        assertThat(failedProbe.getPort()).isNull();
        assertThat(failedProbe.getGreeting()).isNull();
        assertThat(failedProbe.getAuthenticationMechanism()).isNull();
        assertThat(((SmtpCapabilities) values.get("noMaximum")).getMaximumMessageSize()).isNull();
        assertThat(((SmtpTlsDetails) values.get("noVerifier")).getCustomHostnameVerifierAccepted()).isNull();
    }

    @Test
    void knownZeroFalseAndEpochAreNotConfusedWithMissingFacts() throws Exception {
        final Map<String, Object> values = beforeChange();
        final MailSendMeasurement zero = (MailSendMeasurement) values.get("zero");
        assertThat(zero.getElapsed()).isEqualTo(Duration.ZERO);
        assertThat(zero.getUnavailableReason()).isNull();
        final MailSendOutcome outcome = (MailSendOutcome) values.get("outcome");
        assertThat(outcome.getReadyAt()).isEqualTo(Instant.EPOCH);
        assertThat(outcome.getStartedAt()).isEqualTo(Instant.EPOCH);
        assertThat(outcome.getRequestedAt()).isEqualTo(Instant.EPOCH);
        assertThat(outcome.getCompletedAt()).isEqualTo(Instant.EPOCH);
        final MailSendProgress progress = (MailSendProgress) values.get("progress");
        assertThat(progress.getBytesWritten()).isZero();
        assertThat(progress.getTotalBytes()).isZero();
        final MailRecipientResult notAttempted = (MailRecipientResult) values.get("notAttempted");
        final MailRecipientResult unknown = (MailRecipientResult) values.get("unknownRecipient");
        assertThat(notAttempted.getRcptAttempted()).isFalse();
        assertThat(unknown.getRcptAttempted()).isNull();
        assertThat(notAttempted.getRcptStatus()).isEqualTo(SmtpRecipientStatus.NOT_ATTEMPTED);
        assertThat(unknown.getRcptStatus()).isEqualTo(SmtpRecipientStatus.UNKNOWN);
        assertThat(((SmtpTlsDetails) values.get("tls")).getCustomHostnameVerifierAccepted()).isFalse();
        final MailSendSubmissionStep submission = (MailSendSubmissionStep) values.get("submission");
        assertThat(submission.getElapsed()).isEqualTo(Duration.ZERO);
        assertThat(submission.getRetryDelay()).isEqualTo(Duration.ZERO);
        assertThat(submission.getSmtpReplyCode()).isEqualTo(451);
    }

    @Test
    void wholeSectionsStayOptionalAndRetainIdentityAndProtocolFacts() throws Exception {
        final Map<String, Object> values = beforeChange();
        final MailSendOutcome outcome = (MailSendOutcome) values.get("outcome");
        assertThat(outcome.getDiagnostics()).containsSame((MailSendDiagnostics) values.get("diagnostics"));
        assertThat(outcome.getFailure()).isEmpty();
        final MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElseThrow();
        assertThat(receipt.getEmailId()).isEqualTo("<fixed@example.org>");
        assertThat(receipt.getEnvelopeId()).isEqualTo("attempt-1");
        assertThat(receipt.getMessageSize()).isZero();
        assertThat(receipt.getSmtpResponse().orElseThrow().getReturnCode()).isEqualTo(250);
        assertThat(receipt.isFullyAcceptedForSubmission()).isTrue();
        assertThat(receipt.getRecipientResults()).hasSize(1);
        final MailRecipientResult recipient = receipt.getRecipientResults().get(0);
        assertThat(recipient.getEnvelopeAddress()).isEqualTo("reader@example.org");
        assertThat(recipient.getRcptAttempted()).isTrue();
        assertThat(recipient.getRcptResponse().orElseThrow().getEnhancedStatusCode()).isPresent();
        final SmtpConnectionReport probe = (SmtpConnectionReport) values.get("probe");
        assertThat(probe.getBeforeTls()).isEmpty();
        assertThat(probe.getAfterTls()).containsSame((SmtpCapabilities) values.get("capabilities"));
        assertThat(probe.getTlsDetails()).containsSame((SmtpTlsDetails) values.get("tls"));
        assertThat(probe.getEffectiveCapabilities()).isEqualTo(probe.getAfterTls());
        assertThat(probe.getPort()).isEqualTo(587);
        assertThat(probe.getGreeting()).isEqualTo("220 smtp.example.org");
        assertThat(probe.getAuthenticationMechanism()).isEqualTo("XOAUTH2");
        final MailSendOutcome unstarted = (MailSendOutcome) values.get("unstarted");
        assertThat(unstarted.getSubmissionReceipt()).isEmpty();
        assertThat(unstarted.getDiagnostics()).isEmpty();
        assertThat(unstarted.getFailure()).hasValueSatisfying(failure ->
                assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("Admission rejected."));
    }

    @Test
    void writingNewSnapshotsPreservesFieldTypesAndRoundTripsAllResults() throws Exception {
        final Map<String, Object> values = beforeChange();
        for (final Object value : values.values()) {
            assertThat(ObjectStreamClass.lookup(value.getClass()).getSerialVersionUID()).as(value.getClass().getName()).isEqualTo(1L);
        }
        assertThat(ObjectStreamClass.lookup(SmtpConnectionReport.class).getField("port").getType()).isEqualTo(int.class);
        assertThat(ObjectStreamClass.lookup(values.get("outcome").getClass()).getField("readyAt").getType()).isEqualTo(Instant.class);
        assertThat(ObjectStreamClass.lookup(values.get("progress").getClass()).getField("bytesWritten").getType()).isEqualTo(Long.class);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(values); }
        final Map<String, Object> restored = read(bytes.toByteArray());
        assertThat(((MailSendOutcome) restored.get("outcome")).getStartedAt()).isEqualTo(Instant.EPOCH);
        assertThat(((MailSendOutcome) restored.get("unstarted")).getStartedAt()).isNull();
        assertThat(((MailSendProgress) restored.get("progress")).getBytesWritten()).isZero();
        assertThat(((MailSendProgress) restored.get("noBytes")).getBytesWritten()).isNull();
        assertThat(((SmtpConnectionReport) restored.get("failedProbe")).getPort()).isNull();
        assertThat(restored.keySet()).containsExactlyElementsOf(values.keySet());
    }

    private static Map<String, Object> beforeChange() throws Exception {
        try (InputStream fixture = ResultScalarSnapshotTest.class.getResourceAsStream("/outbound-results-before-nullable-scalars.base64")) {
            assertThat(fixture).isNotNull();
            return read(Base64.getMimeDecoder().decode(fixture.readAllBytes()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(final byte[] bytes) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (Map<String, Object>) input.readObject();
        }
    }
}
