package org.simplejavamail.internal.telemetry.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.internal.modules.OpenTelemetryModule;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

import static java.util.Objects.requireNonNull;

/** Stateless module entry point. SDK and per-Mailer runtime references are never kept in ModuleLoader's shared cache. */
@ApiStatus.Internal
public final class OpenTelemetrySupport implements OpenTelemetryModule {

    @Override
    public MailSendTelemetryBridge.Runtime bind(@Nullable final MailSendTelemetry configuration, final boolean smtpEndpointAttributesEnabled) {
        final OpenTelemetry openTelemetry = selectedInstance(configuration);
        return new OtelMailSendRuntime(requireNonNull(openTelemetry, "openTelemetry"), smtpEndpointAttributesEnabled);
    }

    private static OpenTelemetry selectedInstance(@Nullable final MailSendTelemetry configuration) {
        if (configuration == null) {
            return GlobalOpenTelemetry.get();
        }
        // Only this module interprets its opaque handle; the main builder never needs the foreign API or an internal bridge.
        if (!(configuration instanceof OtelTelemetry)) {
            throw new IllegalArgumentException("This telemetry handle does not contain an OpenTelemetry instance. "
                    + "Supply OtelAdapter.wrap(openTelemetry), or clear the handle and enable telemetry to use your application's global instance.");
        }
        return ((OtelTelemetry) configuration).getOpenTelemetry();
    }
}
