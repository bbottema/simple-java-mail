package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import java.util.Optional;

/** The total send budget expired. A timeout is not proof of non-acceptance; an uncertain receipt can require duplicate-aware retry handling. */
public final class MailSendTimeoutException extends MailException {
    private static final long serialVersionUID = 1L;
    @Nullable private final MailSubmissionReceipt submissionReceipt;

    /** Retains the original operation failure, if any, and the same immutable submission facts used by the observer. */
    public MailSendTimeoutException(@Nullable final Throwable cause, @Nullable final MailSubmissionReceipt submissionReceipt) {
        super("Mail-send timeout expired; inspect the submission receipt before retrying", cause);
        this.submissionReceipt = submissionReceipt;
    }

    /** @return Submission facts, or empty when the operation stopped before SMTP submission began. */
    @NotNull
    public Optional<MailSubmissionReceipt> getSubmissionReceipt() {
        return Optional.ofNullable(submissionReceipt);
    }
}
