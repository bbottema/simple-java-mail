import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.api.common.Attributes;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.otel.OtelAdapter;

import java.time.Duration;

/**
 * Export this application's mail traces and aggregate metrics when a local Collector is configured.
 * The application creates and closes the SDK; giving it to a Mailer does not transfer that ownership to SJM.
 */
final class PolarTelemetry implements AutoCloseable {
    private final OpenTelemetrySdk sdk;

    PolarTelemetry() {
        String endpoint = System.getenv("OTEL_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            sdk = null;
            return;
        }
        // Distinguish these two processes' cumulative metric streams, without adding per-email IDs as dimensions.
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", "sjm-polar-meridian")
                .put("service.instance.id", java.util.UUID.randomUUID().toString()).build()));
        var traces = createTracerProvider(resource, endpoint);
        var metrics = createMeterProvider(resource, endpoint);
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(traces)
                .setMeterProvider(metrics)
                .build();
    }

    private SdkTracerProvider createTracerProvider(Resource resource, String endpoint) {
        var exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint + "/v1/traces")
                .build();
        return SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    private SdkMeterProvider createMeterProvider(Resource resource, String endpoint) {
        var exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint + "/v1/metrics")
                .build();
        // A short export interval makes this small demonstration inspectable; it is not a recommended production reporting interval.
        var reader = PeriodicMetricReader.builder(exporter)
                .setInterval(Duration.ofSeconds(1))
                .build();
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(reader)
                .build();
    }

    void configure(MailerRegularBuilder<?> builder) {
        if (sdk != null) {
            builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(OtelAdapter.wrap(sdk)).withEnabled(true).build());
        }
    }

    @Override
    public void close() {
        if (sdk != null) {
            sdk.close();
        }
    }
}
