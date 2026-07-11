package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * SMTP server response captured after a successful message submission.
 * <p>
 * This is the SMTP submission response, such as {@code 250 ... queued as ...}. It confirms that the SMTP server accepted the message for processing,
 * not that the message was finally delivered to the recipient mailbox.
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
