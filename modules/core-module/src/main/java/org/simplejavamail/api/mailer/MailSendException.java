package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import java.util.Optional;

/** A send failure retaining any known SMTP submission facts independently of the failure's cause. */
public abstract class MailSendException extends MailException {

	private static final long serialVersionUID = 1L;
	@Nullable private final MailSubmissionReceipt submissionReceipt;

	protected MailSendException(@NotNull final String message, @Nullable final Throwable cause,
			@Nullable final MailSubmissionReceipt submissionReceipt) {
		super(message, cause);
		this.submissionReceipt = submissionReceipt;
	}

	/**
	 * Returns captured submission facts, including acceptance before a later cleanup failure. Empty means unavailable, not proof of non-acceptance.
	 * Inspect retry guidance before resending; cancellation, timeouts and cleanup failures do not undo SMTP acceptance.
	 */
	@NotNull public final Optional<MailSubmissionReceipt> getSubmissionReceipt() { return Optional.ofNullable(submissionReceipt); }
}
