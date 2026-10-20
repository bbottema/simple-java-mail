package org.simplejavamail.mailer;

import org.simplejavamail.api.mailer.MailFailureKind;

import org.simplejavamail.MailException;

public abstract class MailValidationException extends MailException {

	MailValidationException(final String message) {
		super(MailFailureKind.VALIDATION, message, null, null);
	}
}
