package org.simplejavamail.otel;

import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(25)
class MailSendTelemetryMetricContextTest {
    private static final String METRIC_PREFIX = "simplejavamail.mail.";
    private static final AttributeKey<String> SUBMISSION_STATUS = AttributeKey.stringKey("simplejavamail.submission.status");
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void measurementsUseTheirStoredSpanRatherThanAnUnrelatedCurrentSpan() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final MailSendTelemetryBridge.Runtime runtime = ModuleLoader.loadOpenTelemetryModule().bind(OtelAdapter.wrap(telemetry.sdk), false);
            final MailSendTelemetryBridge.Attempt send = runtime.captureContext().beginSend(UUID.randomUUID());
            final Collection<MetricData> startedMetrics = telemetry.metrics.collectAllMetrics();
            final MailSendTelemetryBridge.Submission submission = send.beginSubmission(false);
            final Span unrelated = telemetry.sdk.getTracer("application").spanBuilder("unrelated work").startSpan();

            // Completion happens after SJM retires its thread scopes; it must not borrow this ambient span or attach another scope.
            try (Scope ignored = unrelated.makeCurrent()) {
                submission.complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
                send.complete(reportWithAllPhases());
                assertThat(Span.current()).isSameAs(unrelated);
            } finally {
                unrelated.end();
            }

            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            assertThat(sum(startedMetrics, "send.active")).isEqualTo(1);
            assertThat(metric(startedMetrics, "send.active").getData().getPoints())
                    .allSatisfy(point -> assertExemplarsUse(point, telemetry.sends().get(0).getSpanContext()));
            assertSendMeasurementsUse(metrics, telemetry.sends().get(0).getSpanContext());
            assertSubmissionMeasurementsUse(metrics, telemetry.submissions().get(0).getSpanContext());
            assertThat(metric(metrics, "send.phase.duration").getHistogramData().getPoints()).hasSize(8);
            assertCounts(metrics, 1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void syncAndAsyncMetricsLinkToTheirOwnSendAndSubmission(final boolean asynchronous) throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).buildMailer()) {
            final Span request = telemetry.sdk.getTracer("application").spanBuilder("request").startSpan();
            try (Scope ignored = request.makeCurrent()) {
                if (asynchronous) {
                    mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
                } else {
                    mailer.sync().sendMail(email());
                }
                assertThat(Span.current()).isSameAs(request);
            } finally {
                request.end();
            }

            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            assertSendMeasurementsUse(metrics, telemetry.sends().get(0).getSpanContext());
            assertSubmissionMeasurementsUse(metrics, telemetry.submissions().get(0).getSpanContext());
            assertCounts(metrics, 1);
        }
    }

    @Test
    void retriedSubmissionMetricsKeepTheirDistinctChildContexts() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(attempt -> attempt == 1 ? "451 retry later" : "250 queued", false);
             TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).withMailSendRetryPolicy(MailSendRetryPolicy.builder()
                     .withMaximumAttempts(3)
                     .withRetryDelay(Duration.ofMillis(20))
                     .build()).buildMailer()) {
            mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);

            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            final List<SpanData> submissions = telemetry.submissions();
            assertThat(submissions).hasSize(2);
            assertThat(submissions.get(0).getSpanContext()).isNotEqualTo(submissions.get(1).getSpanContext());
            assertSendMeasurementsUse(metrics, telemetry.sends().get(0).getSpanContext());
            for (String name : new String[] {"submission.completed", "submission.duration"}) {
                final Collection<? extends PointData> points = metric(metrics, name).getData().getPoints();
                assertThat(points).hasSize(2).allSatisfy(point -> {
                    final SpanData child = submissions.stream()
                            .filter(span -> span.getAttributes().get(SUBMISSION_STATUS).equals(point.getAttributes().get(SUBMISSION_STATUS)))
                            .findFirst()
                            .orElseThrow();
                    assertExemplarsUse(point, child.getSpanContext());
                });
            }
            assertCounts(metrics, 2);
        }
    }

    @Test
    void unsampledSendsStillRecordMetricsWithoutInventingExemplars() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk(Sampler.alwaysOff());
             Mailer mailer = builder(server.port(), telemetry).buildMailer()) {
            mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);

            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            assertCounts(metrics, 1);
            assertThat(telemetry.spans.getFinishedSpanItems()).isEmpty();
            assertThat(metrics).allSatisfy(metric -> assertThat(metric.getData().getPoints())
                    .allSatisfy(point -> assertThat(point.getExemplars()).isEmpty()));
        }
    }

    private static MailSendTelemetryReport reportWithAllPhases() {
        return MailSendTelemetryReport.builder()
                .successful(true)
                .submissionStatus(MailSubmissionStatus.ACCEPTED)
                .elapsed(Duration.ofSeconds(1))
                .preparation(Duration.ZERO)
                .scheduling(Duration.ofMillis(1))
                .rateLimitWait(Duration.ofMillis(2))
                .connectionAcquisition(Duration.ofMillis(3))
                .mimePreparation(Duration.ofMillis(4))
                .submission(Duration.ofMillis(5))
                .retryWait(Duration.ofMillis(6))
                .cleanup(Duration.ofMillis(7))
                .build();
    }

    private static void assertSendMeasurementsUse(final Collection<MetricData> metrics, final SpanContext sendContext) {
        for (String name : new String[] {"send.completed", "send.duration", "send.active", "send.phase.duration"}) {
            assertThat(metric(metrics, name).getData().getPoints()).as(name).isNotEmpty()
                    .allSatisfy(point -> assertExemplarsUse(point, sendContext));
        }
    }

    private static void assertSubmissionMeasurementsUse(final Collection<MetricData> metrics, final SpanContext submissionContext) {
        for (String name : new String[] {"submission.completed", "submission.duration"}) {
            assertThat(metric(metrics, name).getData().getPoints()).as(name).isNotEmpty()
                    .allSatisfy(point -> assertExemplarsUse(point, submissionContext));
        }
    }

    private static void assertExemplarsUse(final PointData point, final SpanContext expected) {
        assertThat(point.getExemplars()).as("sampled exemplars for %s", point.getAttributes()).isNotEmpty()
                .allSatisfy(exemplar -> assertThat(exemplar.getSpanContext()).isEqualTo(expected));
    }

    private static void assertCounts(final Collection<MetricData> metrics, final int submissions) {
        assertThat(metrics).extracting(MetricData::getName).containsExactlyInAnyOrder(
                METRIC_PREFIX + "send.completed", METRIC_PREFIX + "send.duration", METRIC_PREFIX + "send.active",
                METRIC_PREFIX + "submission.completed", METRIC_PREFIX + "submission.duration", METRIC_PREFIX + "send.phase.duration");
        assertThat(sum(metrics, "send.completed")).isEqualTo(1);
        assertThat(sum(metrics, "submission.completed")).isEqualTo(submissions);
        assertThat(sum(metrics, "send.active")).isZero();
        assertThat(metrics).allSatisfy(metric -> assertThat(metric.getData().getPoints()).allSatisfy(point ->
                assertThat(point.getAttributes().asMap().keySet()).allSatisfy(key -> assertThat(key.getKey())
                        .isIn("simplejavamail.send.outcome", "simplejavamail.submission.status", "simplejavamail.send.phase", "error.type"))));
    }

    private static long sum(final Collection<MetricData> metrics, final String name) {
        return metric(metrics, name).getLongSumData().getPoints().stream().mapToLong(point -> point.getValue()).sum();
    }

    private static MetricData metric(final Collection<MetricData> metrics, final String name) {
        // Reuse each collected snapshot: SDK exemplar reservoirs are drained by collection, unlike the cumulative counts.
        return metrics.stream().filter(metric -> metric.getName().equals(METRIC_PREFIX + name)).findFirst().orElseThrow();
    }

    private MailerRegularBuilder<?> builder(final int port, final TelemetryTestSdk telemetry) {
        return mail.mailerBuilder()
                .withSMTPServer("localhost", port)
                .withTransportStrategy(TransportStrategy.SMTP)
                .withTlsConfig(TlsConfig.builder().withOpportunisticTLS(false).build())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("telemetry.example.test").withSessionTimeout(Duration.ofMillis(5000)).build())
                .withProperty("mail.host", "localhost")
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(2).build())
                .withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(OtelAdapter.wrap(telemetry.sdk)).withEnabled(true).build());
    }

    private Email email() {
        return mail.emailBuilder().startingBlank()
                .from("private-sender@example.test")
                .withRecipients(mail.recipientBuilder()
                        .withAddress("private-recipient@example.test")
                        .withType(Message.RecipientType.TO)
                        .build())
                .withSubject("private subject")
                .withPlainText("private body")
                .buildEmail();
    }
}
