package org.simplejavamail.api.mailer;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.RejectedExecutionException;

import static java.util.Objects.requireNonNull;

/**
 * An asynchronous operation was rejected before worker execution, so it acquired no SMTP transport or proxy resources.
 * Ordinary sends report this same exception through their completion and terminal observer; rejected simple batches remain uniterated.
 */
@Getter
public final class MailSendRejectedException extends RejectedExecutionException {
    private static final long serialVersionUID = 1L;
    /** Why admission failed; this is a scheduling result, not an SMTP status. */
    @NotNull private final AsyncQueueRejectionReason reason;

    /** Creates a scheduling failure without retaining the email or executor. */
    public MailSendRejectedException(@NotNull final AsyncQueueRejectionReason reason) {
        super("Asynchronous mail operation was not scheduled: " + requireNonNull(reason, "reason"));
        this.reason = reason;
    }
}
