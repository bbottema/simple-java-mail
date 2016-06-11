package org.codemonkey.simplejavamail;

/**
 * This exception is used to communicate errors during the sending of email.
 * 
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
public class MailException extends RuntimeException {

	static final String GENERIC_ERROR = "Third party error: %s";
	static final String MISSING_HOST = "Can't send an email without host";
	static final String MISSING_USERNAME = "Can't have a password without username";
	static final String INVALID_ENCODING = "Encoding not accepted: %s";
	static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String MISSING_SENDER = "Email is not valid: missing sender";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_SUBJECT = "Email is not valid: missing subject";
	static final String MISSING_CONTENT = "Email is not valid: missing content body";
	static final String INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM: %s";

	MailException(final String message) {
		super(message);
	}

	public MailException(final String message, final Exception cause) {
		super(message, cause);
	}
}