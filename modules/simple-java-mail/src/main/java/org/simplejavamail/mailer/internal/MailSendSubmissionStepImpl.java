package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendSubmissionStep;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link MailSendSubmissionStep}. */
@ApiStatus.Internal
public final class MailSendSubmissionStepImpl implements MailSendSubmissionStep {
    private static final long serialVersionUID = 1L;
    private final int attemptNumber;
    private final MailSubmissionStatus submissionStatus;
    @Nullable private final Integer smtpReplyCode;
    private final Duration elapsed;
    @Nullable private final Duration retryDelay;
    @Nullable private final String explanation;

    /** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSendSubmissionStepImpl(final int attemptNumber, @NotNull final MailSubmissionStatus submissionStatus,
            @Nullable final Integer smtpReplyCode, @NotNull final Duration elapsed, @Nullable final Duration retryDelay,
            @Nullable final String explanation) {
        validateAttemptAndReply(attemptNumber, smtpReplyCode);
        this.elapsed = requireNonNull(elapsed, "elapsed");
        validateDurations(elapsed, retryDelay);
        this.attemptNumber = attemptNumber;
        this.submissionStatus = requireNonNull(submissionStatus, "submissionStatus");
        this.smtpReplyCode = smtpReplyCode;
        this.retryDelay = retryDelay;
        this.explanation = explanation == null ? null : SmtpDiagnosticText.display(explanation);
    }

    private static void validateAttemptAndReply(final int attemptNumber, @Nullable final Integer smtpReplyCode) {
        if (attemptNumber < 1 || isInvalidReplyCode(smtpReplyCode)) {
            throw new IllegalArgumentException("Supply a positive submission attempt number and a genuine SMTP reply code, or null when no code was observed.");
        }
    }

    private static boolean isInvalidReplyCode(@Nullable final Integer smtpReplyCode) {
        return smtpReplyCode != null && (smtpReplyCode < 100 || smtpReplyCode > 599);
    }

    private static void validateDurations(final Duration elapsed, @Nullable final Duration retryDelay) {
        if (elapsed.isNegative() || (retryDelay != null && retryDelay.isNegative())) {
            throw new IllegalArgumentException("Submission elapsed time and the selected retry delay cannot be negative.");
        }
    }

    /** @see MailSendSubmissionStep#getAttemptNumber() */
	@Override
	public int getAttemptNumber() { return attemptNumber; }
    /** @see MailSendSubmissionStep#getSubmissionStatus() */
	@Override
	@NotNull
	public MailSubmissionStatus getSubmissionStatus() { return submissionStatus; }
    /** @see MailSendSubmissionStep#getSmtpReplyCode() */
	@Override
	@Nullable
	public Integer getSmtpReplyCode() { return smtpReplyCode; }
    /** @see MailSendSubmissionStep#getElapsed() */
	@Override
	@NotNull
	public Duration getElapsed() { return elapsed; }
    /** @see MailSendSubmissionStep#getRetryDelay() */
	@Override
	@Nullable
	public Duration getRetryDelay() { return retryDelay; }
    /** @see MailSendSubmissionStep#getExplanation() */
	@Override
	@Nullable
	public String getExplanation() { return explanation; }

	/** @see MailSendSubmissionStep#toString() */
	@Override
	public String toString() {
        return "Attempt " + attemptNumber
                + ": " + submissionStatus
                + (smtpReplyCode == null ? "" : " (SMTP " + smtpReplyCode + ")")
                + ", submission=" + elapsed
                + (retryDelay == null ? "" : ", next delay=" + retryDelay)
                + (explanation == null ? "" : "; " + explanation);
    }
}
