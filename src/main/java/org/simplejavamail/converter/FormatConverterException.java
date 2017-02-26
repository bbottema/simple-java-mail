package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 * 
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class FormatConverterException extends MailException {

	static final String PARSE_ERROR_MIMEMESSAGE = "Error parsing MimeMessage: %s";

	public FormatConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}