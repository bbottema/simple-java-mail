package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * SMTP server response captured during a message submission attempt.
 * <p>
 * A positive response such as {@code 250 ... queued as ...} confirms that the SMTP server accepted the message for processing, not that the message
 * was finally delivered to the recipient mailbox. A failed or partial attempt may instead expose the provider's last negative submission response.
 *
 * @see MailSubmissionReceipt
 */
public final class SmtpServerResponse implements Serializable {

	private static final long serialVersionUID = 1L;

	private final int returnCode;
	@Nullable private final String response;

	public SmtpServerResponse(final int returnCode, @Nullable final String response) {
		this.returnCode = returnCode;
		this.response = response;
	}

	/**
	 * @return The SMTP return code reported by the transport after message submission.
	 */
	public int getReturnCode() {
		return returnCode;
	}

	/**
	 * @return The raw SMTP server response reported by the transport after message submission, or {@code null} if the transport did not provide one.
	 */
	@Nullable
	public String getResponse() {
		return response;
	}

	/**
	 * @return {@code true} if the response code is an SMTP 2xx positive completion reply.
	 */
	public boolean isPositiveCompletionReply() {
		return returnCode >= 200 && returnCode < 300;
	}
}
