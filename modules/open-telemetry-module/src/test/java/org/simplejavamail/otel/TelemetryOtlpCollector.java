package org.simplejavamail.otel;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.trace.v1.Span;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** Receives public OTLP/protobuf on loopback, so the agent fixture needs neither private APIs nor debug-log parsing. */
final class TelemetryOtlpCollector implements AutoCloseable {
    private final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    private final List<Span> spans = new CopyOnWriteArrayList<>();
    private final List<Metric> metrics = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    TelemetryOtlpCollector() throws IOException {
        server.createContext("/v1/traces", this::receiveTraces);
        server.createContext("/v1/metrics", this::receiveMetrics);
        server.start();
    }

    private void receiveTraces(final HttpExchange exchange) throws IOException {
        try {
            final ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(exchange.getRequestBody());
            final List<Span> relevantSpans = request.getResourceSpansList().stream()
                    .flatMap(resource -> resource.getScopeSpansList().stream())
                    .filter(scope -> scope.getScope().getName().equals("org.simplejavamail.mail") || scope.getScope().getName().equals("application"))
                    .flatMap(scope -> scope.getSpansList().stream())
                    .collect(Collectors.toList());
            spans.addAll(relevantSpans);
            exchange.sendResponseHeaders(200, -1);
        } catch (Exception failure) {
            failures.add(failure);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private void receiveMetrics(final HttpExchange exchange) throws IOException {
        try {
            final ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(exchange.getRequestBody());
            final List<Metric> relevantMetrics = request.getResourceMetricsList().stream()
                    .flatMap(resource -> resource.getScopeMetricsList().stream())
                    .filter(scope -> scope.getScope().getName().equals("org.simplejavamail.mail"))
                    .flatMap(scope -> scope.getMetricsList().stream())
                    .collect(Collectors.toList());
            metrics.addAll(relevantMetrics);
            exchange.sendResponseHeaders(200, -1);
        } catch (Exception failure) {
            failures.add(failure);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    String endpoint() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Span> named(final String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .collect(Collectors.toList());
    }

    List<Throwable> failures() {
        return List.copyOf(failures);
    }

    long latestSum(final String name) {
        final List<Metric> matching = metrics.stream()
                .filter(metric -> metric.getName().equals(name))
                .collect(Collectors.toList());
        if (matching.isEmpty()) {
            throw new AssertionError("Agent exported no " + name);
        }
        return matching.get(matching.size() - 1).getSum().getDataPointsList().stream()
                .mapToLong(point -> point.getAsInt())
                .sum();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
