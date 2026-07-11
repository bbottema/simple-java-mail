package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Receipt for one message submission attempt that completed without throwing an exception.
 * <p>
 * This represents SMTP submission acceptance, not final mailbox delivery. Final delivery remains asynchronous and should be tracked through delivery
 * status notifications, read receipts, bounces, or provider-specific webhooks.
 *
 * @see Mailer#sendMailAndGetReceipt(org.simplejavamail.api.email.Email)
 * @see MailSender#sendMailAndGetReceipt(org.simplejavamail.api.email.Email)
 */
public final class MailSubmissionReceipt implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable private final String emailId;
	@Nullable private final SmtpServerResponse smtpResponse;
	@NotNull private final Instant submittedAt;

	public MailSubmissionReceipt(@Nullable final String emailId, @Nullable final SmtpServerResponse smtpResponse, @NotNull final Instant submittedAt) {
		this.emailId = emailId;
		this.smtpResponse = smtpResponse;
		this.submittedAt = checkNonEmptyArgument(submittedAt, "submittedAt");
	}

	/**
	 * @return The effective Message-ID for the submitted email, or {@code null} if no message id was produced by the sending path.
	 */
	@Nullable
	public String getEmailId() {
		return emailId;
	}

	/**
	 * @return The SMTP server response if the message was submitted through an Angus SMTP transport.
	 */
	@NotNull
	public Optional<SmtpServerResponse> getSmtpResponse() {
		return Optional.ofNullable(smtpResponse);
	}

	/**
	 * @return {@code true} if an SMTP server response was captured and it was a 2xx positive completion reply.
	 */
	public boolean isAcceptedByServer() {
		return smtpResponse != null && smtpResponse.isPositiveCompletionReply();
	}

	/**
	 * @return The timestamp captured after the send path completed.
	 */
	@NotNull
	public Instant getSubmittedAt() {
		return submittedAt;
	}
}
