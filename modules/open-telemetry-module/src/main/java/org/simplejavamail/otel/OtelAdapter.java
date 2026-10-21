package org.simplejavamail.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.internal.telemetry.otel.OtelTelemetry;

import static java.util.Objects.requireNonNull;

/**
 * Lets you supply the OpenTelemetry instance your application already uses.
 * Wrap it once and configure the Mailer through its regular builder; the returned handle can be shared between Mailers.
 */
public final class OtelAdapter {

    // Keep this explicit so source-based Javadoc does not advertise a public constructor hidden only by Lombok.
    private OtelAdapter() {
    }

    /**
     * Wraps your OpenTelemetry instance for {@link MailSendTelemetryConfig.Builder#withIntegration(MailSendTelemetry)}.
     * Pass that built configuration to {@link MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig)}.
     * Your instance keeps its existing providers, sampling and exporters. Wrapping it performs no telemetry work, installs no global
     * instance and leaves flushing and shutdown with your application. Closing a Mailer never closes the supplied instance.
     * <p>
     * Registering the integration does not enable telemetry. Choose activation independently through the configuration builder or properties.
     * With an existing {@code mailerBuilder} and application-owned {@code openTelemetry}:
     * <pre>{@code
     * mailerBuilder.withTelemetryConfig(MailSendTelemetryConfig.builder()
     *         .withIntegration(OtelAdapter.wrap(openTelemetry))
     *         .withEnabled(true)
     *         .build());
     * }</pre>
     * Use the same configuration builder for reporting preferences, including whether traces may identify the SMTP relay.
     * Without an explicit integration, enabling telemetry uses the application's global instance.
     *
     * @param openTelemetry The application's non-null instance, including implementations of OpenTelemetry's public interface.
     * @return An immutable core handle retaining this exact instance, without initializing a Mailer or telemetry runtime.
     * @see MailSendTelemetryConfig.Builder#withIntegration(MailSendTelemetry)
     * @see MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig)
     */
    @NotNull
    public static MailSendTelemetry wrap(@NotNull final OpenTelemetry openTelemetry) {
        return new OtelTelemetry(requireNonNull(openTelemetry, "openTelemetry"));
    }
}
