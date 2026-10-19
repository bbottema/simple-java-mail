package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Owns shared callback choices and one bounded progress dispatcher; each send retains only its own generated choices. */
final class MailSendObserverNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailSendObserverNotifier.class);
    @Nullable private final MailSendHandlers handlers;
    @Nullable private final Function<UUID, MailSendHandlers> handlersFactory;
    private final Supplier<UUID> idGenerator;
    private final boolean loggingOnly;
    private final MailSendProgressDispatcher progressDispatcher = new MailSendProgressDispatcher();
    @Nullable private final MailSendTelemetryBridge.Runtime telemetry;

    MailSendObserverNotifier(@Nullable final MailSendResultObserver observer, final boolean loggingOnly) {
        this(observer, null, loggingOnly);
    }

    MailSendObserverNotifier(@Nullable final MailSendResultObserver observer, @Nullable final Executor executor, final boolean loggingOnly) {
        this(observer == null ? null : executor == null ? MailSendHandlers.builder().withResultObserver(observer).build()
                : MailSendHandlers.builder().withResultObserver(observer, executor).build(), null, null, loggingOnly, null);
    }

    @Builder
    private MailSendObserverNotifier(@Nullable final MailSendHandlers handlers,
            @Nullable final Function<UUID, MailSendHandlers> handlersFactory, @Nullable final Supplier<UUID> idGenerator,
            final boolean loggingOnly, @Nullable final MailSendTelemetryBridge.Runtime telemetry) {
        this.handlers = handlers;
        this.handlersFactory = handlersFactory;
        this.idGenerator = idGenerator == null ? UUID::randomUUID : idGenerator;
        this.loggingOnly = loggingOnly;
        this.telemetry = telemetry;
    }

    @NotNull MailSendAttempt beginAttempt(@NotNull final Email email) { return beginAttempt(email, null); }

    @NotNull MailSendAttempt beginAttempt(@NotNull final Email email, @Nullable final MailSendControl control) {
        final UUID id = requireNonNull(idGenerator.get(), "sendIdGenerator returned null");
        final Instant requestedAt = Instant.now();
        final MailSendHandlers generated;
        try {
            generated = handlersFactory == null ? null : requireNonNull(handlersFactory.apply(id), "sendHandlersFactory returned null");
        } catch (RuntimeException failure) {
            final MailSendAttempt failed = newAttempt(email, control, id, requestedAt, null);
            failed.completeWithFailure(failure);
            throw failure;
        }
        return newAttempt(email, control, id, requestedAt, generated);
    }

    private MailSendAttempt newAttempt(final Email email, @Nullable final MailSendControl control, final UUID id,
            final Instant requestedAt, @Nullable final MailSendHandlers generated) {
        if (!observesResults(generated) && !observesProgress(handlers) && !observesProgress(generated) && telemetry == null) {
            return MailSendAttempt.unobserved();
        }
        return MailSendAttempt.observed(this, id, generated, email.getId(), requestedAt, loggingOnly, control);
    }

    @Nullable MailSendTelemetryBridge.CapturedContext captureTelemetryContext() { return MailSendTelemetryRecorder.capture(telemetry); }

    boolean observesResults(@Nullable final MailSendHandlers generated) {
        return hasResultObserver(handlers) || hasResultObserver(generated);
    }

    private static boolean hasResultObserver(@Nullable final MailSendHandlers choice) {
        return choice != null && choice.getResultObserver() != null;
    }

    private static boolean observesProgress(@Nullable final MailSendHandlers choice) {
        return choice != null && choice.getProgressObserver() != null;
    }

    @Nullable MailSendProgressRecorder progressRecorder(final UUID id, @Nullable final String initialMessageId,
            @Nullable final MailSendHandlers generated) {
        if (!observesProgress(handlers) && !observesProgress(generated)) { return null; }
        return new MailSendProgressRecorder(progressDispatcher, id, initialMessageId, System::nanoTime,
                MailSendProgressDispatcher.targets(handlers, generated));
    }

    void closeProgress() { progressDispatcher.close(); }

    void notifyCompletion(final MailSendOutcome outcome, @Nullable final MailSendHandlers generated) {
        notifyCompletion(handlers, outcome);
        notifyCompletion(generated, outcome);
    }

    private void notifyCompletion(@Nullable final MailSendHandlers choice, final MailSendOutcome outcome) {
        if (!hasResultObserver(choice)) { return; }
        final MailSendResultObserver observer = choice.getResultObserver();
        final Runnable notification = () -> {
            try { observer.onMailSendCompleted(outcome); }
            catch (RuntimeException failure) { LOGGER.warn("Mail send observer failed; ignoring observer exception", failure); }
        };
        if (choice.getResultObserverExecutor() == null) { notification.run(); }
        else {
            try { choice.getResultObserverExecutor().execute(notification); }
            catch (RuntimeException rejection) { LOGGER.warn("Mail send observer notification was not handed off; leaving the send result unchanged", rejection); }
        }
    }
}