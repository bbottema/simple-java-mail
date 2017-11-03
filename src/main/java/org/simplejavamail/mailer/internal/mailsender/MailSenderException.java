package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the sending of email.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class MailSenderException extends MailException {

	static final String INVALID_PROXY_SLL_COMBINATION = "Proxy is not supported for SSL connections (this is a limitation by the underlying JavaMail framework)";
	static final String GENERIC_ERROR = "Third party error";
	static final String INVALID_ENCODING = "Encoding not accepted";
	static final String CANNOT_SET_TRUST_WITHOUT_TRANSPORTSTRATEGY =
			"Cannot determine the trust properties to set without a provided transport strategy";
	static String CANNOT_SET_BOUNCETO_WITHOUT_TRANSPORTSTRATEGY =
			"Cannot determine the envelope .from property to set without a provided transport strategy";
	
	MailSenderException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}

	MailSenderException(final String message, final Exception cause) {
		super(message, cause);
	}
}