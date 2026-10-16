package org.simplejavamail.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.List;
import java.util.stream.Collectors;

final class TelemetryTestSdk implements AutoCloseable {
    final InMemorySpanExporter spans = InMemorySpanExporter.create();
    final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    final OpenTelemetrySdk sdk;

    TelemetryTestSdk() {
        this(Sampler.alwaysOn());
    }

    TelemetryTestSdk(final Sampler sampler) {
        final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build();
        final SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metrics)
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
    }

    List<SpanData> sends() {
        return namedSpans("mail.send");
    }

    List<SpanData> submissions() {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().startsWith("mail.") && !span.getName().equals("mail.send"))
                .collect(Collectors.toList());
    }

    List<SpanData> namedSpans(final String name) {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .collect(Collectors.toList());
    }

    long sum(final String name) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .mapToLong(point -> point.getValue())
                .sum();
    }

    List<Attributes> metricAttributes() {
        return metrics.collectAllMetrics().stream()
                .flatMap(metric -> metric.getData().getPoints().stream())
                .map(point -> point.getAttributes())
                .collect(Collectors.toList());
    }

    MetricData metric(final String name) {
        return metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void close() {
        sdk.close();
    }
}
