package org.simplejavamail.internal.telemetry.otel;

import io.opentelemetry.api.OpenTelemetry;
import lombok.ToString;
import lombok.Value;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailSendTelemetry;

/** Retains the application instance without configuration resolution or runtime binding. */
@Value
@ToString(onlyExplicitlyIncluded = true)
@ApiStatus.Internal
public class OtelTelemetry implements MailSendTelemetry {
    @NotNull OpenTelemetry openTelemetry;
}
