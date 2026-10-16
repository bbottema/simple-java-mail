package org.simplejavamail.otelconsumer;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;

/** Exercises property-driven module loading from packaged JARs, including JPMS access to the internal entry point. */
public final class OtelRuntimeConsumer {
    public static void main(final String[] arguments) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", true,
                "simplejavamail.transport.mode.logging.only", true)).load());
        try (Mailer mailer = mail.mailerBuilder().buildMailer()) {
            if (!mailer.getOperationalConfig().isMailSendTelemetryEnabled()) {
                throw new AssertionError("Factory activation did not reach the Mailer");
            }
            mailer.sync().sendMail(mail.emailBuilder().startingBlank().from("sender@example.test")
                    .withRecipients(mail.recipientBuilder().withAddress("receiver@example.test")
                            .withType(jakarta.mail.Message.RecipientType.TO).build()).buildEmail());
        }
        System.out.println("OpenTelemetry runtime consumer OK");
    }
}
