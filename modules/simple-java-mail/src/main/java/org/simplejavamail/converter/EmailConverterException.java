package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailConverterException extends MailException {
	
	static final String PARSE_ERROR_EML_FROM_FILE = "Error parsing EML data from file: %s";
	static final String PARSE_ERROR_EML_FROM_STREAM = "Error parsing EML data from input stream: %s";
	static final String ERROR_READING_EML_INPUTSTREAM = "Error reading EML string from given InputStream";

	EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}