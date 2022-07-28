package org.simplejavamail.mailer;

public class MailInvalidAddressException extends MailValidationException {

	static final String INVALID_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_BOUNCETO = "Invalid BOUNCE TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String INVALID_DISPOSITIONNOTIFICATIONTO = "Invalid \"Disposition Notification To\" address: %s";
	static final String INVALID_RETURNRECEIPTTO = "Invalid \"Return Receipt To\" address: %s";


	MailInvalidAddressException(final String message) {
		super(message);
	}
}