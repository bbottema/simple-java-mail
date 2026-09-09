package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMTP server response captured during a message submission attempt.
 * <p>
 * On a {@link MailSubmissionReceipt}, a final positive response confirms message acceptance for processing, not mailbox delivery.
 * On a {@link MailRecipientResult}, a positive RCPT response accepts only that envelope recipient; DATA can still fail afterwards.
 *
 * @see MailSubmissionReceipt
 */
public final class SmtpServerResponse implements Serializable {

	private static final long serialVersionUID = 1L;
	private static final Pattern ENHANCED_REPLY = Pattern.compile(
			"([245][0-9]{2})[ -]([245]\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2}))(?:[ \\t].*)?");

	private final int returnCode;
	@Nullable private final String response;

	public SmtpServerResponse(final int returnCode, @Nullable final String response) {
		this.returnCode = returnCode;
		this.response = response;
	}

	/**
	 * @return The SMTP return code for the reported command.
	 */
	public int getReturnCode() {
		return returnCode;
	}

	/**
	 * @return The raw SMTP reply text, or {@code null} if the transport did not provide it. Treat this as untrusted server text when logging it.
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

	/**
	 * Returns an RFC 3463 enhanced status such as {@code 5.1.1}, when it appears at the start of the reply text.
	 * Malformed, conflicting or primary-code-mismatched statuses remain absent. Every line of a multiline enhanced reply
	 * must agree, as required by RFC 2034. Unknown but syntactically valid subject/detail values are preserved.
	 */
	@NotNull
	public Optional<String> getEnhancedStatusCode() {
		if (response == null || response.isEmpty()) {
			return Optional.empty();
		}
		String enhancedStatus = null;
		for (final String line : response.split("\\r?\\n")) {
			final Matcher reply = ENHANCED_REPLY.matcher(line);
			if (!reply.matches() || Integer.parseInt(reply.group(1)) != returnCode
					|| reply.group(2).charAt(0) - '0' != returnCode / 100
					|| enhancedStatus != null && !enhancedStatus.equals(reply.group(2))) {
				return Optional.empty();
			}
			enhancedStatus = reply.group(2);
		}
		return Optional.ofNullable(enhancedStatus);
	}
}
