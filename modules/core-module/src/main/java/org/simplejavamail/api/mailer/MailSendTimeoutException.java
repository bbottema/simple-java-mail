package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.Nullable;

/** The total send budget expired. A timeout is not proof of non-acceptance; an uncertain receipt can require duplicate-aware retry handling. */
public final class MailSendTimeoutException extends MailSendException {
    private static final long serialVersionUID = 1L;

    /** Retains the original operation failure, if any, and the same immutable submission facts used by the observer. */
    public MailSendTimeoutException(@Nullable final Throwable cause, @Nullable final MailSubmissionReceipt submissionReceipt) {
        super("Mail-send timeout expired; inspect the submission receipt before retrying", cause, submissionReceipt);
    }
}
