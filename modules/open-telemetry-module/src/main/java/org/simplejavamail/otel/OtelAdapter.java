package org.simplejavamail.otel;

import io.opentelemetry.api.OpenTelemetry;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
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
     * Wraps your OpenTelemetry instance so you can pass it to the Mailer builder's
     * {@link MailerGenericBuilder#withMailSendTelemetry(MailSendTelemetry)} method.
     * Your instance keeps its existing providers, sampling and exporters. Wrapping it performs no telemetry work, installs no global
     * instance and leaves flushing and shutdown with your application. Closing a Mailer never closes the supplied instance.
     * <p>
     * Use the Mailer builder to configure reporting preferences, including whether traces may identify the SMTP relay.
     * Without an explicit instance, you can instead enable telemetry through the builder or properties to use the application's global instance.
     *
     * @param openTelemetry The application's non-null instance, including implementations of OpenTelemetry's public interface.
     * @return An immutable core handle retaining this exact instance, without initializing a Mailer or telemetry runtime.
     * @see MailerGenericBuilder#withMailSendTelemetry(MailSendTelemetry)
     */
    @NotNull
    public static MailSendTelemetry wrap(@NotNull final OpenTelemetry openTelemetry) {
        return new OtelTelemetry(requireNonNull(openTelemetry, "openTelemetry"));
    }
}
