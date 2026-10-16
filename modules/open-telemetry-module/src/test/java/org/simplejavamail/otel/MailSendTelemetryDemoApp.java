package org.simplejavamail.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Shows how to trace a send to a local SMTP test server, without an external account or collector.
 * This example uses an in-memory exporter to print the resulting spans. Production applications supply their own SDK and
 * normally use batch processing with their chosen exporter.
 */
public final class MailSendTelemetryDemoApp {
    public static void main(final String[] arguments) throws Exception {
        final InMemorySpanExporter exported = InMemorySpanExporter.create();
        final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exported))
                .build();
        try (OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
             TelemetrySmtpServer relay = new TelemetrySmtpServer()) {
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            final Email email = mail.emailBuilder().startingBlank()
                    .from("sender@example.test")
                    .withRecipients(mail.recipientBuilder()
                            .withAddress("receiver@example.test")
                            .withType(jakarta.mail.Message.RecipientType.TO)
                            .build())
                    .withSubject("Telemetry example")
                    .withPlainText("Loopback only")
                    .buildEmail();
            final Span request = telemetry.getTracer("example.application").spanBuilder("example request").startSpan();
            try (Mailer mailer = mail.mailerBuilder()
                    .withSMTPServer("localhost", relay.port())
                    .withTransportStrategy(TransportStrategy.SMTP)
                    .withOpportunisticTLS(false)
                    .withProperty("mail.host", "localhost")
                    .withConnectionPoolCoreSize(0)
                    .withMailSendTelemetry(OtelAdapter.wrap(telemetry))
                    .buildMailer()) {
                try (Scope ignored = request.makeCurrent()) {
                    mailer.async().sendMail(email).getCompletion().get(5, SECONDS);
                }
            } finally {
                request.end();
            }
            // Mailer.close() did not close the SDK. This application chooses when to export, flush and finally close it.
            exported.getFinishedSpanItems().forEach(span -> System.out.println(span.getName() + " parent=" + span.getParentSpanId()
                    + " kind=" + span.getKind() + " status=" + span.getStatus().getStatusCode()));
        }
    }
}
