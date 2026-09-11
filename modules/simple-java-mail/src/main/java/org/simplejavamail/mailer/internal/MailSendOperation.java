package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Arbitrates execution versus removal without completing a future or invoking application code on a deadline thread. */
final class MailSendOperation<T> implements Runnable {
    private enum State { PREPARING, ADMITTING, QUEUED, RUNNING, FINISHING }

    private final MailSendOperations owner;
    private final MailSendControl control;
    private final Consumer<T> success;
    private final Consumer<Throwable> failure;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.PREPARING);
    private Supplier<T> work;

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
                finish(null, rejection instanceof RuntimeException ? control.translateFailure((RuntimeException) rejection) : rejection);
            }
            if (rejection instanceof Error) {
                throw (Error) rejection;
            }
        }
    }

    T executeSync(final Supplier<T> action) {
        work = action;
        state.set(State.RUNNING);
        return executeWork();
    }

    void preparationFailed(final Throwable cause) {
        state.set(State.FINISHING);
        finish(null, cause);
    }

    @Override
    public void run() {
        if (state.compareAndSet(State.ADMITTING, State.RUNNING) || state.compareAndSet(State.QUEUED, State.RUNNING)) {
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
        try {
            control.checkStopped();
            result = work.get();
        } catch (RuntimeException | Error cause) {
            finish(null, cause);
            throw cause;
        }
        finish(result, null);
        return result;
    }

    private void retireQueuedOperation() {
        if (state.compareAndSet(State.QUEUED, State.FINISHING)) {
            owner.completeUnstarted(this, () -> finish(null, control.stoppedFailure(null)));
        }
    }

    private void finish(final T result, final Throwable cause) {
        state.set(State.FINISHING);
        work = null;
        control.close();
        owner.withinOperation(() -> {
            try {
                if (cause == null) {
                    success.accept(result);
                } else {
                    failure.accept(cause);
                }
            } finally {
                try {
                    if (cause == null) {
                        completion.complete(result);
                    } else {
                        completion.completeExceptionally(cause);
                    }
                } finally {
                    owner.finished();
                }
            }
        });
    }
}
