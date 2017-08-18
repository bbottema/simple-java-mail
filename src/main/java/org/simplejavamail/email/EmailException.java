package org.simplejavamail.email;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during the creation of an email.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class EmailException extends MailException {
	
	static final String NAME_MISSING_FOR_EMBEDDED_IMAGE = "No name given for embedded image nor passed inside the data source";
	
	/**
	 * This email validation exception is thrown when the email is so really obviously malformed that it doesn't even pass (non-strict) basic
	 * splitting of the personal and address part of an {@link javax.mail.internet.InternetAddress}.
	 * <p>
	 * Proper email validation only happens when actually sending the email rather than when building it.
	 */
	static final String INVALID_EMAIL_ADDRESS = "Unable to (non-strict) parse email address [%s] into its name and address parts";
	
	EmailException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}
}