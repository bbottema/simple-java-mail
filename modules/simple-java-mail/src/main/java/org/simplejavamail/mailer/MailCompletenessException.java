package org.simplejavamail.mailer;

public class MailCompletenessException extends MailValidationException {

	static final String MISSING_SENDER = "Email is not valid: missing sender. Provide with emailBuilder.from(...)";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_DISPOSITIONNOTIFICATIONTO = "Email is not valid: it is set to use \"Disposition Notification To\", but the address is empty";
	static final String MISSING_RETURNRECEIPTTO = "Email is not valid: it is set to use \"Return Receipt To\", but the address is empty";
	static final String INJECTION_SUSPECTED = "Suspected of injection attack, field: %s with suspicious value: %s";


	MailCompletenessException(final String message) {
		super(message);
	}
}