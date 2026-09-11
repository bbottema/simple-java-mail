package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import java.util.Optional;

/** A requested stop ended the send. Inspect any receipt before retrying: cancellation does not imply SMTP non-acceptance. */
public final class MailSendCancelledException extends MailException {
    private static final long serialVersionUID = 1L;
    @Nullable private final MailSubmissionReceipt submissionReceipt;

    /** Retains the original operation failure, if any, and the same immutable submission facts used by the observer. */
    public MailSendCancelledException(@Nullable final Throwable cause, @Nullable final MailSubmissionReceipt submissionReceipt) {
        super("Mail send was cancelled; inspect the submission receipt before retrying", cause);
        this.submissionReceipt = submissionReceipt;
    }

    /** @return Submission facts, or empty when the operation stopped before SMTP submission began. */
    @NotNull
    public Optional<MailSubmissionReceipt> getSubmissionReceipt() {
        return Optional.ofNullable(submissionReceipt);
    }
}
