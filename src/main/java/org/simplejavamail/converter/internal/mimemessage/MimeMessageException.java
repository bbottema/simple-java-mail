package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.MailException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during parsing of a {@link javax.mail.internet.MimeMessage}.
 *
 * @author Benny Bottema
 */
@SuppressWarnings("serial")
class MimeMessageException extends MailException {

	static final String INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM";

	MimeMessageException(@Nonnull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}