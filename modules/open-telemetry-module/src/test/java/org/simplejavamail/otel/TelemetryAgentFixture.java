package org.simplejavamail.otel;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** A separate JVM using public APIs only; the agent variant exports to the test's loopback OTLP receiver. */
public final class TelemetryAgentFixture {
    public static void main(final String[] arguments) throws Exception {
        final boolean agent = Boolean.parseBoolean(arguments[0]);
        if (agent) {
            // Follow OpenTelemetry's manual-instrumentation guidance: use the installed agent SDK, not a competing application SDK.
            verifySends(GlobalOpenTelemetry.get(), true);
            System.out.println("TELEMETRY COEXISTENCE OK agent=true");
            return;
        }
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            verifySends(telemetry.sdk, false);
            assertThat(telemetry.sends()).hasSize(2);
            assertThat(telemetry.submissions()).hasSize(2);
            assertThat(telemetry.sum("simplejavamail.mail.send.completed")).isEqualTo(2);
            assertThat(telemetry.sum("simplejavamail.mail.send.active")).isZero();
        }
        System.out.println("TELEMETRY COEXISTENCE OK agent=false");
    }

    private static void verifySends(final OpenTelemetry supplied, final boolean propertyActivation) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(java.util.Map.of("simplejavamail.telemetry.enabled", propertyActivation)).load());
        final Span request = supplied.getTracer("application").spanBuilder("incoming request").startSpan();
        assertThat(request.getSpanContext().isValid()).isTrue();
        final CustomMailer callback = new CustomMailer() {
            @Override
            public void testConnection(final OperationalConfig config, final Session session) { }
            @Override
            public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(request.getSpanContext().getTraceId());
                assertThat(Span.current().getSpanContext().getSpanId()).isNotEqualTo(request.getSpanContext().getSpanId());
            }
        };
        final var customBuilder = mail.mailerBuilder()
                .withSMTPServer("localhost", 0)
                .withProperty("mail.host", "localhost")
                .withCustomMailer(callback);
        try (TelemetrySmtpServer server = new TelemetrySmtpServer()) {
            final var smtpBuilder = mail.mailerBuilder()
                    .withSMTPServer("localhost", server.port())
                    .withTransportStrategy(TransportStrategy.SMTP)
                    .withTlsConfig(TlsConfig.builder().withOpportunisticTLS(false).build())
                    .withProperty("mail.host", "localhost")
                    .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build());
            if (!propertyActivation) {
                customBuilder.withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(OtelAdapter.wrap(supplied)).withEnabled(true).build());
                smtpBuilder.withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(OtelAdapter.wrap(supplied)).withEnabled(true).build());
            }
            try (Mailer mailer = customBuilder.buildMailer(); Mailer smtpMailer = smtpBuilder.buildMailer(); Scope ignored = request.makeCurrent()) {
                final Email email = mail.emailBuilder().startingBlank()
                        .from("sender@example.test")
                        .withRecipients(mail.recipientBuilder()
                                .withAddress("receiver@example.test")
                                .withType(jakarta.mail.Message.RecipientType.TO)
                                .build())
                        .buildEmail();
                mailer.async().sendMail(email).getCompletion().get(15, SECONDS);
                smtpMailer.async().sendMail(email).getCompletion().get(15, SECONDS);
                assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(request.getSpanContext().getSpanId());
                assertThat(server.submissions).hasValue(1);
            }
        } finally {
            request.end();
        }
    }
}
