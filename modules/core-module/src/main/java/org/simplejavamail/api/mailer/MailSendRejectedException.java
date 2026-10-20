package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Execution admission for a send or asynchronous connection check was rejected before worker execution
 * (or before a synchronous send on a closed Mailer), so it acquired no SMTP transport or proxy resources.
 * Ordinary sends report this same exception through their completion and terminal observer; rejected simple batches remain uniterated.
 */
public final class MailSendRejectedException extends MailSendException {
    private static final long serialVersionUID = 1L;
    /** Why admission failed; this is a scheduling result, not an SMTP status. The failure kind is CAPACITY. */
    @NotNull private final AsyncQueueRejectionReason reason;

    /** Creates a scheduling failure without retaining the email or executor. */
    public MailSendRejectedException(@NotNull final AsyncQueueRejectionReason reason) {
        this(reason, null);
    }

    /** Retains a borrowed executor's original rejection; no receipt is manufactured for unstarted work. */
    public MailSendRejectedException(@NotNull final AsyncQueueRejectionReason reason, @Nullable final Throwable cause) {
        super(MailFailureKind.CAPACITY, "Mail operation was not scheduled: " + requireNonNull(reason, "reason"), cause, null);
        this.reason = reason;
    }

    /** Returns why execution admission failed, rather than an SMTP server response. */
    @NotNull
    public AsyncQueueRejectionReason getReason() {
        return reason;
    }
}
