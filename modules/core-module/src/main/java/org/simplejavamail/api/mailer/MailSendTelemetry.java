package org.simplejavamail.api.mailer;

/**
 * Holds an application-owned telemetry instance adapted for use with a Mailer.
 * Obtain it through the optional telemetry module's typed adapter, then pass it to
 * {@link org.simplejavamail.api.mailer.config.MailSendTelemetryConfig.Builder#withIntegration(MailSendTelemetry)}.
 * Pass the built configuration to {@link MailerGenericBuilder#withTelemetryConfig(org.simplejavamail.api.mailer.config.MailSendTelemetryConfig)}.
 * The application keeps ownership of the supplied instance.
 * <p>
 * Registering an integration does not enable telemetry: choose activation independently with
 * {@link org.simplejavamail.api.mailer.config.MailSendTelemetryConfig.Builder#withEnabled(boolean)} or configuration properties.
 * This handle exposes no SDK, configuration defaults or recording operations.
 *
 * @see org.simplejavamail.api.mailer.config.MailSendTelemetryConfig.Builder#withIntegration(MailSendTelemetry)
 * @see MailerGenericBuilder#withTelemetryConfig(org.simplejavamail.api.mailer.config.MailSendTelemetryConfig)
 */
public interface MailSendTelemetry {
}
