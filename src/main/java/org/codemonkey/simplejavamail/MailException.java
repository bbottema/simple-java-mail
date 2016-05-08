package org.codemonkey.simplejavamail;

/**
 * This exception is used to communicate errors during the sending of email.
 * 
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
public final class MailException extends RuntimeException {

	protected static final String GENERIC_ERROR = "Generic error: %s";
	protected static final String MISSING_HOST = "Can't send an email without host";
	protected static final String MISSING_USERNAME = "Can't have a password without username";
	protected static final String INVALID_ENCODING = "Encoding not accepted: %s";
	protected static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	protected static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	protected static final String INVALID_SENDER = "Invalid FROM address: %s";
	protected static final String MISSING_SENDER = "Email is not valid: missing sender";
	protected static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	protected static final String MISSING_SUBJECT = "Email is not valid: missing subject";
	protected static final String MISSING_CONTENT = "Email is not valid: missing content body";

	protected MailException(final String message) {
		super(message);
	}

	public MailException(final String message, final Exception cause) {
		super(message, cause);
	}
}