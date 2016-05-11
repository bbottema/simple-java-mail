package org.codemonkey.simplejavamail.email;

import org.codemonkey.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 * 
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
public class EmailException extends MailException {

	protected static final String PARSE_ERROR_MIMEMESSAGE = "Error parsing MimeMessage: %s";
	protected static final String DKIM_ERROR_INVALID_FILE = "Private key not found: %s";
	protected static final String DKIM_ERROR_UNCLOSABLE_INPUTSTREAM = "Was unable to close InputStream: %s";

	public EmailException(final String message, final Exception cause) {
		super(message, cause);
	}
}