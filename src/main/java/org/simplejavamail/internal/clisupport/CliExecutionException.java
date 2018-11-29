package org.simplejavamail.internal.clisupport;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class CliExecutionException extends MailException {
	
	static final String DID_NOT_RECOGNIZE_EMAIL_FILETYPE = "Did not recognize file type for: %s";
	static final String ERROR_INVOKING_BUILDER_API = "Got error while invoking Builder API";
	
	CliExecutionException(String message) {
		super(message);
	}
	
	CliExecutionException(String message, Exception cause) {
		super(message, cause);
	}
}