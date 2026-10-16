package org.simplejavamail.api.mailer;

/**
 * Holds an application-owned telemetry instance adapted for use with a Mailer.
 * Obtain it through the optional telemetry module's typed adapter, then pass it to
 * {@link MailerGenericBuilder#withMailSendTelemetry(MailSendTelemetry)}. The application keeps ownership of the supplied instance.
 * <p>
 * Sending preferences belong to the Mailer builder. This handle exposes no SDK, configuration defaults or recording operations.
 *
 * @see MailerGenericBuilder#withMailSendTelemetry(MailSendTelemetry)
 */
public interface MailSendTelemetry {
}
