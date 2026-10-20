package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.internal.util.SmtpProbeReports;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResultAbsencePolicyTest {
    @TempDir Path output;

    @Test
    void callersReadScalarFactsDirectlyAndInspectWholeSectionsConditionally() throws Exception {
        final Path source = output.resolve("ResultReader.java");
        Files.writeString(source, "import org.simplejavamail.api.mailer.*; import java.time.*; import java.util.*;"
                + "class ResultReader { void inspect(MailSendOutcome outcome, MailSendDiagnostics diagnostics,"
                + " MailSendMeasurement measurement, MailSendConnectionStep connection, MailSendSubmissionStep submission,"
                + " MailSendProgress progress, MailRecipientResult recipient, SmtpConnectionReport probe,"
                + " SmtpCapabilities capabilities, SmtpTlsDetails tls) {"
                + " Instant ready = outcome.getReadyAt(); Instant started = outcome.getStartedAt();"
                + " String host = diagnostics.getSmtpHost(); Integer port = diagnostics.getSmtpPort();"
                + " Duration measured = measurement.getElapsed(); String unavailable = measurement.getUnavailableReason();"
                + " String name = connection.getConfiguredName(); String candidateHost = connection.getSmtpHost();"
                + " Integer candidatePort = connection.getSmtpPort(); Duration acquisition = connection.getElapsed();"
                + " String connectionExplanation = connection.getExplanation();"
                + " Integer code = submission.getSmtpReplyCode(); Duration delay = submission.getRetryDelay();"
                + " String submissionExplanation = submission.getExplanation();"
                + " Long written = progress.getBytesWritten(); Long total = progress.getTotalBytes();"
                + " String address = recipient.getEnvelopeAddress(); Boolean attempted = recipient.getRcptAttempted();"
                + " String greeting = probe.getGreeting(); String mechanism = probe.getAuthenticationMechanism();"
                + " SmtpConnectionPhase phase = probe.getFailurePhase(); String description = probe.getFailureDescription();"
                + " Integer probePort = probe.getPort(); Long maximum = capabilities.getMaximumMessageSize();"
                + " Boolean verifierAccepted = tls.getCustomHostnameVerifierAccepted();"
                + " Optional<MailSubmissionReceipt> receipt = outcome.getSubmissionReceipt();"
                + " Optional<MailSendDiagnostics> details = outcome.getDiagnostics(); Optional<Throwable> failure = outcome.getFailure();"
                + " Optional<SmtpServerResponse> response = recipient.getRcptResponse();"
                + " Optional<SmtpCapabilities> before = probe.getBeforeTls(); Optional<SmtpTlsDetails> handshake = probe.getTlsDetails();"
                + " Instant requested = outcome.getRequestedAt(); Duration elapsed = diagnostics.getElapsed();"
                + " MailSubmissionStatus status = submission.getSubmissionStatus(); List<MailSendConnectionStep> history = diagnostics.getConnectionHistory();"
                + " } }".replace("; ", ";\n"));
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(diagnostics, null, null)) {
            assertThat(ToolProvider.getSystemJavaCompiler().getTask(null, files, diagnostics,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, files.getJavaFileObjects(source)).call()).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
    }

    @Test
    void anUnspecifiedProviderPortIsAbsentWithoutGuessingAProtocolDefault() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "custom-provider");
        final SmtpConnectionReport report = SmtpProbeReports.begin(session(properties), false)
                .completedAt(Instant.EPOCH).build();
        assertThat((Object) report.getPort()).isNull();
        assertThat(report.getHost()).isEqualTo("localhost");
        assertThat(report.getProtocol()).isEqualTo("custom-provider");
        assertThat(report.getStartedAt()).isNotNull();
        assertThat(report.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(report.getWarnings()).isEmpty();
    }

    @Test
    void knownProbePortsAndProtocolDefaultsRemainFacts() {
        for (final String protocol : List.of("smtp", "smtps")) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", protocol);
            final SmtpConnectionReport report = SmtpProbeReports.begin(session(properties), false)
                    .completedAt(Instant.EPOCH).build();
            assertThat((Object) report.getPort()).isEqualTo(protocol.equals("smtp") ? 25 : 465);
        }
    }

    @Test
    void absentProbeScalarsDoNotEraseStructuredSections() {
        final SmtpConnectionReport report = SmtpConnectionReport.builder().host("smtp.example.org").port(587).protocol("smtp")
                .startedAt(Instant.EPOCH).completedAt(Instant.EPOCH).build();
        assertThat((Object) report.getGreeting()).isNull();
        assertThat((Object) report.getAuthenticationMechanism()).isNull();
        assertThat((Object) report.getFailurePhase()).isNull();
        assertThat((Object) report.getFailureDescription()).isNull();
        assertThat(report.getBeforeTls()).isEmpty();
        assertThat(report.getAfterTls()).isEmpty();
        assertThat(report.getTlsDetails()).isEmpty();
        assertThat(report.getEffectiveCapabilities()).isEmpty();
    }

    @Test
    void unknownRcptAttemptIsDistinctFromKnownFalse() {
        final MailRecipientResult unknown = new MailRecipientResult("reader@example.org", null, MailRecipientDisposition.UNKNOWN, null, null);
        final MailRecipientResult notAttempted = new MailRecipientResult("reader@example.org", "reader@example.org",
                MailRecipientDisposition.VALID_UNSENT, false, null);
        assertThat((Object) unknown.getEnvelopeAddress()).isNull();
        assertThat((Object) unknown.getRcptAttempted()).isNull();
        assertThat((Object) notAttempted.getRcptAttempted()).isEqualTo(Boolean.FALSE);
        assertThat((Object) notAttempted.getEnvelopeAddress()).isEqualTo("reader@example.org");
        assertThat(unknown.getRcptResponse()).isEmpty();
        assertThat(unknown.getRcptStatus()).isEqualTo(SmtpRecipientStatus.UNKNOWN);
        assertThat(notAttempted.getRcptStatus()).isEqualTo(SmtpRecipientStatus.NOT_ATTEMPTED);
    }

    @Test
    void missingOrUnusableSizeLimitIsNotAZeroMaximum() {
        assertThat((Object) new SmtpCapabilities(Map.of()).getMaximumMessageSize()).isNull();
        assertThat((Object) new SmtpCapabilities(Map.of("SIZE", List.of("0"))).getMaximumMessageSize()).isNull();
        assertThat((Object) new SmtpCapabilities(Map.of("SIZE", List.of("1000"))).getMaximumMessageSize()).isEqualTo(1000L);
    }

    @Test
    void unknownVerifierResultIsDistinctFromKnownRejection() {
        final SmtpTlsDetails unknown = new SmtpTlsDetails("TLSv1.3", "cipher", List.of(), true, null);
        final SmtpTlsDetails rejected = new SmtpTlsDetails("TLSv1.3", "cipher", List.of(), true, false);
        assertThat((Object) unknown.getCustomHostnameVerifierAccepted()).isNull();
        assertThat((Object) rejected.getCustomHostnameVerifierAccepted()).isEqualTo(Boolean.FALSE);
    }

    private static Session session(final Properties properties) {
        final Session session = mock(Session.class);
        when(session.getProperties()).thenReturn(properties);
        when(session.getProperty(anyString())).thenAnswer(call -> properties.getProperty(call.getArgument(0)));
        return session;
    }
}
