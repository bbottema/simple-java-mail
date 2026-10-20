package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSendException;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.time.Duration;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

/** Arbitrates execution versus removal without completing a future or invoking application code on a deadline thread. */
final class MailSendOperation<T> implements Runnable {
    private enum State { CREATED, ADMITTING, QUEUED, RUNNING, WAITING_FOR_RETRY, RESUMING, FINISHING, FINISHED }

    private final MailSendOperations owner;
    private final MailSendControl control;
    private final Consumer<T> success;
    private final Consumer<Throwable> failure;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private Supplier<T> work;
    private boolean synchronous;
    @Nullable private RetryingWork<T> retryingWork;
    @Nullable private MailSendRetryCoordinator.Ticket retryTicket;
    @Nullable private Duration retryDelay;
    @Nullable private Supplier<MailSendTelemetryBridge.Scope> telemetryScope;

    void observeWith(final MailSendAttempt attempt) {
        if (attempt.observesTelemetry()) {
            telemetryScope = attempt::attachTelemetry;
        }
    }

    void observeWith(@Nullable final MailSendTelemetryBridge.CapturedContext context) {
        if (context != null) {
            telemetryScope = () -> MailSendTelemetryRecorder.attach(context);
        }
    }

    @Nullable
    private MailSendTelemetryBridge.Scope attachTelemetry() {
        return telemetryScope == null ? null : telemetryScope.get();
    }

    /** One physical submission per execution turn. A refused turn is parked only after its ordinary resources have been released. */
    interface RetryingWork<T> extends AutoCloseable {
        RetryResult<T> execute();
        @Nullable RuntimeException lastFailure();
        void waiting();
        void resumed();
        default void retryScheduled(final Duration delay) { }
        default void failed() { }
        @Override void close();
    }

    static final class RetryResult<T> {
        final T value;
        final Duration nextDelay;
        private RetryResult(final T value, final Duration nextDelay) { this.value = value; this.nextDelay = nextDelay; }
        static <T> RetryResult<T> completed(final T value) { return new RetryResult<>(value, null); }
        static <T> RetryResult<T> retry(final Duration delay) { return new RetryResult<>(null, delay); }
    }

    MailSendOperation(final MailSendOperations owner, final MailSendControl control,
            final Consumer<T> success, final Consumer<Throwable> failure) {
        this.owner = owner;
        this.control = control;
        this.success = success;
        this.failure = failure;
        control.onStop(this::retireQueuedOperation);
    }

    MailSendControl control() {
        return control;
    }

    MailSend<T> handle() {
        return new MailSend<>(completion, control::requestCancellation);
    }

    void schedule(final Supplier<T> action) {
        work = action;
        state.set(State.ADMITTING);
        try {
            control.checkStopped();
            owner.schedule(this);
            if (state.compareAndSet(State.ADMITTING, State.QUEUED) && control.isStopRequested()) {
                retireQueuedOperation();
            }
        } catch (RuntimeException | Error rejection) {
            if (state.compareAndSet(State.ADMITTING, State.FINISHING)) {
                final Throwable admissionFailure = rejection instanceof java.util.concurrent.RejectedExecutionException
                        ? new org.simplejavamail.api.mailer.MailSendRejectedException(
                                org.simplejavamail.api.mailer.AsyncQueueRejectionReason.EXECUTOR_REJECTED, rejection) : rejection;
                finish(null, admissionFailure instanceof RuntimeException ? control.translateFailure((RuntimeException) admissionFailure) : admissionFailure);
            }
            if (rejection instanceof Error) {
                throw (Error) rejection;
            }
        }
    }

    T executeSync(final Supplier<T> action) {
        work = action;
        synchronous = true;
        state.set(State.RUNNING);
        return executeWork();
    }

    @Override
    public void run() {
        if (state.compareAndSet(State.ADMITTING, State.RUNNING) || state.compareAndSet(State.QUEUED, State.RUNNING)
                || state.compareAndSet(State.RESUMING, State.RUNNING)) {
            // Keep the waiting permit through timer expiry and executor queueing; release it only when execution actually owns this turn.
            releaseRetryTicket();
            try {
                executeWork();
            } catch (RuntimeException ignored) {
                // The exact failure is already on the result and has been reported to the observer.
            }
        }
    }

    private T executeWork() {
        return owner.withinOperation(this::executeAndComplete);
    }

    private T executeAndComplete() {
        final T result;
        try (MailSendTelemetryBridge.Scope ignored = attachTelemetry()) {
            control.checkStopped();
            result = work.get();
        } catch (RuntimeException | Error cause) {
            final Throwable terminal = retainRefusalWhenStopped(cause);
            if (state.compareAndSet(State.RUNNING, State.FINISHING)) {
                finish(null, terminal);
            }
            if (terminal instanceof Error) { throw (Error) terminal; }
            throw (RuntimeException) terminal;
        }
        // Publish the timer only after the old execution action has returned. An inline executor must not let a new RUNNING turn
        // be mistaken for the old turn's successful completion, especially when the selected delay is only one nanosecond.
        if (state.get() == State.WAITING_FOR_RETRY) {
            activateRetryTimer();
            return result;
        }
        if (state.compareAndSet(State.RUNNING, State.FINISHING)) {
            final Throwable cleanupFailure = finish(result, null);
            if (cleanupFailure instanceof RuntimeException) { throw (RuntimeException) cleanupFailure; }
            if (cleanupFailure instanceof Error) { throw (Error) cleanupFailure; }
        }
        return result;
    }

    private Throwable retainRefusalWhenStopped(final Throwable cause) {
        return retryingWork != null && (cause instanceof MailSendCancelledException || cause instanceof MailSendTimeoutException)
                && ((MailSendException) cause).getSubmissionReceipt().isEmpty()
                && control.isStopRequested() ? control.stoppedFailure(retryingWork.lastFailure()) : cause;
    }

    private void activateRetryTimer() {
        final MailSendRetryCoordinator.Ticket ticket = retryTicket;
        final Duration delay = retryDelay;
        if (ticket != null) {
            try { ticket.activate(delay); }
            catch (RuntimeException | Error unavailableTimer) {
                final MailerException explanation = new MailerException(MailFailureKind.CAPACITY, "Couldn't schedule this email's retry timer. No further submission was started.", null);
                explanation.addSuppressed(unavailableTimer);
                retryResumeRejected(explanation);
            }
        }
    }

    T executeRetrying(final RetryingWork<T> action) {
        retryingWork = action;
        work = this::resumeRetrying;
        return resumeRetrying();
    }

    private T resumeRetrying() {
        retryingWork.resumed();
        for (;;) {
            control.checkStopped();
            final RetryResult<T> result = retryingWork.execute();
            if (result.nextDelay == null) {
                return result.value;
            }
            retryingWork.waiting();
            if (!synchronous) {
                parkRetry(result.nextDelay);
                return null;
            }
            waitForSynchronousRetry(result.nextDelay);
        }
    }

    private void waitForSynchronousRetry(final Duration delay) {
        try {
            retryingWork.retryScheduled(delay);
            MailSendRetryWait.await(delay, control);
        } catch (RuntimeException failure) {
            throw control.isStopRequested() ? control.stoppedFailure(retryingWork.lastFailure()) : failure;
        }
        retryingWork.resumed();
    }

    private void parkRetry(final Duration delay) {
        final MailSendRetryCoordinator.Ticket ticket;
        try {
            ticket = owner.reserveRetry(this);
        } catch (RuntimeException unavailableCapacity) {
            throw finalRetryAdmissionFailure(unavailableCapacity);
        }
        retryTicket = ticket;
        retryDelay = delay;
        retryingWork.retryScheduled(delay);
        // No attempt state is mutated after publishing WAITING. A cancellation completion may now release the replay snapshot.
        state.set(State.WAITING_FOR_RETRY);
        if (control.isStopRequested()) {
            retireQueuedOperation();
        }
    }

    boolean beginRetryResume() {
        return state.compareAndSet(State.WAITING_FOR_RETRY, State.RESUMING);
    }

    void retryResumeRejected(final RuntimeException rejection) {
        if (state.compareAndSet(State.RESUMING, State.FINISHING) || state.compareAndSet(State.WAITING_FOR_RETRY, State.FINISHING)) {
            releaseRetryTicket();
            owner.completeUnstarted(this, () -> finish(null, finalRetryAdmissionFailure(rejection)));
        }
    }

    private RuntimeException finalRetryAdmissionFailure(final RuntimeException rejection) {
        final RuntimeException refusal = retryingWork.lastFailure();
        if (control.isStopRequested()) {
            return control.stoppedFailure(refusal);
        }
        // Admission/resumption failure did not produce another SMTP result. Keep the genuine refusal primary and explain why retrying stopped.
        refusal.addSuppressed(rejection);
        return control.translateFailure(refusal);
    }

    private void releaseRetryTicket() {
        final MailSendRetryCoordinator.Ticket ticket = retryTicket;
        if (ticket != null) {
            ticket.close();
            retryTicket = null;
        }
    }

    private void retireQueuedOperation() {
        if (state.compareAndSet(State.QUEUED, State.FINISHING) || state.compareAndSet(State.WAITING_FOR_RETRY, State.FINISHING)
                || state.compareAndSet(State.RESUMING, State.FINISHING)) {
            releaseRetryTicket();
            owner.completeUnstarted(this, () -> finish(null, control.stoppedFailure(retryingWork == null ? null : retryingWork.lastFailure())));
        }
    }

    private Throwable finish(final T result, final Throwable cause) {
        state.set(State.FINISHING);
        releaseRetryTicket();
        work = null;
        final Throwable terminalFailure;
        try (MailSendTelemetryBridge.Scope ignored = attachTelemetry()) {
            terminalFailure = closeRetryingWork(cause);
        }
        telemetryScope = null;
        control.close();
        owner.withinOperation(() -> publishOutcomeAndComplete(result, terminalFailure));
        return terminalFailure;
    }

    private Throwable closeRetryingWork(final Throwable cause) {
        Throwable terminalFailure = cause;
        try {
            if (retryingWork != null && cause != null) { retryingWork.failed(); }
            if (retryingWork != null) { retryingWork.close(); }
        } catch (RuntimeException | Error cleanupFailure) {
            if (terminalFailure == null) { terminalFailure = cleanupFailure; }
            else if (terminalFailure != cleanupFailure) { terminalFailure.addSuppressed(cleanupFailure); }
        } finally {
            retryingWork = null;
            retryDelay = null;
        }
        return terminalFailure;
    }

    private void publishOutcomeAndComplete(final T result, final Throwable outcomeFailure) {
        try {
            if (outcomeFailure == null) {
                success.accept(result);
            } else {
                failure.accept(outcomeFailure);
            }
        } finally {
            try {
                if (outcomeFailure == null) {
                    completion.complete(result);
                } else {
                    completion.completeExceptionally(outcomeFailure);
                }
            } finally {
                owner.finished();
                state.set(State.FINISHED);
            }
        }
    }
}
