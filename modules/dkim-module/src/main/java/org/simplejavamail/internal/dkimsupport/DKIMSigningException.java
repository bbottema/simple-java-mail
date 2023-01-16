package org.simplejavamail.internal.dkimsupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during signing with the DKIM library.
 */
@SuppressWarnings("serial")
class DKIMSigningException extends MailException {

	static final String ERROR_SIGNING_DKIM_INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM";
	
	DKIMSigningException(@SuppressWarnings("SameParameterValue") @NotNull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}