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
	
	EmailException(@SuppressWarnings("SameParameterValue") final String message) {
		super(message);
	}
}