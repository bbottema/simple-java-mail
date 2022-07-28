package org.simplejavamail.mailer;

import org.simplejavamail.MailException;

public abstract class MailValidationException extends MailException {

	MailValidationException(final String message) {
		super(message);
	}
}