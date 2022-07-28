package org.simplejavamail.mailer;

public class MailSuspiciousCRLFValueException extends MailValidationException {

	static final String INJECTION_SUSPECTED = "Suspected of injection attack, field: %s with suspicious value: %s";


	MailSuspiciousCRLFValueException(final String message) {
		super(message);
	}
}