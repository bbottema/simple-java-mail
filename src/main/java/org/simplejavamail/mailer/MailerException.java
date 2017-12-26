package org.simplejavamail.mailer;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the validation of email.
 */
@SuppressWarnings("serial")
class MailerException extends MailException {

	static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_BOUNCETO = "Invalid BOUNCE TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String INVALID_DISPOSITIONNOTIFICATIONTO = "Invalid \"Disposition Notification To\" address: %s";
	static final String INVALID_RETURNRECEIPTTO = "Invalid \"Return Receipt To\" address: %s";
	static final String MISSING_SENDER = "Email is not valid: missing sender";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_DISPOSITIONNOTIFICATIONTO = "Email is not valid: missing \"Disposition Notification To\" address";
	static final String MISSING_RETURNRECEIPTTO = "Email is not valid: missing \"Return Receipt To\" address";
	static final String INJECTION_SUSPECTED = "Suspected of injection attack, field: %s with suspicious value: %s";

	MailerException(final String message) {
		super(message);
	}
}