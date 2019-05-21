package org.simplejavamail.mailer.internal;

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
	static final String MISSING_SENDER = "Email is not valid: missing sender. Provide with emailBuilder.from(...)";
	static final String MISSING_RECIPIENT = "Email is not valid: missing recipients";
	static final String MISSING_DISPOSITIONNOTIFICATIONTO = "Email is not valid: it is set to use \"Disposition Notification To\", but the address is empty";
	static final String MISSING_RETURNRECEIPTTO = "Email is not valid: it is set to use \"Return Receipt To\", but the address is empty";
	static final String INJECTION_SUSPECTED = "Suspected of injection attack, field: %s with suspicious value: %s";
	static final String SMIME_MODULE_NOT_AVAILABLE = "Cannot sign and/or encrypt message, S/MIME module not found";

	MailerException(final String message) {
		super(message);
	}
}