package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailConverterException extends MailException {
	
	static final String FILE_NOT_RECOGNIZED_AS_EML = "Eml file should have \".eml\" extension: %s";
	static final String FILE_NOT_RECOGNIZED_AS_OUTLOOK = "Outlook file should have \".msg\" extension: %s";
	static final String PARSE_ERROR_EML = "Error parsing EML data: %s";
	static final String ERROR_READING_EML_INPUTSTREAM = "Error reading EML string from given InputStream";

	EmailConverterException(final String message) {
		super(message);
	}

	EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}