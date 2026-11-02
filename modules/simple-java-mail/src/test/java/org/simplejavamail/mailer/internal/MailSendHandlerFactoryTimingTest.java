package org.simplejavamail.mailer.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MailSendHandlerFactoryTimingTest {

    @ParameterizedTest
    @CsvSource({ "false, false", "false, true", "true, false" })
    void totalElapsedIncludesSuccessfulAndFailedHandlerFactoryWork(final boolean factoryFails, final boolean generatedHandlers) throws Exception {
        final AtomicReference<MailSendOutcome> outcome = new AtomicReference<>();
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        final AtomicLong clock = new AtomicLong(-1_000);
        final IllegalStateException failure = new IllegalStateException("handler setup failed");
        final MailSendHandlers handlers = MailSendHandlers.builder().withResultObserver(outcome::set)
                .withProgressObserver(progress::add, Runnable::run).build();
        final MailSendObserverNotifier notifier = MailSendObserverNotifier.builder()
                .clock(clock::get)
                .handlers(generatedHandlers ? null : handlers)
                .handlersFactory(id -> {
                    clock.set(-500);
                    if (factoryFails) { throw failure; }
                    return generatedHandlers ? handlers : MailSendHandlers.builder().build();
                })
                .build();

        try {
            if (factoryFails) {
                assertThat(catchThrowable(() -> notifier.beginAttempt(mock(Email.class)))).isSameAs(failure);
            } else {
                final MailSendAttempt attempt = notifier.beginAttempt(mock(Email.class));
                attempt.preparationStarted();
                clock.set(-250);
                attempt.completeWithFailure(failure);
            }

            final Duration expected = Duration.ofNanos(factoryFails ? 500 : 750);
            assertThat(outcome.get()).isNotNull();
            assertThat(outcome.get().getDiagnostics().orElseThrow().getElapsed()).isEqualTo(expected);
            assertThat(outcome.get().getFailure()).containsSame(failure);
            MailSendProgress update;
            do {
                update = progress.poll(5, SECONDS);
                assertThat(update).isNotNull();
            } while (update.getStage() != MailSendProgressStage.FINISHED);
            assertThat(update.getElapsed()).isEqualTo(expected);
        } finally {
            notifier.closeProgress();
        }
    }

    @Test
    void anUnobservedSendDoesNotReadTheInjectedClockOrMessage() {
        final Email email = mock(Email.class);
        final MailSendAttempt attempt = MailSendObserverNotifier.builder()
                .clock(() -> { throw new AssertionError("unobserved clock was read"); }).build().beginAttempt(email);
        attempt.completeWithFailure(new IllegalStateException("send failed"));
        assertThat(attempt.diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
        verifyNoInteractions(email);
    }
}
