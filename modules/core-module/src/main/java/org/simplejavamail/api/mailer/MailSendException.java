package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

/** A send failure retaining any known SMTP submission facts independently of the failure's cause. */
public abstract class MailSendException extends MailException {

	private static final long serialVersionUID = 1L;
	protected MailSendException(@NotNull final String message, @Nullable final Throwable cause,
			@Nullable final MailSubmissionReceipt submissionReceipt) {
		this(MailFailureKind.OTHER, message, cause, submissionReceipt);
	}

	protected MailSendException(@NotNull final MailFailureKind kind, @NotNull final String message, @Nullable final Throwable cause,
			@Nullable final MailSubmissionReceipt submissionReceipt) {
		super(kind, message, cause, submissionReceipt);
	}
}
