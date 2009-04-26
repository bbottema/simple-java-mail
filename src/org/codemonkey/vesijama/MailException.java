package org.codemonkey.vesijama;

import javax.mail.MessagingException;

/**
 * This exception is used to communicate errors during the sending of email.
 */
@SuppressWarnings("serial")
public final class MailException extends RuntimeException {

	protected static final String GENERIC_ERROR = "Generic error: %s";
	protected static final String INVALID_ENCODING = "Encoding not accepted: %s";
	protected static final String INVALID_RECIPIENT = "To addres is niet goed gevuld: %s";
	protected static final String INVALID_SENDER = "From addres is niet goed gevuld: %s";
	protected static final String MISSING_SENDER = "Email is niet goed gevuld: geen verzender gedefinieerd";
	protected static final String MISSING_RECIPIENT = "Email is niet goed gevuld: geen ontvangers gedefinieerd";
	protected static final String MISSING_SUBJECT = "Email is niet goed gevuld: geen subject gedefinieerd";
	protected static final String MISSING_CONTENT = "Email is niet goed gevuld: geen inhoud gedefinieerd";

	protected MailException(final String message) {
		super(message);
	}

	protected MailException(final String message, final MessagingException cause) {
		super(message, cause);
	}
}