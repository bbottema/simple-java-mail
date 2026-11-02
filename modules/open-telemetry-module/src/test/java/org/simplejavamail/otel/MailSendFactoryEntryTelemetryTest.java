package org.simplejavamail.otel;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.config.ConfigLoader;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MailSendFactoryEntryTelemetryTest {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void logicalSpanStartsAtAttemptEntryEvenWhenItsHandlerFactoryFails(final boolean factoryFails) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email email = mail.emailBuilder().startingBlank()
                .from("sender@example.test").to("recipient@example.test").withPlainText("Hello").buildEmail();
        final AtomicReference<MailSendOutcome> outcome = new AtomicReference<>();
        final IllegalStateException failure = new IllegalStateException("handler setup failed");
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                     .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(true)
                             .withIntegration(OtelAdapter.wrap(telemetry.sdk)).build())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome::set).build())
                     .withSendHandlersFactory(id -> {
                         if (factoryFails) { throw failure; }
                         return MailSendHandlers.builder().build();
                     }).buildMailer()) {
            final Throwable thrown = catchThrowable(() -> mailer.sync().sendMail(email));
            if (factoryFails) {
                assertThat(thrown).isSameAs(failure);
            } else {
                assertThat(thrown).isNull();
            }

            assertThat(telemetry.sends()).hasSize(1);
            final SpanData span = telemetry.sends().get(0);
            final Instant requestedAt = outcome.get().getRequestedAt();
            assertThat(span.getStartEpochNanos()).isEqualTo(requestedAt.getEpochSecond() * 1_000_000_000L + requestedAt.getNano());
            assertThat(telemetry.sum("simplejavamail.mail.send.active")).isZero();
        }
    }
}
