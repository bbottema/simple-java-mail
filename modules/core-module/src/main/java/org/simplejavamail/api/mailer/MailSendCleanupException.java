package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Cleanup failed after an individual email's submission. Its receipt describes that email, never an entire shared connection scope. */
public final class MailSendCleanupException extends MailSendException {

	private static final long serialVersionUID = 1L;

	/** Retains the original cleanup cause and any immutable facts captured for this individual send. */
	public MailSendCleanupException(@NotNull final String message, @NotNull final Throwable cause,
			@Nullable final MailSubmissionReceipt submissionReceipt) {
		super(message, cause, submissionReceipt);
	}
}
