package org.simplejavamail.converter.internal.mimemessage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during production of a new {@link jakarta.mail.internet.MimeMessage}.
 */
@SuppressWarnings("serial")
class MimeMessageProduceException extends MailException {

	MimeMessageProduceException(@NotNull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}