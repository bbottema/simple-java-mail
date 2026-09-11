package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * One mail-send operation: await its actual result through {@link #getCompletion()}, and request cancellation only when needed.
 * A cancellation request does not prove that SMTP submission was prevented. A late request can still complete with an accepted receipt;
 * an uncertain final reply retains its duplicate-risk guidance.
 *
 * @param <T> The completed value, normally {@link Void} or {@link MailSubmissionReceipt}.
 */
public final class MailSend<T> {

    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final Runnable cancellationRequest;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    /**
     * Creates a handle for a Mailer implementation. The producer owns the supplied completion stage and must complete it only after
     * operation exit, required cleanup, and the configured observer ordering. The cancellation action must request stopping that work,
     * not merely cancel its future; it must tolerate the race with operation completion and must not invoke application observers.
     */
    public MailSend(@NotNull final CompletionStage<T> completion, @NotNull final Runnable cancellationRequest) {
        this.cancellationRequest = requireNonNull(cancellationRequest, "cancellationRequest");
        requireNonNull(completion, "completion").whenComplete((value, failure) -> {
            if (failure == null) {
                this.completion.complete(value);
            } else {
                this.completion.completeExceptionally(failure);
            }
        });
    }

    /**
     * Returns a detached future for the real send result. Each call returns a new view retaining the original result or failure.
     * Cancelling, completing, or timing out a view affects that view only; use {@link #requestCancellation()} to ask the send to stop.
     * Inline observers return before completion. Executor-backed observers are handed off before completion but need not have run yet.
     */
    @NotNull
    public CompletableFuture<T> getCompletion() {
        final CompletableFuture<T> view = new CompletableFuture<>();
        completion.whenComplete((value, failure) -> {
            if (failure == null) {
                view.complete(value);
            } else {
                view.completeExceptionally(failure);
            }
        });
        return view;
    }

    /**
     * Requests cancellation of remaining work. Repeated requests and requests after completion do nothing.
     * This is not an acknowledgement that work stopped or that email was unsent: inspect the eventual completion and receipt.
     * Supported transports abort I/O; other integrations can stop only at cooperative boundaries. Application callbacks cannot be forcibly stopped.
     */
    public void requestCancellation() {
        if (!completion.isDone() && cancellationRequested.compareAndSet(false, true)) {
            cancellationRequest.run();
        }
    }
}
