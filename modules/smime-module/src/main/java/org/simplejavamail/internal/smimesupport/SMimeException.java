package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.MailException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during encryption / decryption of attachments.
 */
@SuppressWarnings("serial")
class SMimeException extends MailException {

	SMimeException(@SuppressWarnings("SameParameterValue") @Nonnull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}