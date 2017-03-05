package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.MailException;

/**
 * This exception is used to communicate errors during parsing of a {@link javax.mail.internet.MimeMessage}.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class MimeMessageException extends MailException {

	static final String INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM";

	MimeMessageException(final String message, final Exception cause) {
		super(message, cause);
	}
}