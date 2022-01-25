package org.simplejavamail.mailer.internal;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the sending of email.
 */
@SuppressWarnings("serial")
class MailerException extends MailException {

	static final String ERROR_READING_SMIME_FROM_INPUTSTREAM = "Was unable to read S/MIME data from input stream";
	static final String ERROR_READING_FROM_FILE = "Error reading from file: %s";
	static final String INVALID_PROXY_SLL_COMBINATION = "Proxy is not supported for SSL connections (this is a limitation by the underlying JavaMail framework)";
	static final String ERROR_CONNECTING_SMTP_SERVER = "Was unable to connect to SMTP server";
	static final String GENERIC_ERROR = "Failed to send email [%s], reason: Third party error";
	static final String INVALID_ENCODING = "Failed to send email [%s], reason: Encoding not accepted";
	static final String UNKNOWN_ERROR = "Failed to send email [%s], reason: Unknown error";

	MailerException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}

	MailerException(final String message, final Exception cause) {
		super(message, cause);
	}
}