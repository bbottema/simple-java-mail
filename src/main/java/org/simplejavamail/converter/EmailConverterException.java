package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 * 
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class EmailConverterException extends MailException {

	static final String PARSE_ERROR_EML = "Error parsing EML data: %s";

	public EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}