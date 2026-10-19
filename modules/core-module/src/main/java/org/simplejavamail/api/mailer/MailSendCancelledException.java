package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.Nullable;

/** A requested stop ended the send. Inspect any receipt before retrying: cancellation does not imply SMTP non-acceptance. */
public final class MailSendCancelledException extends MailSendException {
    private static final long serialVersionUID = 1L;

    /** Retains the original operation failure, if any, and the same immutable submission facts used by the observer. */
    public MailSendCancelledException(@Nullable final Throwable cause, @Nullable final MailSubmissionReceipt submissionReceipt) {
        super("Mail send was cancelled; inspect the submission receipt before retrying", cause, submissionReceipt);
    }
}
