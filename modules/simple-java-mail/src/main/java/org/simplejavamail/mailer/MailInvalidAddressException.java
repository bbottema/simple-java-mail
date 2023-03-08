package org.simplejavamail.mailer;

public class MailInvalidAddressException extends MailValidationException {

	static final String INVALID_TO_RECIPIENT = "Invalid TO address: %s";
	static final String INVALID_CC_RECIPIENT = "Invalid CC address: %s";
	static final String INVALID_BCC_RECIPIENT = "Invalid BCC address: %s";
	static final String INVALID_REPLYTO = "Invalid REPLY TO address: %s";
	static final String INVALID_BOUNCETO = "Invalid BOUNCE TO address: %s";
	static final String INVALID_SENDER = "Invalid FROM address: %s";
	static final String INVALID_DISPOSITIONNOTIFICATIONTO = "Invalid \"Disposition Notification To\" address: %s";
	static final String INVALID_RETURNRECEIPTTO = "Invalid \"Return Receipt To\" address: %s";


	MailInvalidAddressException(final String message) {
		super(message);
	}
}