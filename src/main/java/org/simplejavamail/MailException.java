package org.simplejavamail;

/**
 * This exception is used to communicate errors during the sending of email.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
public class MailException extends RuntimeException {

	static final String GENERIC_ERROR = "Third party error";
	static final String INVALID_ENCODING = "Encoding not accepted";
	static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String MISSING_SENDER = "Email is not valid: missing sender";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_SUBJECT = "Email is not valid: missing subject";
	static final String MISSING_CONTENT = "Email is not valid: missing content body";
	static final String INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM: %s";
	static final String INVALID_PROXY_SLL_COMBINATION = "Proxy is not supported for SSL connections (this is a limitation by the underlying JavaMail framework)";

	MailException(final String message) {
		super(message);
	}

	public MailException(final String message, final Exception cause) {
		super(message, cause);
	}
}