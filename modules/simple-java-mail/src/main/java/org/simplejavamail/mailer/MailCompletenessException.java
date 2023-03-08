package org.simplejavamail.mailer;

public class MailCompletenessException extends MailValidationException {

	static final String MISSING_SENDER = "Email is not valid: missing sender. Provide with emailBuilder.from(...)";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";

	MailCompletenessException(final String message) {
		super(message);
	}
}