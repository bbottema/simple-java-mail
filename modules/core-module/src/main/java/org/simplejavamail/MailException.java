package org.simplejavamail;

/**
 * This exception is used to communicate errors during the creation, validation and sending of email.
 */
@SuppressWarnings("serial")
public abstract class MailException extends RuntimeException {

	protected MailException(final String message) {
		super(message);
	}

	protected MailException(final String message, final Throwable cause) {
		super(message, cause);
	}
}