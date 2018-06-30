package org.simplejavamail.converter;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailConverterException extends MailException {
	
	static final String PARSE_ERROR_EML = "Error parsing EML data: %s";
	static final String ERROR_OUTLOOK_MSGPARSER_LIBRARY_MISSING = "Outlook Message Parser library not found, make sure it is on the classpath (https://github.com/bbottema/outlook-message-parser)";
	static final String ERROR_LOADING_OUTLOOK_MSGPARSER_LIBRARY = "Error loading the Outlook Message Parsing library...";

	public EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}