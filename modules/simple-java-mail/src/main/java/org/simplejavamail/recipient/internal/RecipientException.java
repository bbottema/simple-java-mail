package org.simplejavamail.recipient.internal;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

/**
 * This exception is used to communicate errors during the sending of email.
 */
class RecipientException extends MailException {

	static final String MISSING_ADDRESS = "Address is required";

	RecipientException(@SuppressWarnings("SameParameterValue") final String message) {
		super(MailFailureKind.VALIDATION, message, null, null);
	}
}
