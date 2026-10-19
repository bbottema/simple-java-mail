package org.simplejavamail.api.mailer;

/**
 * Holds an application-owned telemetry instance adapted for use with a Mailer.
 * Obtain it through the optional telemetry module's typed adapter, then pass it to
 * {@link MailerGenericBuilder#withTelemetryConfig(org.simplejavamail.api.mailer.config.MailSendTelemetryConfig)}. The application keeps ownership of the supplied instance.
 * <p>
 * Sending preferences belong to the Mailer builder. This handle exposes no SDK, configuration defaults or recording operations.
 *
 * @see MailerGenericBuilder#withTelemetryConfig(org.simplejavamail.api.mailer.config.MailSendTelemetryConfig)
 */
public interface MailSendTelemetry {
}
