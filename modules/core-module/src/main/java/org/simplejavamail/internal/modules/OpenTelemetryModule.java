package org.simplejavamail.internal.modules;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

/** Reflection-loaded boundary for the optional OpenTelemetry runtime. */
@ApiStatus.Internal
public interface OpenTelemetryModule {

    /** Binds an adapted application instance with the resolved endpoint permission; a null handle selects the application's global instance. */
    @NotNull MailSendTelemetryBridge.Runtime bind(@Nullable MailSendTelemetry configuration, boolean smtpEndpointAttributesEnabled);
}
