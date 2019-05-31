package org.simplejavamail.converter.internal.mimemessage;

import org.simplejavamail.MailException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during production of a new {@link javax.mail.internet.MimeMessage}.
 */
@SuppressWarnings("serial")
class MimeMessageProduceException extends MailException {

	MimeMessageProduceException(@Nonnull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}