package org.simplejavamail.api.mailer.config;

import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import static java.util.Objects.requireNonNull;

/**
 * Immutable mail-send telemetry choices. Unset flags inherit the factory snapshot; this value never reads configuration sources.
 * Supplying or removing an integration does not enable or disable telemetry. The application owns its SDK and exporters.
 * Enabled telemetry without an integration uses the application's global OpenTelemetry instance through the optional module.
 */
@Value
public class MailSendTelemetryConfig {
    @Nullable Boolean enabled;
    @Nullable @ToString.Exclude MailSendTelemetry integration;
    @Nullable Boolean smtpEndpointAttributes;

    private MailSendTelemetryConfig(@Nullable final Boolean enabled, @Nullable final MailSendTelemetry integration,
            @Nullable final Boolean smtpEndpointAttributes) {
        this.enabled = enabled;
        this.integration = integration;
        this.smtpEndpointAttributes = smtpEndpointAttributes;
    }

    /** @return A builder with inherited flags and no supplied integration. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining the same borrowed integration. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withEnabled(boolean) */
    @Nullable public Boolean getEnabled() { return enabled; }
    /** @see Builder#withIntegration(MailSendTelemetry) */
    @Nullable public MailSendTelemetry getIntegration() { return integration; }
    /** @see Builder#withSmtpEndpointAttributes(boolean) */
    @Nullable public Boolean getSmtpEndpointAttributes() { return smtpEndpointAttributes; }

    /** Collects choices without loading an optional module or initializing an SDK. */
    public static final class Builder {
        private Boolean enabled;
        private MailSendTelemetry integration;
        private Boolean smtpEndpointAttributes;

        private Builder() { }
        private Builder(final MailSendTelemetryConfig config) {
            enabled = config.enabled;
            integration = config.integration;
            smtpEndpointAttributes = config.smtpEndpointAttributes;
        }

        /**
         * Replaces activation independently from the supplied integration. Disabling does not remove that integration.
         * {@link #resetEnabled()} selects the library default; {@link #useConfiguredEnabled()} restores inheritance.
         * @param enabled Whether mail sends produce telemetry; enabling requires the optional telemetry module.
         */
        @NotNull public Builder withEnabled(final boolean enabled) { this.enabled = enabled; return this; }
        /** @see #withEnabled(boolean) */
        @Nullable public Boolean getEnabled() { return enabled; }
        /** @see #withEnabled(boolean) */
        @NotNull public Builder resetEnabled() { return withEnabled(MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_ENABLED); }
        /** @see #withEnabled(boolean) */
        @NotNull public Builder useConfiguredEnabled() { enabled = null; return this; }

        /**
         * Supplies the handle returned by the optional module's typed adapter, without changing activation or endpoint permission.
         * The SDK and exporters remain application-owned. {@link #clearIntegration()} only removes this registration.
         * @param integration Application-owned integration, for example {@code OtelAdapter.wrap(openTelemetry)}.
         */
        @NotNull public Builder withIntegration(@NotNull final MailSendTelemetry integration) {
            this.integration = requireNonNull(integration, "integration");
            return this;
        }
        /** @see #withIntegration(MailSendTelemetry) */
        @Nullable public MailSendTelemetry getIntegration() { return integration; }
        /** @see #withIntegration(MailSendTelemetry) */
        @NotNull public Builder clearIntegration() { integration = null; return this; }

        /**
         * Allows logical SMTP host and port in trace attributes; they never become metric labels.
         * This does not enable telemetry. {@link #resetSmtpEndpointAttributes()} selects the library default;
         * {@link #useConfiguredSmtpEndpointAttributes()} restores inheritance.
         * @param enabled Whether infrastructure endpoint attributes may be included.
         */
        @NotNull public Builder withSmtpEndpointAttributes(final boolean enabled) { smtpEndpointAttributes = enabled; return this; }
        /** @see #withSmtpEndpointAttributes(boolean) */
        @Nullable public Boolean getSmtpEndpointAttributes() { return smtpEndpointAttributes; }
        /** @see #withSmtpEndpointAttributes(boolean) */
        @NotNull public Builder resetSmtpEndpointAttributes() {
            return withSmtpEndpointAttributes(MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES);
        }
        /** @see #withSmtpEndpointAttributes(boolean) */
        @NotNull public Builder useConfiguredSmtpEndpointAttributes() { smtpEndpointAttributes = null; return this; }

        /** @return An immutable snapshot; the integration remains borrowed. */
        @NotNull public MailSendTelemetryConfig build() { return new MailSendTelemetryConfig(enabled, integration, smtpEndpointAttributes); }
    }
}
