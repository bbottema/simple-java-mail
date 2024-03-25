package org.simplejavamail.recipient;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the sending of email.
 */
class RecipientException extends MailException {

	static final String MISSING_ADDRESS = "Address is required";

	RecipientException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}
}