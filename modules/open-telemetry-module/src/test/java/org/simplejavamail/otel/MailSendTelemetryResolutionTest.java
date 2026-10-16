package org.simplejavamail.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MailSendTelemetryResolutionTest {
    private static final String SCOPE = "org.simplejavamail.mail";
    private static final String METRIC_PREFIX = "simplejavamail.mail.";
    private static final AttributeKey<String> PHASE = AttributeKey.stringKey("simplejavamail.send.phase");
    private static final AttributeKey<String> FAILURE_PHASE = AttributeKey.stringKey("simplejavamail.send.failure.phase");
    private static final List<Double> EXPECTED_BUCKETS = List.of(
            0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0, 30.0, 60.0, 120.0, 300.0);

    @Test
    void durationHistogramsSeparateShortSendsAndIncludeMultiMinuteWaits() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final MailSendTelemetryBridge.Runtime runtime = runtime(telemetry.sdk);
            completeSubmission(runtime, Duration.ofMillis(80));
            completeSubmission(runtime, Duration.ofMillis(600));
            runtime.captureContext().beginSend(UUID.randomUUID()).complete(MailSendTelemetryReport.builder()
                    .successful(true)
                    .elapsed(Duration.ofMinutes(3))
                    .retryWait(Duration.ofMinutes(3))
                    .build());

            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            for (String name : List.of("send.duration", "submission.duration", "send.phase.duration")) {
                assertThat(metric(metrics, name).getHistogramData().getPoints())
                        .allSatisfy(point -> assertThat(point.getBoundaries()).containsExactlyElementsOf(EXPECTED_BUCKETS));
            }
            final HistogramPointData sends = metric(metrics, "send.duration").getHistogramData().getPoints().stream()
                    .filter(point -> point.getCount() == 2).findFirst().orElseThrow();
            assertThat(sends.getCounts().get(EXPECTED_BUCKETS.indexOf(0.1))).isEqualTo(1L);
            assertThat(sends.getCounts().get(EXPECTED_BUCKETS.indexOf(0.75))).isEqualTo(1L);
            assertThat(sends.getSum()).isCloseTo(0.68, within(1e-12));
            final HistogramPointData retry = metric(metrics, "send.phase.duration").getHistogramData().getPoints().stream()
                    .filter(point -> "retry_wait".equals(point.getAttributes().get(PHASE))).findFirst().orElseThrow();
            assertThat(retry.getCounts().get(EXPECTED_BUCKETS.indexOf(300.0))).isEqualTo(1L);
            assertThat(retry.getSum()).isEqualTo(180.0);
        }
    }

    @Test
    void applicationViewsCanReplaceTheBucketAdvice() {
        final InMemoryMetricReader reader = InMemoryMetricReader.create();
        final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .registerView(InstrumentSelector.builder().setMeterName(SCOPE).setName(METRIC_PREFIX + "*duration").build(),
                        View.builder().setAggregation(Aggregation.explicitBucketHistogram(List.of(0.25, 1.0))).build())
                .build();
        try (OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build()) {
            completeSubmission(runtime(telemetry), Duration.ofMillis(80));
            final Collection<MetricData> metrics = reader.collectAllMetrics();
            for (String name : List.of("send.duration", "submission.duration", "send.phase.duration")) {
                assertThat(metric(metrics, name).getHistogramData().getPoints())
                        .allSatisfy(point -> assertThat(point.getBoundaries()).containsExactly(0.25, 1.0));
            }
        }
    }

    @Test
    void logicalSpanAndPhaseMetricsShareDurationsWithoutAddingMetricLabels() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final MailSendTelemetryBridge.Attempt send = runtime(telemetry.sdk).captureContext().beginSend(UUID.randomUUID());
            send.beginSubmission(false).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
            send.complete(MailSendTelemetryReport.builder()
                    .successful(true)
                    .elapsed(Duration.ofSeconds(1))
                    .preparation(Duration.ZERO)
                    .scheduling(Duration.ofMillis(1))
                    .rateLimitWait(Duration.ofMillis(2))
                    .connectionAcquisition(Duration.ofMillis(3))
                    .mimePreparation(Duration.ofMillis(4))
                    .submission(Duration.ofMillis(5))
                    .retryWait(Duration.ofMillis(6))
                    .cleanup(Duration.ofMillis(7))
                    .build());

            final SpanData span = telemetry.sends().get(0);
            final List<String> phases = List.of("preparation", "scheduling", "rate_limit_wait", "connection_acquisition",
                    "mime_preparation", "submission", "retry_wait", "cleanup");
            for (int index = 0; index < phases.size(); index++) {
                assertThat(span.getAttributes().get(durationKey(phases.get(index)))).isEqualTo(index / 1000.0);
                assertThat(telemetry.submissions().get(0).getAttributes().get(durationKey(phases.get(index)))).isNull();
            }
            final Collection<MetricData> metrics = telemetry.metrics.collectAllMetrics();
            assertThat(metric(metrics, "send.phase.duration").getHistogramData().getPoints()).hasSize(8).allSatisfy(point -> {
                final String phase = point.getAttributes().get(PHASE);
                assertThat(point.getSum()).isEqualTo(span.getAttributes().get(durationKey(phase)));
            });
            assertMetricLabelsStayBounded(metrics);
        }
    }

    @Test
    void unavailableTimingsAndUnmeasuredFailureLocationsRemainAbsent() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            runtime(telemetry.sdk).captureContext().beginSend(UUID.randomUUID()).complete(MailSendTelemetryReport.builder()
                    .successful(false)
                    .failure(new IllegalStateException("private provider detail"))
                    .elapsed(Duration.ofMillis(5))
                    .preparation(Duration.ZERO)
                    .build());
            final SpanData span = telemetry.sends().get(0);
            assertThat(span.getAttributes().get(durationKey("preparation"))).isZero();
            assertThat(span.getAttributes().get(durationKey("connection_acquisition"))).isNull();
            assertThat(span.getAttributes().get(durationKey("submission"))).isNull();
            assertThat(span.getAttributes().get(FAILURE_PHASE)).isNull();
            assertThat(span.getAttributes().toString()).doesNotContain("private provider detail");
            assertThat(metric(telemetry.metrics.collectAllMetrics(), "send.phase.duration").getHistogramData().getPoints()).hasSize(1);
        }
    }

    @Test
    void primaryFailureLocationBelongsOnlyToTheLogicalSpan() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final MailSendTelemetryBridge.Attempt send = runtime(telemetry.sdk).captureContext().beginSend(UUID.randomUUID());
            send.beginSubmission(false).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
            send.complete(MailSendTelemetryReport.builder()
                    .successful(false)
                    .submissionStatus(MailSubmissionStatus.ACCEPTED)
                    .failure(new IllegalStateException("private cleanup detail"))
                    .failurePhase("cleanup")
                    .elapsed(Duration.ofMillis(20))
                    .submission(Duration.ofMillis(10))
                    .cleanup(Duration.ofMillis(5))
                    .build());
            assertThat(telemetry.sends().get(0).getAttributes().get(FAILURE_PHASE)).isEqualTo("cleanup");
            assertThat(telemetry.submissions().get(0).getAttributes().get(FAILURE_PHASE)).isNull();
            assertMetricLabelsStayBounded(telemetry.metrics.collectAllMetrics());
        }
    }

    @Test
    void successfulCompletionNeverExportsAStaleFailureLocation() {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            runtime(telemetry.sdk).captureContext().beginSend(UUID.randomUUID()).complete(MailSendTelemetryReport.builder()
                    .successful(true)
                    .failurePhase("submission")
                    .elapsed(Duration.ZERO)
                    .build());
            assertThat(telemetry.sends().get(0).getAttributes().get(FAILURE_PHASE)).isNull();
        }
    }

    private static MailSendTelemetryBridge.Runtime runtime(final OpenTelemetrySdk telemetry) {
        return ModuleLoader.loadOpenTelemetryModule().bind(OtelAdapter.wrap(telemetry), false);
    }

    private static void completeSubmission(final MailSendTelemetryBridge.Runtime runtime, final Duration elapsed) {
        final MailSendTelemetryBridge.Attempt send = runtime.captureContext().beginSend(UUID.randomUUID());
        send.beginSubmission(false).complete(true, MailSubmissionStatus.ACCEPTED, null, null, null);
        send.complete(MailSendTelemetryReport.builder()
                .successful(true)
                .submissionStatus(MailSubmissionStatus.ACCEPTED)
                .elapsed(elapsed)
                .submission(elapsed)
                .build());
    }

    private static AttributeKey<Double> durationKey(final String phase) {
        return AttributeKey.doubleKey("simplejavamail.send.phase." + phase + ".duration");
    }

    private static MetricData metric(final Collection<MetricData> metrics, final String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(METRIC_PREFIX + name)).findFirst().orElseThrow();
    }

    private static void assertMetricLabelsStayBounded(final Collection<MetricData> metrics) {
        assertThat(metrics).allSatisfy(metric -> assertThat(metric.getData().getPoints()).allSatisfy(point ->
                assertThat(point.getAttributes().asMap().keySet()).allSatisfy(key -> assertThat(key.getKey())
                        .isIn("simplejavamail.send.outcome", "simplejavamail.submission.status", "simplejavamail.send.phase", "error.type"))));
    }
}
