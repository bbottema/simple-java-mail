package org.simplejavamail.converter.internal;

import org.simplejavamail.MailException;
import org.jetbrains.annotations.ApiStatus;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
@ApiStatus.Internal
public class EmailConverterException extends MailException {

	public static final String PARSE_ERROR_EML_FROM_FILE = "Error parsing EML data from file: %s";
	public static final String PARSE_ERROR_EML_FROM_PATH = "Error parsing EML data from path [%s]: %s";
	public static final String PARSE_ERROR_EML_FROM_STREAM = "Error parsing EML data from input stream: %s";
	public static final String ERROR_READING_EML_INPUTSTREAM = "Error reading EML string from given InputStream";
	public static final String ERROR_READING_OUTLOOK_PATH = "Error reading Outlook MSG data from path [%s]: %s";

	public EmailConverterException(final String message, final Exception cause) {
		super(message, cause);
	}
}
