package org.simplejavamail;

/**
 * This exception is used to communicate errors during the creation, validation and sending of email.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
public abstract class MailException extends RuntimeException {

	protected MailException(final String message) {
		super(message);
	}

	protected MailException(final String message, final Exception cause) {
		super(message, cause);
	}
}