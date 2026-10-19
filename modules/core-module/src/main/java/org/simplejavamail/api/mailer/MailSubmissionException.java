package org.simplejavamail.api.mailer;

import jakarta.mail.MessagingException;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * Reports a failed or partial mail submission while retaining provider-neutral recipient facts.
 * <p>
 * The original Jakarta Mail {@link MessagingException}, including a {@link jakarta.mail.SendFailedException} when supplied by the provider, remains
 * the direct cause. {@link #getSubmissionReceipt()} reports which recipients are known to have been accepted, left unsent, or rejected as invalid.
 * When its status is {@link MailSubmissionStatus#UNKNOWN}, some recipients may already have been accepted and an automatic retry can create duplicates.
 */
public final class MailSubmissionException extends MailSendException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a submission failure whose direct cause remains the provider's original Jakarta Mail exception.
	 *
	 * @param message            The failure description.
	 * @param cause              The original Jakarta Mail submission failure.
	 * @param submissionReceipt The provider-neutral facts captured for the same attempt.
	 */
	public MailSubmissionException(@NotNull final String message,
			@NotNull final MessagingException cause,
			@NotNull final MailSubmissionReceipt submissionReceipt) {
		super(message, cause, requireNonNull(submissionReceipt, "submissionReceipt"));
	}

	/**
	 * @return The provider-neutral acceptance status for this failure.
	 */
	@NotNull
	public MailSubmissionStatus getStatus() {
		return getSubmissionReceipt().orElseThrow().getStatus();
	}

	/**
	 * @return The original Jakarta Mail failure.
	 */
	@Override
	@NotNull
	public synchronized MessagingException getCause() {
		return (MessagingException) super.getCause();
	}
}
