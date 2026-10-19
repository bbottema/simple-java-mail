package org.simplejavamail.otelconsumer;

import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import io.opentelemetry.api.OpenTelemetry;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.otel.OtelAdapter;

/** Loads the optional integration without main, an SMTP provider, an SDK or an exporter on the compile/runtime path. */
public final class OtelConsumer {
    public static void main(final String[] arguments) {
        final OpenTelemetry supplied = OpenTelemetry.noop();
        final MailSendTelemetry integration = OtelAdapter.wrap(supplied);
        if (integration == null) {
            throw new AssertionError("The typed adapter did not return its core handle");
        }
        System.out.println("OpenTelemetry consumer OK");
    }

    public static MailerGenericBuilder<?> configure(final MailerGenericBuilder<?> builder, final MailSendTelemetry telemetry) {
        return builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(telemetry).withEnabled(true).withSmtpEndpointAttributes(true).build());
    }
}
