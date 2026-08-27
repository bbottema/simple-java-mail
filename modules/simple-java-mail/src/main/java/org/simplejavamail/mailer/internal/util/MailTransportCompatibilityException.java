package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;

/** A transport capability mismatch detected before message submission begins. */
final class MailTransportCompatibilityException extends MessagingException {

	private static final long serialVersionUID = 1L;

	MailTransportCompatibilityException(final String message) {
		super(message);
	}
}
