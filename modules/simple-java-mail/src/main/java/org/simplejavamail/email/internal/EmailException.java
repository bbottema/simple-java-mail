package org.simplejavamail.email.internal;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailException extends MailException {
	
	static final String NAME_MISSING_FOR_EMBEDDED_IMAGE = "No name given for embedded image nor passed inside the data source";
	static final String ERROR_READING_FROM_FILE = "Error reading from file: %s";
	static final String ERROR_READING_FROM_PEM_INPUTSTREAM = "Was unable to convert PEM data to X509 certificate";
	static final String ERROR_LOADING_PROVIDER_FOR_SMIME_SUPPORT = "Unable to load certificate (missing bouncy castle), is the S/MIME module on the class path?";

	EmailException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}
	
	EmailException(@SuppressWarnings("SameParameterValue") final String message, final Exception cause) {
		super(message, cause);
	}
}